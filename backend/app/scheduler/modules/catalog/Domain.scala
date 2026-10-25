package scheduler.modules.catalog

import java.time.{LocalTime, ZoneId}

import scheduler.modules.scheduling.domain.VehicleType

final case class InspectionArea(code: String, name: String)

/**
 * Areas a customer can ask the workshop to look at in a "check on request" or
 * "customisation" consultation. Kept as codes so the UI can translate them.
 */
val InspectionAreas: Seq[InspectionArea] = Seq(
  InspectionArea("ENGINE", "Engine & oil"),
  InspectionArea("BRAKES", "Brakes"),
  InspectionArea("ELECTRICAL", "Electrical & battery"),
  InspectionArea("SUSPENSION", "Suspension & steering"),
  InspectionArea("TYRES", "Tyres & wheels"),
  InspectionArea("DRIVETRAIN", "Chain / belt / gearbox"),
  InspectionArea("LIGHTS", "Lights & horn"),
  InspectionArea("COOLING", "Cooling system"),
  InspectionArea("BODY", "Body & paint"),
  InspectionArea("EXHAUST", "Exhaust"),
  InspectionArea("OTHER", "Something else")
)

final case class Dealership(
    id: String,
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

final case class Money(amount: Int, currency: String)

final case class ServicePackage(
    id: String,
    code: String,
    name: String,
    description: String,
    vehicleType: VehicleType,
    durationMinutes: Int,
    requiredSkill: String,
    bayType: String,
    price: Money,
    checklist: Seq[String],
    isRecommended: Boolean
)

final case class SkillInfo(code: String, name: String)

final case class BayTypeInfo(code: String, name: String, vehicleTypes: Seq[String])

/**
 * Port: read access to the catalogue (dealerships, packages, reference data).
 * Scheduling and consultation depend on this interface, never on the SQL behind it.
 */
trait CatalogReader:
  def listDealerships(): Seq[Dealership]
  def getDealership(id: String): Option[Dealership]
  def listPackages(vehicleType: Option[VehicleType]): Seq[ServicePackage]
  def getPackage(id: String): Option[ServicePackage]
  def listSkills(): Seq[SkillInfo]
  def hasSkill(code: String): Boolean
  def listBayTypes(): Seq[BayTypeInfo]
