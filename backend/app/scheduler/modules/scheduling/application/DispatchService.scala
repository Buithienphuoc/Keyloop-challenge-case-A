package scheduler.modules.scheduling.application

import java.time.{Instant, LocalDate}

import play.api.libs.functional.syntax.*
import play.api.libs.json.*

import scheduler.modules.identity.{AuthContext, assertSameDealership}
import scheduler.modules.scheduling.domain.{Availability, AppointmentStatus, DayLoad, Workload}
import scheduler.shared.{AppError, Db, Log, Metrics, Validate}
import Workshop.rulesOf

/** Move a job: give a new bay, a new technician, or both. */
final case class Reassignment(bayId: Option[String], technicianId: Option[String], reason: Option[String])

object Reassignment:
  given Reads[Reassignment] = (
    (__ \ "bayId").readNullable(Validate.uuid) and
      (__ \ "technicianId").readNullable(Validate.uuid) and
      (__ \ "reason").readNullable(Validate.text(0, 300)).map(_.filter(_.nonEmpty))
  )(Reassignment.apply).filter(Validate.error("Give a bayId, a technicianId or both"))(r => r.bayId.isDefined || r.technicianId.isDefined)

final case class WorkloadView(date: LocalDate, timezone: String, load: DayLoad)

/**
 * The workshop controller's tools: see how loaded every bay and technician is on a day, and
 * move a job to another bay or technician (e.g. someone called in sick). The database's
 * no-overlap constraints still have the last word, so a move can never double-book anyone.
 */
final class DispatchService(
    db: Db,
    calendar: ResourceCalendar,
    reader: AppointmentReader,
    writer: AppointmentWriter,
    consultations: ConsultationBookingPort,
    workshop: WorkshopContext,
    clock: () => Instant
):

  def workload(auth: AuthContext, date: LocalDate): WorkloadView =
    val dealership = workshop.dealership(dealershipOf(auth))
    val rules = rulesOf(dealership)
    db.run { s =>
      val WorkshopResources(bays, technicians) = calendar.activeResources(s, dealership.id)
      val from = date.atStartOfDay(rules.timezone).toInstant
      val busy = calendar.busyPeriods(s, dealership.id, from, date.plusDays(1).atStartOfDay(rules.timezone).toInstant, clock())
      WorkloadView(date, rules.timezone.getId, Workload.forDay(rules, date, bays, technicians, busy))
    }

  def reassign(auth: AuthContext, id: String, move: Reassignment, log: Log): AppointmentView =
    val dealershipId = dealershipOf(auth)
    val zone = workshop.dealership(dealershipId).timezone
    val note = db.tx { s =>
      val locked = writer.lockForUpdate(s, id).getOrElse(throw AppError.notFound("Appointment"))
      assertSameDealership(auth, locked.dealershipId)
      val job = reader.jobDetails(s, id).getOrElse(throw AppError.notFound("Appointment"))
      if job.status != AppointmentStatus.CONFIRMED then
        throw AppError.conflict("NOT_REASSIGNABLE", s"Only confirmed jobs can be moved; this one is ${job.status}")

      val WorkshopResources(bays, technicians) = calendar.activeResources(s, dealershipId)
      val bayId = move.bayId.getOrElse(job.bayId)
      val technicianId = move.technicianId.getOrElse(job.technicianId)
      if bayId == job.bayId && technicianId == job.technicianId then
        throw AppError.unprocessable("NOTHING_TO_CHANGE", "The job already uses this bay and technician")

      val bay = bays.find(_.id == bayId).getOrElse(throw AppError.unprocessable("UNKNOWN_BAY", "Not an active bay of your dealership"))
      if bay.bayType != job.requirement.bayType then
        throw AppError.unprocessable("BAY_TYPE_MISMATCH", s"This job needs a ${job.requirement.bayType} bay")
      val tech = technicians.find(_.id == technicianId)
        .getOrElse(throw AppError.unprocessable("UNKNOWN_TECHNICIAN", "Not an active technician of your dealership"))
      if !tech.skills.contains(job.requirement.requiredSkill) then
        throw AppError.unprocessable("TECHNICIAN_NOT_QUALIFIED", s"${tech.name} does not have the ${job.requirement.requiredSkill} skill")
      if !Availability.isOnShift(tech, job.start.atZone(zone), job.end.atZone(zone)) then
        throw AppError.unprocessable("TECHNICIAN_NOT_ON_SHIFT", s"${tech.name} is not working for the whole job")

      // A hold that ran out could still sit in the way: release it first.
      writer.expireHolds(s, clock(), HoldScope.Around(dealershipId, None, job.start, job.end))
        .flatMap(_.consultationRequestId).foreach(consultations.reopen(s, _))
      try writer.reassign(s, id, bayId, technicianId)
      catch
        case _: SlotTakenError =>
          throw AppError.conflict("RESOURCE_BUSY", "That bay or technician already has a job at this time")

      val bayNames = bays.map(b => b.id -> b.name).toMap
      val techNames = technicians.map(t => t.id -> t.name).toMap
      def change(label: String, from: String, to: String, names: Map[String, String]) =
        Option.when(from != to)(s"$label ${names.getOrElse(from, "?")} → ${names.getOrElse(to, "?")}")
      val text = (change("bay", job.bayId, bayId, bayNames).toSeq ++ change("technician", job.technicianId, technicianId, techNames) ++
        move.reason.map(r => s"reason: $r")).mkString("; ")
      writer.recordEvent(s, AppointmentEvent(id, Some(job.status), job.status, auth.userId, Some(s"Moved: $text")))
      text
    }
    Metrics.reassignments.inc()
    log.info("appointment.reassigned", "appointmentId" -> id, "change" -> note)
    db.run(reader.get(_, id)).get

  private def dealershipOf(auth: AuthContext): String =
    auth.dealershipId.getOrElse(throw AppError.forbidden("Your account is not attached to a dealership"))
