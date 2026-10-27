package scheduler.modules.scheduling.application

import java.time.{Instant, LocalDate}

import play.api.libs.functional.syntax.*
import play.api.libs.json.*

import scheduler.modules.identity.{AuthContext, Role, assertSameDealership, requireDealership}
import scheduler.modules.scheduling.domain.{Availability, AppointmentStatus, DayLoad, LocalDay, TechnicianAssignment, Workload}
import scheduler.shared.{AppError, Db, DbSession, Log, Metrics, Validate}
import Workshop.rulesOf

/** Move a job: give a new bay, a new technician, or both. */
final case class Reassignment(bayId: Option[String], technicianId: Option[String], reason: Option[String])

object Reassignment:
  given Reads[Reassignment] = (
    (__ \ "bayId").readNullable(Validate.uuid) and
      (__ \ "technicianId").readNullable(Validate.uuid) and
      (__ \ "reason").readNullable(Validate.text(0, 300)).map(_.filter(_.nonEmpty))
  )(Reassignment.apply).filter(Validate.error("Give a bayId, a technicianId or both"))(r => r.bayId.isDefined || r.technicianId.isDefined)

/** Put a technician on a job. A technician taking a job for themselves may leave it out. */
final case class TechnicianAssignmentRequest(technicianId: Option[String])

object TechnicianAssignmentRequest:
  given Reads[TechnicianAssignmentRequest] =
    (__ \ "technicianId").readNullable(Validate.uuid).map(TechnicianAssignmentRequest.apply)

final case class WorkloadView(date: LocalDate, timezone: String, load: DayLoad)

/**
 * The workshop controller's tools: see how loaded every bay and technician is on a day, and
 * move a job to another bay or technician (e.g. someone called in sick). Also how a technician
 * takes an open service job, and how an advisor assigns one. The database's
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
    val dealership = workshop.dealership(auth.requireDealership)
    val rules = rulesOf(dealership)
    db.run { s =>
      val WorkshopResources(bays, technicians) = calendar.activeResources(s, dealership.id)
      val (from, to) = LocalDay.bounds(date, rules.timezone)
      val busy = calendar.busyPeriods(s, dealership.id, from, to, clock())
      WorkloadView(date, rules.timezone.getId, Workload.forDay(rules, date, bays, technicians, busy))
    }

  def reassign(auth: AuthContext, id: String, move: Reassignment, log: Log): AppointmentView =
    val note = db.tx(s => moveJob(s, auth, id, move, "Moved")(_ => ()))
    Metrics.reassignments.inc()
    log.info("appointment.reassigned", "appointmentId" -> id, "change" -> note)
    reload(id)

  /**
   * Puts a technician on an open (or already assigned) service job: a technician takes it
   * themselves, or an advisor assigns one. See TechnicianAssignment for who may do what. The
   * same skill, shift and no-overlap checks as a move apply.
   */
  def assignTechnician(auth: AuthContext, id: String, input: TechnicianAssignmentRequest, log: Log): AppointmentView =
    val note = db.tx { s =>
      val ownTechnician = reader.technicianIdForUser(s, auth.userId)
      val target = (auth.role, input.technicianId) match
        case (Role.TECHNICIAN, None) => ownTechnician.getOrElse(throw refusal(TechnicianAssignment.Refusal.NOT_LINKED_TO_TECHNICIAN))
        case (_, Some(technicianId)) => technicianId
        case (_, None)               => throw AppError.unprocessable("TECHNICIAN_REQUIRED", "Choose the technician to assign")
      TechnicianAssignment.check(auth.role, auth.userId, ownTechnician, target, reader.technicianAccount(s, target))
        .foreach(r => throw refusal(r))
      moveJob(s, auth, id, Reassignment(None, Some(target), None), "Assigned", allowSame = true) { job =>
        if job.technicianAssigned && job.technicianId == target then
          throw AppError.conflict("ALREADY_ASSIGNED", "This technician already has this job")
        // A technician takes open jobs only; moving a taken job is for the advisor or the controller.
        if auth.role == Role.TECHNICIAN && job.technicianAssigned then
          throw AppError.conflict("ALREADY_TAKEN", "Another technician has already taken this job")
      }
    }
    Metrics.reassignments.inc()
    log.info("appointment.technician_assigned", "appointmentId" -> id, "change" -> note, "by" -> auth.role)
    reload(id)

  private def refusal(r: TechnicianAssignment.Refusal): AppError = r match
    case TechnicianAssignment.Refusal.ROLE_NOT_ALLOWED => AppError.forbidden("Your role cannot assign technicians")
    case TechnicianAssignment.Refusal.ONLY_SELF => AppError.forbidden("A technician can only take a job for themselves")
    case TechnicianAssignment.Refusal.NOT_LINKED_TO_TECHNICIAN => AppError.forbidden("Your account is not linked to a technician profile")
    case TechnicianAssignment.Refusal.CANNOT_ASSIGN_SELF =>
      AppError.unprocessable("CANNOT_ASSIGN_SELF", "An advisor cannot assign themselves to a job")
    case TechnicianAssignment.Refusal.NOT_A_TECHNICIAN =>
      AppError.unprocessable("NOT_A_TECHNICIAN", "Only a technician can be assigned, not an advisor or other staff")

  /**
   * The checks and the write shared by every way of changing a job's bay or technician. A move
   * that sets a technician also marks the job as taken. Returns the audit note.
   */
  private def moveJob(s: DbSession, auth: AuthContext, id: String, move: Reassignment, verb: String, allowSame: Boolean = false)(
      extraCheck: JobDetails => Unit
  ): String =
    val dealershipId = auth.requireDealership
    val zone = workshop.dealership(dealershipId).timezone
    val locked = writer.lockForUpdate(s, id).getOrElse(throw AppError.notFound("Appointment"))
    assertSameDealership(auth, locked.dealershipId)
    val job = reader.jobDetails(s, id).getOrElse(throw AppError.notFound("Appointment"))
    if job.status != AppointmentStatus.CONFIRMED then
      throw AppError.conflict("NOT_REASSIGNABLE", s"Only confirmed jobs can be moved; this one is ${job.status}")
    extraCheck(job)

    val WorkshopResources(bays, technicians) = calendar.activeResources(s, dealershipId)
    val bayId = move.bayId.getOrElse(job.bayId)
    val technicianId = move.technicianId.getOrElse(job.technicianId)
    val unchanged = bayId == job.bayId && technicianId == job.technicianId
    if unchanged && !allowSame then
      throw AppError.unprocessable("NOTHING_TO_CHANGE", "The job already uses this bay and technician")

    // Only what changes is checked: moving the bay of a job whose technician has since been
    // deactivated (or the reverse) must still work, so the unchanged side is left alone.
    if bayId != job.bayId then
      val bay = bays.find(_.id == bayId).getOrElse(throw AppError.unprocessable("UNKNOWN_BAY", "Not an active bay of your dealership"))
      if bay.bayType != job.requirement.bayType then
        throw AppError.unprocessable("BAY_TYPE_MISMATCH", s"This job needs a ${job.requirement.bayType} bay")
    if technicianId != job.technicianId then
      val tech = technicians.find(_.id == technicianId)
        .getOrElse(throw AppError.unprocessable("UNKNOWN_TECHNICIAN", "Not an active technician of your dealership"))
      if !tech.skills.contains(job.requirement.requiredSkill) then
        throw AppError.unprocessable("TECHNICIAN_NOT_QUALIFIED", s"${tech.name} does not have the ${job.requirement.requiredSkill} skill")
      if !Availability.isOnShift(tech, job.start.atZone(zone), job.end.atZone(zone)) then
        throw AppError.unprocessable("TECHNICIAN_NOT_ON_SHIFT", s"${tech.name} is not working for the whole job")

    if !unchanged then
      // A hold that ran out could still sit in the way: release it first.
      Holds.releaseExpired(s, writer, consultations, clock(), HoldScope.Around(dealershipId, None, job.start, job.end))
      try writer.reassign(s, id, bayId, technicianId)
      catch
        case _: SlotTakenError =>
          throw AppError.conflict("RESOURCE_BUSY", "That bay or technician already has a job at this time")
    if move.technicianId.isDefined then writer.markTechnicianAssigned(s, id, auth.userId)

    val bayNames = bays.map(b => b.id -> b.name).toMap
    val techNames = technicians.map(t => t.id -> t.name).toMap
    def change(label: String, from: String, to: String, names: Map[String, String]) =
      Option.when(from != to)(s"$label ${names.getOrElse(from, "?")} → ${names.getOrElse(to, "?")}")
    val taken = Option.when(unchanged)(s"technician ${techNames.getOrElse(technicianId, "?")}")
    val text = (change("bay", job.bayId, bayId, bayNames).toSeq ++ change("technician", job.technicianId, technicianId, techNames) ++
      taken ++ move.reason.map(r => s"reason: $r")).mkString("; ")
    writer.recordEvent(s, AppointmentEvent(id, Some(job.status), job.status, auth.userId, Some(s"$verb: $text")))
    text

  private def reload(id: String): AppointmentView =
    db.run(reader.get(_, id)).getOrElse(throw IllegalStateException(s"Appointment $id vanished after commit"))
