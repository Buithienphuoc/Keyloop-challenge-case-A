package scheduler.modules.admin.application

import java.time.LocalTime

import play.api.libs.functional.syntax.*
import play.api.libs.json.*

import scheduler.modules.admin.*
import scheduler.modules.identity.{AuthContext, PublicUser, Role, StaffMember, requireDealership}
import scheduler.modules.identity.application.{CreateUser, IdentityRequests, UserAccounts}
import scheduler.shared.{AppError, Db, Log, Validate}

object AdminRequests:
  private val weekdays = Validate.listOf(Validate.int(1, 7), min = 1, max = 7)

  final case class NewBay(name: String, bayType: String)
  given Reads[NewBay] = (
    (__ \ "name").read(Validate.text(1, 60)) and
      (__ \ "bayType").read(Validate.text(1, 100))
  )(NewBay.apply)

  final case class Login(email: String, password: String)
  given Reads[Login] = (
    (__ \ "email").read(Validate.email) and
      (__ \ "password").read(IdentityRequests.password)
  )(Login.apply)

  final case class NewTechnician(
      displayName: String,
      skills: List[String],
      shiftStart: LocalTime,
      shiftEnd: LocalTime,
      workDays: List[Int],
      login: Option[Login]
  )
  given Reads[NewTechnician] = (
    (__ \ "displayName").read(Validate.text(2, 80)) and
      (__ \ "skills").read(Validate.listOf(Reads.StringReads, min = 1)) and
      (__ \ "shiftStart").read(Validate.hhmm) and
      (__ \ "shiftEnd").read(Validate.hhmm) and
      (__ \ "workDays").read(weekdays) and
      (__ \ "login").readNullable[Login]
  )(NewTechnician.apply)

  final case class NewStaff(fullName: String, email: String, password: String, phone: Option[String], role: Role)
  given Reads[NewStaff] = (
    (__ \ "fullName").read(Validate.text(2, 120)) and
      (__ \ "email").read(Validate.email) and
      (__ \ "password").read(IdentityRequests.password) and
      (__ \ "phone").readNullable(Validate.text(0, 30)).map(_.filter(_.nonEmpty)) and
      (__ \ "role").read(Validate.oneOf(Array(Role.SERVICE_ADVISOR, Role.ADMIN, Role.WORKSHOP_CONTROLLER, Role.PARTS_STAFF)))
  )(NewStaff.apply)

  final case class Hours(
      openingTime: LocalTime,
      closingTime: LocalTime,
      openDays: List[Int],
      slotMinutes: Int,
      minLeadMinutes: Int,
      maxDaysAhead: Int
  )
  given Reads[Hours] = (
    (__ \ "openingTime").read(Validate.hhmm) and
      (__ \ "closingTime").read(Validate.hhmm) and
      (__ \ "openDays").read(weekdays) and
      (__ \ "slotMinutes").read(Reads.IntReads.filter(Validate.error("Must be 15, 30 or 60"))(Set(15, 30, 60))) and
      (__ \ "minLeadMinutes").read(Validate.int(0, 7 * 24 * 60)) and
      (__ \ "maxDaysAhead").read(Validate.int(1, 365))
  )(Hours.apply)

  final case class SetActive(isActive: Boolean)
  given Reads[SetActive] = (__ \ "isActive").read[Boolean].map(SetActive.apply)

final case class BayCreated(id: String, name: String, bayType: String, isActive: Boolean)
final case class WorkshopView(bays: Seq[BayRecord], technicians: Seq[TechnicianRecord])
final case class HoursUpdated(hours: WorkshopHours, upcomingOutsideHours: Int)

/**
 * Dealership administration: bays, technicians (skills, shifts) and staff accounts, always for the
 * caller's own dealership. The system manager may read the bays and technicians of any dealership.
 */
final class WorkshopAdminService(db: Db, workshop: WorkshopRepository, identity: UserAccounts):
  import AdminRequests.*

  def listBays(auth: AuthContext, dealershipId: Option[String] = None): Seq[BayRecord] =
    workshop.listBays(readable(auth, dealershipId))

  def addBay(auth: AuthContext, input: NewBay, log: Log): BayCreated =
    val id = workshop.addBay(auth.requireDealership, input.name, input.bayType)
    log.info("admin.bay_created", "bayId" -> id, "bayType" -> input.bayType)
    BayCreated(id, input.name, input.bayType, isActive = true)

  /** Deactivating keeps history intact; existing appointments stay valid, no new ones are offered. */
  def setBayActive(auth: AuthContext, bayId: String, isActive: Boolean, log: Log): Unit =
    if !workshop.setBayActive(auth.requireDealership, bayId, isActive) then throw AppError.notFound("Bay")
    log.info("admin.bay_updated", "bayId" -> bayId, "isActive" -> isActive)

  def listTechnicians(auth: AuthContext, dealershipId: Option[String] = None): Seq[TechnicianRecord] =
    workshop.listTechnicians(readable(auth, dealershipId))

  /** The dealership a list is read for: the admin's own; the system manager names any one. */
  private def readable(auth: AuthContext, dealershipId: Option[String]): String =
    if auth.role == Role.SYSTEM_MANAGER then
      dealershipId.getOrElse(throw AppError.badRequest("DEALERSHIP_REQUIRED", "Give a dealershipId"))
    else auth.requireDealership

  def addTechnician(auth: AuthContext, input: NewTechnician, log: Log): String =
    val dealershipId = auth.requireDealership
    if !input.shiftStart.isBefore(input.shiftEnd) then throw AppError.unprocessable("INVALID_SHIFT", "Shift must end after it starts")
    val id = db.tx { s =>
      val user = input.login.map { l =>
        identity.createUser(CreateUser(l.email, l.password, input.displayName, None, Role.TECHNICIAN, Some(dealershipId)), Some(s))
      }
      workshop.addTechnician(
        s,
        NewTechnicianRecord(dealershipId, user.map(_.id), input.displayName, input.shiftStart, input.shiftEnd, input.workDays, input.skills)
      )
    }
    log.info("admin.technician_created", "technicianId" -> id, "skills" -> input.skills, "withLogin" -> input.login.isDefined)
    id

  def setTechnicianActive(auth: AuthContext, technicianId: String, isActive: Boolean, log: Log): Unit =
    if !workshop.setTechnicianActive(auth.requireDealership, technicianId, isActive) then throw AppError.notFound("Technician")
    log.info("admin.technician_updated", "technicianId" -> technicianId, "isActive" -> isActive)

  /** Read-only view of the workshop for the day board (advisors need it too, not just admins). */
  def activeResources(auth: AuthContext): WorkshopView =
    val dealershipId = auth.requireDealership
    WorkshopView(workshop.listBays(dealershipId, onlyActive = true), workshop.listTechnicians(dealershipId, onlyActive = true))

  /** Opening hours and booking rules. New bookings follow them at once; existing ones are kept. */
  def updateHours(auth: AuthContext, input: Hours, log: Log): HoursUpdated =
    if !input.openingTime.isBefore(input.closingTime) then
      throw AppError.unprocessable("INVALID_HOURS", "Closing time must be after opening time")
    val hours = WorkshopHours(input.openingTime, input.closingTime, input.openDays.distinct.sorted, input.slotMinutes,
      input.minLeadMinutes, input.maxDaysAhead)
    val outside = workshop.updateHours(auth.requireDealership, hours)
    log.info("admin.hours_updated", "openingTime" -> hours.openingTime.toString, "closingTime" -> hours.closingTime.toString,
      "openDays" -> hours.openDays, "upcomingOutsideHours" -> outside)
    HoursUpdated(hours, outside)

  def listStaff(auth: AuthContext): Seq[StaffMember] = identity.listStaff(auth.requireDealership)

  def addStaff(auth: AuthContext, input: NewStaff, log: Log): PublicUser =
    val user = identity.createUser(CreateUser(input.email, input.password, input.fullName, input.phone, input.role, Some(auth.requireDealership)))
    log.info("admin.staff_created", "newUserId" -> user.id, "newRole" -> user.role)
    user
