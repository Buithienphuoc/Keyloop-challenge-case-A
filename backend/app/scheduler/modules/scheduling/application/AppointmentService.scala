package scheduler.modules.scheduling.application

import java.time.{Instant, LocalDate}

import scheduler.modules.identity.{AuthContext, Role, assertSameDealership}
import scheduler.modules.scheduling.domain.{AppointmentStatus, Lifecycle}
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
    assertCanSee(s, auth, view)
    view
  }

  def listMine(auth: AuthContext): Seq[AppointmentView] = db.run(reader.list(_, AppointmentFilter(customerId = Some(auth.userId))))

  /** A dealership's appointments for one local day (advisor / admin board). */
  def listForDealershipDay(auth: AuthContext, date: LocalDate): Seq[AppointmentView] =
    val dealershipId = auth.dealershipId.getOrElse(throw AppError.forbidden())
    val zone = workshop.dealership(dealershipId).timezone
    db.run(reader.list(_, AppointmentFilter(dealershipId = Some(dealershipId), from = Some(dayStart(date, zone)), to = Some(dayStart(date.plusDays(1), zone)))))

  /** The signed-in technician's jobs for one local day. */
  def listTechnicianDay(auth: AuthContext, date: LocalDate): Seq[AppointmentView] = db.run { s =>
    (reader.technicianIdForUser(s, auth.userId), auth.dealershipId) match
      case (Some(technicianId), Some(dealershipId)) =>
        val zone = workshop.dealership(dealershipId).timezone
        reader.list(s, AppointmentFilter(technicianId = Some(technicianId), from = Some(dayStart(date, zone)), to = Some(dayStart(date.plusDays(1), zone))))
      case _ => throw AppError.forbidden("Your account is not linked to a technician profile")
  }

  def changeStatus(auth: AuthContext, id: String, to: AppointmentStatus, reason: Option[String], log: Log): AppointmentView =
    val from = db.tx { s =>
      val a = writer.lockForUpdate(s, id).getOrElse(throw AppError.notFound("Appointment"))
      if auth.role == Role.CUSTOMER && a.customerId != auth.userId then throw AppError.notFound("Appointment")
      if auth.role != Role.CUSTOMER then assertSameDealership(auth, a.dealershipId)
      if auth.role == Role.TECHNICIAN && !reader.technicianIdForUser(s, auth.userId).contains(a.technicianId) then
        throw AppError.forbidden("This job is assigned to another technician")

      Lifecycle.checkTransition(a.status, to, auth.role, a.start.toEpochMilli, clock().toEpochMilli).foreach { error =>
        throw TransitionErrors(error)(a.status, to)
      }

      writer.setStatus(s, id, to, if to == AppointmentStatus.CANCELLED then reason else None)
      writer.recordEvent(s, AppointmentEvent(id, Some(a.status), to, auth.userId, reason))
      if to == AppointmentStatus.CANCELLED then a.consultationRequestId.foreach(consultations.reopen(s, _))
      a.status
    }
    Metrics.appointmentStatusChanges.labelValues(to.toString).inc()
    log.info("appointment.status_changed", "appointmentId" -> id, "from" -> from, "to" -> to)
    db.run(reader.get(_, id)).get

  private def assertCanSee(s: DbSession, auth: AuthContext, view: AppointmentView): Unit =
    if auth.role == Role.CUSTOMER then
      if view.customer.id != auth.userId then throw AppError.notFound("Appointment")
    else
      assertSameDealership(auth, view.dealership.id)
      if auth.role == Role.TECHNICIAN && !reader.technicianIdForUser(s, auth.userId).contains(view.technician.id) then
        throw AppError.notFound("Appointment")

  private def dayStart(date: LocalDate, zone: java.time.ZoneId): Instant = date.atStartOfDay(zone).toInstant
