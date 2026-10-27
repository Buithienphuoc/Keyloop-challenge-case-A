package scheduler.modules.vehicles.application

import scheduler.modules.vehicles.{NewVehicle, OwnedVehicle, OwnedVehicles, Vehicle, VehicleRepository}
import scheduler.shared.{AppError, Log}

final class VehicleService(vehicles: VehicleRepository) extends OwnedVehicles:

  def listMine(ownerId: String): Seq[Vehicle] = vehicles.listByOwner(ownerId)

  def listAll(): Seq[OwnedVehicle] = vehicles.listAll()

  def getOwned(ownerId: String, vehicleId: String): Vehicle =
    vehicles.findOwned(ownerId, vehicleId).getOrElse(throw AppError.notFound("Vehicle"))

  def add(ownerId: String, input: NewVehicle, log: Log): Vehicle =
    val vehicle = vehicles.add(ownerId, input)
    log.info("vehicle.added", "vehicleId" -> vehicle.id, "vehicleType" -> vehicle.vehicleType)
    vehicle
