package scheduler.modules.system.adapters

import java.sql.SQLException

import anorm.*

import scheduler.modules.system.*
import scheduler.shared.{AppError, Db, PgErrors, Sql}
import scheduler.shared.Sql.{*, given}

final class PgPackageRepo(db: Db) extends PackageRepository:

  private val record: RowParser[PackageRecord] = Sql.rows { r =>
    PackageRecord(
      id = r.uuid("id"),
      code = r.str("code"),
      name = r.str("name"),
      description = r.str("description"),
      vehicleType = r.str("vehicle_type"),
      durationMinutes = r.int("duration_minutes"),
      requiredSkill = r.str("required_skill"),
      bayType = r.str("bay_type"),
      priceAmount = r.int("price_amount"),
      currency = r.str("currency"),
      checklist = r.strings("checklist"),
      isRecommended = r.bool("is_recommended"),
      isActive = r.bool("is_active")
    )
  }

  def listAll(): Seq[PackageRecord] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"SELECT * FROM service_packages ORDER BY vehicle_type, sort_order, name".as(record.*)
  }

  def insert(p: NewPackageRecord): String = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    try
      SQL"""
        INSERT INTO service_packages (code, name, description, vehicle_type, duration_minutes, required_skill, bay_type,
          price_amount, checklist, is_recommended, sort_order)
        VALUES (${p.code}, ${p.name}, ${p.description}, ${p.vehicleType}, ${p.durationMinutes}, ${p.requiredSkill}, ${p.bayType},
          ${p.priceAmount}, ${textArray(p.checklist)}, ${p.isRecommended},
          (SELECT COALESCE(max(sort_order), 0) + 1 FROM service_packages))
        RETURNING id""".as(SqlParser.scalar[java.util.UUID].single).toString
    catch
      case e: SQLException if PgErrors.sqlState(e).contains(PgErrors.UniqueViolation) =>
        throw AppError.conflict("PACKAGE_CODE_TAKEN", "A package with this code already exists")
      case e: SQLException if PgErrors.constraint(e).exists(_.contains("required_skill")) =>
        throw AppError.unprocessable("UNKNOWN_SKILL", "Unknown skill")
      case e: SQLException if PgErrors.sqlState(e).contains(PgErrors.ForeignKeyViolation) =>
        throw AppError.unprocessable("UNKNOWN_BAY_TYPE", "Unknown bay type")
  }

  def update(id: String, c: PackageChanges): Option[PackageRecord] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"""
      UPDATE service_packages SET
        name = COALESCE(${c.name}, name),
        description = COALESCE(${c.description}, description),
        duration_minutes = COALESCE(${c.durationMinutes}, duration_minutes),
        price_amount = COALESCE(${c.priceAmount}, price_amount),
        checklist = CASE WHEN ${c.checklist.isDefined} THEN ${textArray(c.checklist.getOrElse(Nil))} ELSE checklist END,
        is_recommended = COALESCE(${c.isRecommended}, is_recommended),
        is_active = COALESCE(${c.isActive}, is_active)
      WHERE id = $id::uuid
      RETURNING *""".as(record.singleOpt)
  }
