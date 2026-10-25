package scheduler.modules.vehicles.adapters

import java.sql.SQLException

import anorm.*

import scheduler.modules.scheduling.domain.VehicleType
import scheduler.modules.vehicles.{NewVehicle, Vehicle, VehicleRepository}
import scheduler.shared.{AppError, Db, PgErrors, Sql}
import scheduler.shared.Sql.*

final class PgVehicleRepo(db: Db) extends VehicleRepository:

  private val vehicle: RowParser[Vehicle] = Sql.rows { r =>
    Vehicle(
      id = r.uuid("id"),
      vehicleType = VehicleType.valueOf(r.str("vehicle_type")),
      make = r.str("make"),
      model = r.str("model"),
      year = r.intOpt("year"),
      plate = r.str("plate"),
      vin = r.strOpt("vin"),
      mileageKm = r.intOpt("mileage_km")
    )
  }

  def listByOwner(ownerId: String): Seq[Vehicle] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"SELECT * FROM vehicles WHERE owner_id = $ownerId::uuid ORDER BY created_at".as(vehicle.*)
  }

  def findOwned(ownerId: String, vehicleId: String): Option[Vehicle] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"SELECT * FROM vehicles WHERE id = $vehicleId::uuid AND owner_id = $ownerId::uuid".as(vehicle.singleOpt)
  }

  def add(ownerId: String, v: NewVehicle): Vehicle = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    try
      SQL"""INSERT INTO vehicles (owner_id, vehicle_type, make, model, year, plate, vin, mileage_km)
            VALUES ($ownerId::uuid, ${v.vehicleType.toString}, ${v.make}, ${v.model}, ${v.year}, ${v.plate}, ${v.vin}, ${v.mileageKm})
            RETURNING *""".as(vehicle.single)
    catch
      case e: SQLException if PgErrors.sqlState(e).contains(PgErrors.UniqueViolation) =>
        throw AppError.conflict("PLATE_ALREADY_REGISTERED", "You have already added a vehicle with this plate")
  }
