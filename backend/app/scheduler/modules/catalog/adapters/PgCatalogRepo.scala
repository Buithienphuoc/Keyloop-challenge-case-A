package scheduler.modules.catalog.adapters

import java.time.ZoneId

import anorm.*

import scheduler.modules.catalog.*
import scheduler.modules.scheduling.domain.VehicleType
import scheduler.shared.{Db, Sql}
import scheduler.shared.Sql.*

/** PostgreSQL adapter for the CatalogReader port. */
final class PgCatalogRepo(db: Db) extends CatalogReader:

  private val dealership: RowParser[Dealership] = Sql.rows { r =>
    Dealership(
      id = r.uuid("id"),
      code = r.str("code"),
      name = r.str("name"),
      address = r.str("address"),
      phone = r.strOpt("phone"),
      timezone = ZoneId.of(r.str("timezone")),
      openingTime = r.time("opening_time"),
      closingTime = r.time("closing_time"),
      openDays = r.ints("open_days"),
      slotMinutes = r.int("slot_minutes"),
      minLeadMinutes = r.int("min_lead_minutes"),
      maxDaysAhead = r.int("max_days_ahead")
    )
  }

  private val servicePackage: RowParser[ServicePackage] = Sql.rows { r =>
    ServicePackage(
      id = r.uuid("id"),
      code = r.str("code"),
      name = r.str("name"),
      description = r.str("description"),
      vehicleType = VehicleType.valueOf(r.str("vehicle_type")),
      durationMinutes = r.int("duration_minutes"),
      requiredSkill = r.str("required_skill"),
      bayType = r.str("bay_type"),
      price = Money(r.int("price_amount"), r.str("currency")),
      checklist = r.strings("checklist"),
      isRecommended = r.bool("is_recommended")
    )
  }

  def listDealerships(): Seq[Dealership] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"SELECT * FROM dealerships ORDER BY name".as(dealership.*)
  }

  def getDealership(id: String): Option[Dealership] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"SELECT * FROM dealerships WHERE id = $id::uuid".as(dealership.singleOpt)
  }

  def listPackages(vehicleType: Option[VehicleType]): Seq[ServicePackage] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    val vt = vehicleType.map(_.toString)
    SQL"""SELECT * FROM service_packages
          WHERE is_active AND (CAST($vt AS text) IS NULL OR vehicle_type = CAST($vt AS text))
          ORDER BY vehicle_type, sort_order, name""".as(servicePackage.*)
  }

  def getPackage(id: String): Option[ServicePackage] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"SELECT * FROM service_packages WHERE id = $id::uuid AND is_active".as(servicePackage.singleOpt)
  }

  def listSkills(): Seq[SkillInfo] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"SELECT code, name FROM skills ORDER BY name".as(Sql.rows(r => SkillInfo(r.str("code"), r.str("name"))).*)
  }

  def hasSkill(code: String): Boolean = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"SELECT 1 FROM skills WHERE code = $code".as(SqlParser.scalar[Int].singleOpt).isDefined
  }

  def listBayTypes(): Seq[BayTypeInfo] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"SELECT code, name, vehicle_types FROM bay_types ORDER BY name"
      .as(Sql.rows(r => BayTypeInfo(r.str("code"), r.str("name"), r.strings("vehicle_types"))).*)
  }
