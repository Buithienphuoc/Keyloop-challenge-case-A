package scheduler.modules.scheduling.domain

/** Which kinds of vehicle each bay type can take. Mirrors the bay_types reference table. */
object Compatibility:
  val BayVehicleTypes: Map[String, Set[VehicleType]] = Map(
    "MOTORBIKE_STAND" -> Set(VehicleType.MOTORBIKE),
    "CAR_LIFT" -> Set(VehicleType.CAR),
    "DIAGNOSTIC_BAY" -> Set(VehicleType.MOTORBIKE, VehicleType.CAR)
  )

  def bayFitsVehicle(bayType: String, vehicleType: VehicleType): Boolean =
    BayVehicleTypes.get(bayType).exists(_.contains(vehicleType))
