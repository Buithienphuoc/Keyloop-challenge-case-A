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

/** A service package as the platform manages it (inactive ones included). */
final case class PackageRecord(
    id: String,
    code: String,
    name: String,
    description: String,
    vehicleType: String,
    durationMinutes: Int,
    requiredSkill: String,
    bayType: String,
    priceAmount: Int,
    currency: String,
    checklist: Seq[String],
    isRecommended: Boolean,
    isActive: Boolean
)

final case class NewPackageRecord(
    code: String,
    name: String,
    description: String,
    vehicleType: String,
    durationMinutes: Int,
    requiredSkill: String,
    bayType: String,
    priceAmount: Int,
    checklist: Seq[String],
    isRecommended: Boolean
)

/** Fields left as None keep their current value. */
final case class PackageChanges(
    name: Option[String],
    description: Option[String],
    durationMinutes: Option[Int],
    priceAmount: Option[Int],
    checklist: Option[Seq[String]],
    isRecommended: Option[Boolean],
    isActive: Option[Boolean]
)

/**
 * Port: the service package catalogue. It is shared by every dealership (the same
 * "Essential maintenance" everywhere), so the platform manages it, not a dealership admin.
 */
trait PackageRepository:
  def listAll(): Seq[PackageRecord]

  /** 409 PACKAGE_CODE_TAKEN; 422 UNKNOWN_SKILL or UNKNOWN_BAY_TYPE. */
  def insert(p: NewPackageRecord): String

  /** None when the package does not exist. */
  def update(id: String, changes: PackageChanges): Option[PackageRecord]
