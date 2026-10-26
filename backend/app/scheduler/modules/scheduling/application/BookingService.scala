package scheduler.modules.scheduling.application

import java.time.{Instant, ZonedDateTime}
import java.util.concurrent.ThreadLocalRandom

import scala.annotation.tailrec

import play.api.libs.json.Json

import scheduler.modules.identity.{AuthContext, Role}
import scheduler.modules.catalog.Dealership
import scheduler.modules.scheduling.domain.{AppointmentStatus, Availability, LocalDay, Requirement, WorkshopRules}
import scheduler.modules.scheduling.domain.Availability.StartValidationError
import scheduler.modules.vehicles.OwnedVehicles
import scheduler.shared.{AppError, Db, Ids, JsonFormats, Log, Metrics, Tracing, TransientStorageError}
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
    /** How long a hold keeps its slot while the customer confirms. */
    holdMinutes: Int = 10,
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
   *
   * With `hold = true` the slot is only reserved (HELD) until `holdMinutes` from now; the
   * customer confirms it with AppointmentService.confirmHold. A hold takes the bay, technician
   * and vehicle exactly like a booking, so the same constraints and retries apply.
   */
  def book(auth: AuthContext, input: BookRequest, idempotencyKey: Option[String], log: Log, hold: Boolean = false): BookingResult =
    if auth.role != Role.CUSTOMER then throw AppError.forbidden("Only customers can book for themselves")
    idempotencyKey.flatMap(replay(auth, _, log)).getOrElse {
      val plan = planFor(auth, input, idempotencyKey, hold)
      bookWithRetries(plan, log).getOrElse(slotUnavailable(plan, log))
    }

  /** Everything one booking needs, checked once before the first attempt. */
  private final case class Plan(
      auth: AuthContext,
      input: BookRequest,
      idempotencyKey: Option[String],
      hold: Boolean,
      dealership: Dealership,
      vehicleId: String,
      requirement: Requirement,
      rules: WorkshopRules,
      start: ZonedDateTime
  ):
    val end: ZonedDateTime = start.plusMinutes(requirement.durationMinutes)
    val logContext: Seq[(String, Any)] = Seq("dealershipId" -> dealership.id, "start" -> JsonFormats.offsetIso(start),
      "durationMinutes" -> requirement.durationMinutes, "hold" -> hold)

  private def planFor(auth: AuthContext, input: BookRequest, idempotencyKey: Option[String], hold: Boolean): Plan =
    val dealership = workshop.dealership(input.dealershipId)
    val vehicle = vehicles.getOwned(auth.userId, input.vehicleId)
    val requirement = db.run(s => workshop.requirementFor(s, auth, dealership.id, input, Some(vehicle)))
    val rules = rulesOf(dealership)
    val start = Availability.validateStart(rules, requirement, input.start, clock()) match
      case Right(start) => start
      case Left(error)  => throw AppError.unprocessable(error.toString, StartErrors(error))
    Plan(auth, input, idempotencyKey, hold, dealership, vehicle.id, requirement, rules, start)

  /** Some(result) once booked or replayed; None when no bay + technician pair is left. */
  @tailrec private def bookWithRetries(plan: Plan, log: Log, n: Int = 1): Option[BookingResult] =
    if n > MaxAttempts then None
    else
      val outcome: Either[String, Option[BookingResult]] =
        try
          val id = Tracing.span("booking.attempt", "attempt" -> n.toString, "hold" -> plan.hold.toString)(attempt(plan, n, log))
          Right(Some(booked(plan, id, n, log)))
        catch
          case _: NoCapacity => Right(None)
          case _: AlreadyBooked if plan.idempotencyKey.isDefined => Right(plan.idempotencyKey.flatMap(replay(plan.auth, _, log)))
          case _: VehicleBusyError =>
            log.info("booking.vehicle_already_booked", plan.logContext*)
            throw AppError.conflict("VEHICLE_ALREADY_BOOKED", "This vehicle already has an appointment at that time")
          case _: SlotTakenError        => Left("race")
          case _: TransientStorageError => Left("deadlock")
      outcome match
        case Right(result) => result
        case Left(reason) =>
          Metrics.bookingConflicts.labelValues(reason).inc()
          log.warn("booking.race_lost_retrying", plan.logContext ++ Seq("attempt" -> n, "reason" -> reason)*)
          backoff(n)
          bookWithRetries(plan, log, n + 1)

  /** One booking attempt in its own transaction; returns the new appointment's id. */
  private def attempt(plan: Plan, n: Int, log: Log): String = db.tx { s =>
    import plan.*
    idempotencyKey.foreach { key =>
      writer.lockIdempotencyKey(s, auth.userId, key)
      if writer.findIdByIdempotencyKey(s, auth.userId, key).isDefined then throw AlreadyBooked()
    }
    val WorkshopResources(bays, technicians) = calendar.activeResources(s, dealership.id)
    if !isOfferedHere(requirement, bays, technicians) then
      throw AppError.unprocessable("NOT_OFFERED_HERE", "This dealership has no bay or technician for this job")
    val (dayStart, dayEnd) = LocalDay.bounds(start.toLocalDate, rules.timezone)
    // Holds that ran out still occupy their rows until released; release the ones that
    // could stand in this booking's way, so the constraints only see live reservations.
    val now = clock()
    Holds.releaseExpired(s, writer, consultations, now, HoldScope.Around(dealership.id, Some(vehicleId), dayStart, dayEnd))
    val busy = calendar.busyPeriods(s, dealership.id, dayStart, dayEnd, now)
    val allocation = Availability.allocate(requirement, start, bays, technicians, busy).allocation.getOrElse(throw NoCapacity())

    input.consultationRequestId.foreach { id =>
      if !consultations.markBooked(s, id) then
        throw AppError.conflict("CONSULTATION_NOT_BOOKABLE", "This request has already been booked or is no longer approved")
    }
    val status = if hold then AppointmentStatus.HELD else AppointmentStatus.CONFIRMED
    val appointmentId = writer.insert(
      s,
      NewAppointment(
        reference = Ids.humanReference("SV"),
        dealershipId = dealership.id,
        customerId = auth.userId,
        vehicleId = vehicleId,
        servicePackageId = input.packageId,
        consultationRequestId = input.consultationRequestId,
        bayId = allocation.bay.id,
        technicianId = allocation.technician.id,
        start = start.toInstant,
        end = end.toInstant,
        customerNote = input.customerNote,
        idempotencyKey = idempotencyKey,
        createdBy = auth.userId,
        status = status,
        holdExpiresAt = Option.when(hold)(now.plusSeconds(holdMinutes * 60L))
      )
    )
    writer.recordEvent(s, AppointmentEvent(appointmentId, None, status, auth.userId, None))
    log.debug("booking.allocated", logContext ++ Seq("attempt" -> n, "bayId" -> allocation.bay.id, "technicianId" -> allocation.technician.id)*)
    appointmentId
  }

  /** After the commit: read the new appointment back, count it and log it. */
  private def booked(plan: Plan, id: String, n: Int, log: Log): BookingResult =
    val appointment = db.run(s => reader.get(s, id)).getOrElse(throw IllegalStateException(s"Appointment $id vanished after commit"))
    if plan.hold then Metrics.holds.labelValues("created").inc()
    else Metrics.bookingsCreated.labelValues(plan.dealership.code, if plan.input.packageId.isDefined then "package" else "consultation").inc()
    log.info(if plan.hold then "booking.held" else "booking.created", plan.logContext ++ Seq("appointmentId" -> id,
      "reference" -> appointment.reference, "attempt" -> n, "bayId" -> appointment.bay.id, "technicianId" -> appointment.technician.id)*)
    BookingResult(appointment, replayed = false)

  /** Nothing left at that time: 409 with the nearest free times. */
  private def slotUnavailable(plan: Plan, log: Log): Nothing =
    Metrics.bookingConflicts.labelValues("unavailable").inc()
    val alternatives = db.run(s => availability.nearest(s, plan.rules, plan.requirement, plan.dealership.id, plan.start))
    log.info("booking.slot_unavailable", plan.logContext :+ ("alternatives" -> alternatives.map(a => JsonFormats.offsetIso(a.start)))*)
    throw AppError.conflict(
      "SLOT_UNAVAILABLE",
      "Sorry, that time has just been taken. Here are the nearest free times.",
      Some(Json.obj("alternatives" -> alternatives))
    )

  private def replay(auth: AuthContext, key: String, log: Log): Option[BookingResult] =
    db.run { s =>
      writer.findIdByIdempotencyKey(s, auth.userId, key).map { existingId =>
        Metrics.bookingIdempotentReplays.inc()
        log.info("booking.idempotent_replay", "appointmentId" -> existingId)
        BookingResult(reader.get(s, existingId).get, replayed = true)
      }
    }
