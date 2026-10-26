package scheduler.modules.admin

import java.time.LocalTime

import scheduler.shared.DbSession

final case class BayRecord(id: String, name: String, bayType: String, bayTypeName: String, isActive: Boolean)

final case class TechnicianRecord(
    id: String,
    displayName: String,
    shiftStart: LocalTime,
    shiftEnd: LocalTime,
    workDays: Seq[Int],
    isActive: Boolean,
    loginEmail: Option[String],
    skills: Seq[String]
)

final case class NewTechnicianRecord(
    dealershipId: String,
    userId: Option[String],
    displayName: String,
    shiftStart: LocalTime,
    shiftEnd: LocalTime,
    workDays: Seq[Int],
    skills: Seq[String]
)

/** Opening hours and booking rules of one dealership. */
final case class WorkshopHours(
    openingTime: LocalTime,
    closingTime: LocalTime,
    openDays: Seq[Int],
    slotMinutes: Int,
    minLeadMinutes: Int,
    maxDaysAhead: Int
)

/**
 * Port: the workshop resources an admin manages. Every method is scoped to one dealership,
 * so an admin can never touch another dealership's bays or technicians.
 */
trait WorkshopRepository:
  def listBays(dealershipId: String, onlyActive: Boolean = false): Seq[BayRecord]

  /** 409 BAY_NAME_TAKEN, 422 UNKNOWN_BAY_TYPE. */
  def addBay(dealershipId: String, name: String, bayType: String): String

  /** False when the bay does not exist in this dealership. */
  def setBayActive(dealershipId: String, bayId: String, isActive: Boolean): Boolean
  def listTechnicians(dealershipId: String, onlyActive: Boolean = false): Seq[TechnicianRecord]

  /** Runs in the caller's transaction (it may also create a login). 422 UNKNOWN_SKILL. */
  def addTechnician(s: DbSession, t: NewTechnicianRecord): String
  def setTechnicianActive(dealershipId: String, technicianId: String, isActive: Boolean): Boolean

  /**
   * Saves new opening hours and booking rules. Returns how many upcoming bookings now fall
   * outside them: they stay valid, but the dealership should call those customers.
   */
  def updateHours(dealershipId: String, hours: WorkshopHours): Int
