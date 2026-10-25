package scheduler.modules.system

import java.time.{LocalTime, ZoneId}

import scheduler.shared.DbSession

final case class DealershipSummary(
    id: String,
    code: String,
    name: String,
    address: String,
    timezone: String,
    openingTime: LocalTime,
    closingTime: LocalTime,
    openDays: Seq[Int],
    activeBays: Int,
    activeTechnicians: Int,
    staffAccounts: Int,
    upcomingAppointments: Int
)

final case class NewDealershipRecord(
    code: String,
    name: String,
    address: String,
    phone: Option[String],
    timezone: ZoneId,
    openingTime: LocalTime,
    closingTime: LocalTime,
    openDays: Seq[Int],
    slotMinutes: Int,
    minLeadMinutes: Int,
    maxDaysAhead: Int
)

/** Port: dealerships as tenants of the platform. */
trait TenantRepository:
  def listWithCapacity(): Seq[DealershipSummary]

  /** Runs in the caller's transaction. 409 DEALERSHIP_CODE_TAKEN. */
  def insert(s: DbSession, d: NewDealershipRecord): String
