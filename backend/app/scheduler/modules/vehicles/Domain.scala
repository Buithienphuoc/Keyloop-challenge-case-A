package scheduler.modules.vehicles

import java.time.Year

import play.api.libs.functional.syntax.*
import play.api.libs.json.*

import scheduler.modules.scheduling.domain.VehicleType
import scheduler.shared.Validate

final case class Vehicle(
    id: String,
    vehicleType: VehicleType,
    make: String,
    model: String,
    year: Option[Int],
    plate: String,
    vin: Option[String],
    mileageKm: Option[Int]
)

final case class NewVehicle(
    vehicleType: VehicleType,
    make: String,
    model: String,
    year: Option[Int],
    plate: String,
    vin: Option[String],
    mileageKm: Option[Int]
)

object NewVehicle:
  private val vin: Reads[Option[String]] = Reads.StringReads.map(_.trim.toUpperCase).flatMapResult {
    case ""                                        => JsSuccess(None) // an empty form field means "no VIN"
    case v if "[A-HJ-NPR-Z0-9]{17}".r.matches(v) => JsSuccess(Some(v))
    case _ => JsError(Validate.error("A VIN has 17 characters and never uses I, O or Q"))
  }

  /**
   * Model years from 1950 to next year (next year's models go on sale early). The limit is
   * worked out on every read, not once at start-up, so a server running past New Year accepts
   * the new year's models.
   */
  private[vehicles] def modelYear(thisYear: () => Year): Reads[Int] =
    Reads(js => Validate.int(1950, thisYear().getValue + 1).reads(js))

  given Reads[NewVehicle] = (
    (__ \ "vehicleType").read(Validate.oneOf(VehicleType.values)) and
      (__ \ "make").read(Validate.text(1, 60)) and
      (__ \ "model").read(Validate.text(1, 60)) and
      (__ \ "year").readNullable(modelYear(() => Year.now())) and
      (__ \ "plate").read(Validate.text(3, 20)) and
      (__ \ "vin").readNullable(vin).map(_.flatten) and
      (__ \ "mileageKm").readNullable(Validate.int(0, 2_000_000))
  )(NewVehicle.apply)

/** Port: vehicle persistence. */
trait VehicleRepository:
  def listByOwner(ownerId: String): Seq[Vehicle]

  /** The vehicle only if it belongs to this owner; None otherwise (never leaks other people's vehicles). */
  def findOwned(ownerId: String, vehicleId: String): Option[Vehicle]

  /** Throws AppError 409 PLATE_ALREADY_REGISTERED when the owner already has this plate. */
  def add(ownerId: String, vehicle: NewVehicle): Vehicle

/**
 * What other modules need from vehicles: "give me this customer's vehicle or fail with 404".
 * Booking and consultation depend on this narrow interface (ISP), not on the whole module.
 */
trait OwnedVehicles:
  def getOwned(ownerId: String, vehicleId: String): Vehicle
