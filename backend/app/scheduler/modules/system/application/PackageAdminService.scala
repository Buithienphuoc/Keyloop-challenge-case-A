package scheduler.modules.system.application

import play.api.libs.functional.syntax.*
import play.api.libs.json.*

import scheduler.modules.scheduling.domain.{Compatibility, VehicleType}
import scheduler.modules.system.*
import scheduler.shared.{AppError, Log, Validate}

object PackageRequests:
  /** Jobs are scheduled on a 15-minute grid and fit inside one working day. */
  private val duration = Validate.int(15, 600).filter(Validate.error("Must be a multiple of 15 minutes"))(_ % 15 == 0)
  private val price = Validate.int(0, 1_000_000_000)
  private val checklist = Validate.listOf(Validate.text(1, 120), min = 1, max = 30)

  final case class NewPackage(
      code: String,
      name: String,
      description: String,
      vehicleType: VehicleType,
      durationMinutes: Int,
      requiredSkill: String,
      bayType: String,
      priceAmount: Int,
      checklist: List[String],
      isRecommended: Boolean
  )
  given Reads[NewPackage] = (
    (__ \ "code").read(Validate.matching("[A-Za-z0-9-]{2,30}".r, "2-30 letters, digits or dashes").map(_.toUpperCase)) and
      (__ \ "name").read(Validate.text(3, 80)) and
      (__ \ "description").read(Validate.text(3, 500)) and
      (__ \ "vehicleType").read(Validate.oneOf(VehicleType.values)) and
      (__ \ "durationMinutes").read(duration) and
      (__ \ "requiredSkill").read(Validate.text(1, 50)) and
      (__ \ "bayType").read(Validate.text(1, 50)) and
      (__ \ "priceAmount").read(price) and
      (__ \ "checklist").read(checklist) and
      (__ \ "isRecommended").readWithDefault(false)
  )(NewPackage.apply)

  given Reads[PackageChanges] = (
    (__ \ "name").readNullable(Validate.text(3, 80)) and
      (__ \ "description").readNullable(Validate.text(3, 500)) and
      (__ \ "durationMinutes").readNullable(duration) and
      (__ \ "priceAmount").readNullable(price) and
      (__ \ "checklist").readNullable(checklist) and
      (__ \ "isRecommended").readNullable[Boolean] and
      (__ \ "isActive").readNullable[Boolean]
  )(PackageChanges.apply)

/**
 * The platform's service package catalogue. A new package is bookable at every dealership
 * that has a matching bay and a technician with the skill; deactivating one hides it from
 * new bookings while existing appointments keep it.
 */
final class PackageAdminService(packages: PackageRepository):
  import PackageRequests.NewPackage

  def list(): Seq[PackageRecord] = packages.listAll()

  def create(input: NewPackage, log: Log): PackageRecord =
    if !Compatibility.bayFitsVehicle(input.bayType, input.vehicleType) then
      throw AppError.unprocessable("BAY_NOT_FOR_VEHICLE", s"A ${input.bayType} bay cannot take a ${input.vehicleType.toString.toLowerCase}")
    val id = packages.insert(NewPackageRecord(input.code, input.name, input.description, input.vehicleType.toString, input.durationMinutes,
      input.requiredSkill, input.bayType, input.priceAmount, input.checklist, input.isRecommended))
    log.info("system.package_created", "packageId" -> id, "code" -> input.code)
    packages.listAll().find(_.id == id).get

  def update(id: String, changes: PackageChanges, log: Log): PackageRecord =
    val updated = packages.update(id, changes).getOrElse(throw AppError.notFound("Service package"))
    log.info("system.package_updated", "packageId" -> id, "isActive" -> updated.isActive, "durationMinutes" -> updated.durationMinutes)
    updated
