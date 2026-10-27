package scheduler.modules.scheduling.application

import java.time.{Instant, LocalDate}

import scheduler.modules.identity.{AuthContext, Role, assertCanSee, requireDealership}
import scheduler.modules.scheduling.domain.{AppointmentStatus, Lifecycle, LocalDay}
import scheduler.modules.scheduling.domain.Lifecycle.TransitionError
import scheduler.shared.{AppError, Db, DbSession, Log, Metrics}

object AppointmentService:
  /** How each lifecycle rule violation is reported. A new rule = one new entry (OCP). */
  private val TransitionErrors: Map[TransitionError, (AppointmentStatus, AppointmentStatus) => AppError] = Map(
    TransitionError.ROLE_NOT_ALLOWED -> ((_, to) => AppError.forbidden(s"Your role cannot set an appointment to $to")),
    TransitionError.INVALID_TRANSITION -> ((from, to) => AppError.conflict("INVALID_STATUS_TRANSITION", s"Cannot go from $from to $to")),
    TransitionError.ALREADY_STARTED -> ((_, _) =>
      AppError.conflict("TOO_LATE_TO_CANCEL", "This appointment has already started. Please call the dealership.")),
    TransitionError.NOT_STARTED_YET -> ((_, _) =>
      AppError.conflict("NOT_STARTED_YET", "You can only mark a no-show after the start time"))
  )

  private enum HoldOutcome:
    case Confirmed, AlreadyConfirmed, Expired

/** Viewing appointments and moving them through their lifecycle. */
final class AppointmentService(
    db: Db,
    reader: AppointmentReader,
    writer: AppointmentWriter,
    consultations: ConsultationBookingPort,
    workshop: WorkshopContext,
    clock: () => Instant
):
  import AppointmentService.TransitionErrors

  def get(auth: AuthContext, id: String): AppointmentView = db.run { s =>
    val view = reader.get(s, id).getOrElse(throw AppError.notFound("Appointment"))
    assertCanSee(auth, view.customer.id, view.dealership.id, "Appointment")
    if !isOwnJobOrNotTechnician(s, auth, view.technician.id) then throw AppError.notFound("Appointment")
    view
  }

  def listMine(auth: AuthContext): Seq[AppointmentView] = db.run(reader.list(_, AppointmentFilter(customerId = Some(auth.userId))))

  /** A dealership's appointments for one local day (advisor / admin board). */
  def listForDealershipDay(auth: AuthContext, date: LocalDate): Seq[AppointmentView] =
    val dealershipId = auth.requireDealership
    val (from, to) = LocalDay.bounds(date, workshop.dealership(dealershipId).timezone)
    db.run(reader.list(_, AppointmentFilter(dealershipId = Some(dealershipId), from = Some(from), to = Some(to))))

  /**
   * Every job of the admin's dealership, newest first, optionally in one status: what is new,
   * in the workshop, waiting to be paid and finished. Holds still being confirmed are left out.
   */
  def listDealershipJobs(auth: AuthContext, status: Option[AppointmentStatus]): Seq[AppointmentView] =
    val statuses = status.fold(AppointmentStatus.values.toSet - AppointmentStatus.HELD)(Set(_))
    db.run(reader.list(_, AppointmentFilter(dealershipId = Some(auth.requireDealership), statuses = statuses, newestFirst = true)))

  /** The signed-in technician's jobs for one local day. */
  def listTechnicianDay(auth: AuthContext, date: LocalDate): Seq[AppointmentView] = db.run { s =>
    (reader.technicianIdForUser(s, auth.userId), auth.dealershipId) match
      case (Some(technicianId), Some(dealershipId)) =>
        val (from, to) = LocalDay.bounds(date, workshop.dealership(dealershipId).timezone)
        reader.list(s, AppointmentFilter(technicianId = Some(technicianId), from = Some(from), to = Some(to)))
      case _ => throw AppError.forbidden("Your account is not linked to a technician profile")
  }

  def changeStatus(auth: AuthContext, id: String, to: AppointmentStatus, reason: Option[String], log: Log): AppointmentView =
    val from = db.tx { s =>
      val a = writer.lockForUpdate(s, id).getOrElse(throw AppError.notFound("Appointment"))
      assertCanSee(auth, a.customerId, a.dealershipId, "Appointment")
      if !isOwnJobOrNotTechnician(s, auth, a.technicianId) then throw AppError.forbidden("This job is assigned to another technician")

      Lifecycle.checkTransition(a.status, to, auth.role, a.start.toEpochMilli, clock().toEpochMilli).foreach { error =>
        throw TransitionErrors(error)(a.status, to)
      }

      writer.setStatus(s, id, to, if to == AppointmentStatus.CANCELLED then reason else None)
      writer.recordEvent(s, AppointmentEvent(id, Some(a.status), to, auth.userId, reason))
      if to == AppointmentStatus.CANCELLED then a.consultationRequestId.foreach(consultations.reopen(s, _))
      a.status
    }
    if from == AppointmentStatus.HELD then Metrics.holds.labelValues("released").inc()
    Metrics.appointmentStatusChanges.labelValues(to.toString).inc()
    log.info("appointment.status_changed", "appointmentId" -> id, "from" -> from, "to" -> to)
    reload(id)

  /**
   * HELD → CONFIRMED, while the hold is still running. Confirming twice answers with the
   * same booking. A hold that ran out is released on the spot (409 HOLD_EXPIRED), so the
   * customer is never told a slot is theirs after someone else could have taken it.
   */
  def confirmHold(auth: AuthContext, id: String, log: Log): AppointmentView =
    import AppointmentService.HoldOutcome
    val outcome = db.tx { s =>
      val a = writer.lockForUpdate(s, id).getOrElse(throw AppError.notFound("Appointment"))
      if a.customerId != auth.userId then throw AppError.notFound("Appointment")
      a.status match
        case AppointmentStatus.CONFIRMED => HoldOutcome.AlreadyConfirmed
        case AppointmentStatus.HELD if a.holdExpiresAt.exists(_.isAfter(clock())) =>
          writer.setStatus(s, id, AppointmentStatus.CONFIRMED, None)
          writer.recordEvent(s, AppointmentEvent(id, Some(AppointmentStatus.HELD), AppointmentStatus.CONFIRMED, auth.userId, None))
          HoldOutcome.Confirmed
        case AppointmentStatus.HELD =>
          writer.setStatus(s, id, AppointmentStatus.EXPIRED, None)
          writer.recordEvent(s, AppointmentEvent(id, Some(AppointmentStatus.HELD), AppointmentStatus.EXPIRED, auth.userId, Some("Hold expired")))
          a.consultationRequestId.foreach(consultations.reopen(s, _))
          HoldOutcome.Expired
        case AppointmentStatus.EXPIRED => HoldOutcome.Expired
        case other => throw AppError.conflict("NOT_HELD", s"This appointment is $other, not on hold")
    }
    outcome match
      case HoldOutcome.Expired =>
        Metrics.holds.labelValues("expired").inc()
        log.info("booking.hold_expired_on_confirm", "appointmentId" -> id)
        throw AppError.conflict("HOLD_EXPIRED", "Your hold on this time has run out. Please choose a time again.")
      case HoldOutcome.Confirmed =>
        Metrics.holds.labelValues("confirmed").inc()
        Metrics.appointmentStatusChanges.labelValues(AppointmentStatus.CONFIRMED.toString).inc()
        log.info("booking.hold_confirmed", "appointmentId" -> id)
      case HoldOutcome.AlreadyConfirmed => ()
    reload(id)

  /** Technicians only ever see and move the jobs assigned to them. */
  private def isOwnJobOrNotTechnician(s: DbSession, auth: AuthContext, technicianId: String): Boolean =
    auth.role != Role.TECHNICIAN || reader.technicianIdForUser(s, auth.userId).contains(technicianId)

  /** The appointment as it is after a committed change. */
  private def reload(id: String): AppointmentView =
    db.run(reader.get(_, id)).getOrElse(throw IllegalStateException(s"Appointment $id vanished after commit"))
