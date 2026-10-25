package scheduler.modules.scheduling.application

import java.time.Instant
import java.util.concurrent.ThreadLocalRandom

import scala.annotation.tailrec

import play.api.libs.json.Json

import scheduler.modules.identity.{AuthContext, Role}
import scheduler.modules.scheduling.domain.{AppointmentStatus, Availability}
import scheduler.modules.scheduling.domain.Availability.StartValidationError
import scheduler.modules.vehicles.OwnedVehicles
import scheduler.shared.{AppError, Db, Ids, JsonFormats, Log, Metrics, TransientStorageError}
import SchedulingJson.given
import Workshop.{isOfferedHere, rulesOf}

final case class BookingResult(appointment: AppointmentView, replayed: Boolean)

object BookingService:
  val MaxAttempts = 5

  private val StartErrors: Map[StartValidationError, String] = Map(
    StartValidationError.INVALID_START -> "The start time is not a valid date and time",
    StartValidationError.CLOSED_DAY -> "The workshop is closed on that day",
    StartValidationError.OUTSIDE_OPENING_HOURS -> "The job would not fit inside opening hours",
    StartValidationError.NOT_ON_SLOT_GRID -> "Please choose one of the offered start times",
    StartValidationError.TOO_SOON -> "That time is too soon to book. Please choose a later slot",
    StartValidationError.TOO_FAR_AHEAD -> "That date is too far ahead to book yet"
  )

  /** Small random jitter so racing requests do not retry in lock-step. Runs on a virtual thread. */
  val jitter: Int => Unit = _ => Thread.sleep(5 + ThreadLocalRandom.current().nextLong(20))

  /** No bay + technician pair is free for the whole job. */
  private final class NoCapacity extends RuntimeException(null, null, false, false)

  /** Another request with the same Idempotency-Key committed first. */
  private final class AlreadyBooked extends RuntimeException(null, null, false, false)

/** The write side of scheduling: book one appointment, safely under concurrency. */
final class BookingService(
    db: Db,
    calendar: ResourceCalendar,
    reader: AppointmentReader,
    writer: AppointmentWriter,
    consultations: ConsultationBookingPort,
    vehicles: OwnedVehicles,
    workshop: WorkshopContext,
    availability: AvailabilityService,
    clock: () => Instant,
    /** Pause between retries; injectable so unit tests do not wait. */
    backoff: Int => Unit = BookingService.jitter
):
  import BookingService.*

  /**
   * Concurrency strategy:
   *  1. inside a transaction, read free resources and pick a bay + technician (best fit);
   *  2. insert; the database's EXCLUDE constraints are the final judge;
   *  3. if another request won the same bay or technician (SlotTakenError) or the database
   *     broke a deadlock (TransientStorageError), retry with fresh data, up to MaxAttempts;
   *  4. if nothing is left, answer 409 with the nearest alternative slots.
   * Requests with the same Idempotency-Key queue on a lock and the later ones replay the first.
   */
  def book(auth: AuthContext, input: BookRequest, idempotencyKey: Option[String], log: Log): BookingResult =
    if auth.role != Role.CUSTOMER then throw AppError.forbidden("Only customers can book for themselves")

    idempotencyKey.flatMap(replay(auth, _, log)) match
      case Some(earlier) => earlier
      case None =>
        val dealership = workshop.dealership(input.dealershipId)
        val vehicle = vehicles.getOwned(auth.userId, input.vehicleId)
        val requirement = db.run(s => workshop.requirementFor(s, auth, dealership.id, input, Some(vehicle)))
        val rules = rulesOf(dealership)
        val start = Availability.validateStart(rules, requirement, input.start, clock()) match
          case Right(start) => start
          case Left(error)  => throw AppError.unprocessable(error.toString, StartErrors(error))
        val end = start.plusMinutes(requirement.durationMinutes)
        val ctx = Seq("dealershipId" -> dealership.id, "start" -> JsonFormats.offsetIso(start), "durationMinutes" -> requirement.durationMinutes)

        /** One booking attempt in its own transaction; returns the new appointment's id. */
        def attempt(n: Int): String = db.tx { s =>
          idempotencyKey.foreach { key =>
            writer.lockIdempotencyKey(s, auth.userId, key)
            if writer.findIdByIdempotencyKey(s, auth.userId, key).isDefined then throw AlreadyBooked()
          }
          val WorkshopResources(bays, technicians) = calendar.activeResources(s, dealership.id)
          if !isOfferedHere(requirement, bays, technicians) then
            throw AppError.unprocessable("NOT_OFFERED_HERE", "This dealership has no bay or technician for this job")
          val dayStart = start.toLocalDate.atStartOfDay(rules.timezone).toInstant
          val busy = calendar.busyPeriods(s, dealership.id, dayStart, start.toLocalDate.plusDays(1).atStartOfDay(rules.timezone).toInstant)
          val allocation = Availability.allocate(requirement, start, bays, technicians, busy).allocation.getOrElse(throw NoCapacity())

          input.consultationRequestId.foreach { id =>
            if !consultations.markBooked(s, id) then
              throw AppError.conflict("CONSULTATION_NOT_BOOKABLE", "This request has already been booked or is no longer approved")
          }
          val appointmentId = writer.insert(
            s,
            NewAppointment(
              reference = Ids.humanReference("SV"),
              dealershipId = dealership.id,
              customerId = auth.userId,
              vehicleId = vehicle.id,
              servicePackageId = input.packageId,
              consultationRequestId = input.consultationRequestId,
              bayId = allocation.bay.id,
              technicianId = allocation.technician.id,
              start = start.toInstant,
              end = end.toInstant,
              customerNote = input.customerNote,
              idempotencyKey = idempotencyKey,
              createdBy = auth.userId
            )
          )
          writer.recordEvent(s, AppointmentEvent(appointmentId, None, AppointmentStatus.CONFIRMED, auth.userId, None))
          log.debug("booking.allocated", ctx ++ Seq("attempt" -> n, "bayId" -> allocation.bay.id, "technicianId" -> allocation.technician.id)*)
          appointmentId
        }

        /** Some(result) once booked or replayed; None when no bay/technician pair is left. */
        @tailrec def loop(n: Int): Option[BookingResult] =
          if n > MaxAttempts then None
          else
            val outcome: Either[String, Option[BookingResult]] =
              try
                val id = attempt(n)
                val appointment = db.run(s => reader.get(s, id)).get
                Metrics.bookingsCreated.labelValues(dealership.code, if input.packageId.isDefined then "package" else "consultation").inc()
                log.info("booking.created", ctx ++ Seq("appointmentId" -> id, "reference" -> appointment.reference, "attempt" -> n,
                  "bayId" -> appointment.bay.id, "technicianId" -> appointment.technician.id)*)
                Right(Some(BookingResult(appointment, replayed = false)))
              catch
                case _: NoCapacity => Right(None)
                case _: AlreadyBooked if idempotencyKey.isDefined => Right(idempotencyKey.flatMap(replay(auth, _, log)))
                case _: VehicleBusyError =>
                  log.info("booking.vehicle_already_booked", ctx*)
                  throw AppError.conflict("VEHICLE_ALREADY_BOOKED", "This vehicle already has an appointment at that time")
                case _: SlotTakenError        => Left("race")
                case _: TransientStorageError => Left("deadlock")
            outcome match
              case Right(result) => result
              case Left(reason) =>
                Metrics.bookingConflicts.labelValues(reason).inc()
                log.warn("booking.race_lost_retrying", ctx ++ Seq("attempt" -> n, "reason" -> reason)*)
                backoff(n)
                loop(n + 1)

        loop(1).getOrElse {
          Metrics.bookingConflicts.labelValues("unavailable").inc()
          val alternatives = db.run(s => availability.nearest(s, rules, requirement, dealership.id, start))
          log.info("booking.slot_unavailable", ctx :+ ("alternatives" -> alternatives.map(a => JsonFormats.offsetIso(a.start)))*)
          throw AppError.conflict(
            "SLOT_UNAVAILABLE",
            "Sorry, that time has just been taken. Here are the nearest free times.",
            Some(Json.obj("alternatives" -> alternatives))
          )
        }

  private def replay(auth: AuthContext, key: String, log: Log): Option[BookingResult] =
    db.run { s =>
      writer.findIdByIdempotencyKey(s, auth.userId, key).map { existingId =>
        Metrics.bookingIdempotentReplays.inc()
        log.info("booking.idempotent_replay", "appointmentId" -> existingId)
        BookingResult(reader.get(s, existingId).get, replayed = true)
      }
    }
