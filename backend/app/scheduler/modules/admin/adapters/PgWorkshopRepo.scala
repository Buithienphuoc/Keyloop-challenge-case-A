package scheduler.modules.admin.adapters

import java.sql.SQLException

import anorm.*

import scheduler.modules.admin.*
import scheduler.shared.{AppError, Db, DbSession, PgErrors, Sql}
import scheduler.shared.Sql.{*, given}

final class PgWorkshopRepo(db: Db) extends WorkshopRepository:

  def listBays(dealershipId: String, onlyActive: Boolean): Seq[BayRecord] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"""SELECT b.id, b.name, b.bay_type, bt.name AS bay_type_name, b.is_active
          FROM service_bays b JOIN bay_types bt ON bt.code = b.bay_type
          WHERE b.dealership_id = $dealershipId::uuid AND (NOT $onlyActive OR b.is_active) ORDER BY b.name"""
      .as(Sql.rows(r => BayRecord(r.uuid("id"), r.str("name"), r.str("bay_type"), r.str("bay_type_name"), r.bool("is_active"))).*)
  }

  def addBay(dealershipId: String, name: String, bayType: String): String = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    try
      SQL"INSERT INTO service_bays (dealership_id, name, bay_type) VALUES ($dealershipId::uuid, $name, $bayType) RETURNING id"
        .as(SqlParser.scalar[java.util.UUID].single).toString
    catch
      case e: SQLException if PgErrors.sqlState(e).contains(PgErrors.UniqueViolation) =>
        throw AppError.conflict("BAY_NAME_TAKEN", "A bay with this name already exists")
      case e: SQLException if PgErrors.sqlState(e).contains(PgErrors.ForeignKeyViolation) =>
        throw AppError.unprocessable("UNKNOWN_BAY_TYPE", "Unknown bay type")
  }

  def setBayActive(dealershipId: String, bayId: String, isActive: Boolean): Boolean = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"UPDATE service_bays SET is_active = $isActive WHERE id = $bayId::uuid AND dealership_id = $dealershipId::uuid".executeUpdate() == 1
  }

  def listTechnicians(dealershipId: String, onlyActive: Boolean): Seq[TechnicianRecord] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"""SELECT t.id, t.display_name, t.shift_start, t.shift_end, t.work_days, t.is_active, u.email AS login_email,
                 array_remove(array_agg(ts.skill_code ORDER BY ts.skill_code), NULL) AS skills
          FROM technicians t
          LEFT JOIN technician_skills ts ON ts.technician_id = t.id
          LEFT JOIN users u ON u.id = t.user_id
          WHERE t.dealership_id = $dealershipId::uuid AND (NOT $onlyActive OR t.is_active)
          GROUP BY t.id, u.email ORDER BY t.display_name"""
      .as(Sql.rows { r =>
        TechnicianRecord(
          id = r.uuid("id"),
          displayName = r.str("display_name"),
          shiftStart = r.time("shift_start"),
          shiftEnd = r.time("shift_end"),
          workDays = r.ints("work_days"),
          isActive = r.bool("is_active"),
          loginEmail = r.strOpt("login_email"),
          skills = r.strings("skills")
        )
      }.*)
  }

  def addTechnician(s: DbSession, t: NewTechnicianRecord): String =
    given java.sql.Connection = Sql.connection(s)
    val id = SQL"""INSERT INTO technicians (dealership_id, user_id, display_name, shift_start, shift_end, work_days)
                   VALUES (${t.dealershipId}::uuid, ${t.userId}::uuid, ${t.displayName}, ${t.shiftStart.toString}::time,
                           ${t.shiftEnd.toString}::time, ${intArray(t.workDays)})
                   RETURNING id""".as(SqlParser.scalar[java.util.UUID].single).toString
    try
      t.skills.distinct.foreach { skill =>
        SQL"INSERT INTO technician_skills (technician_id, skill_code) VALUES ($id::uuid, $skill)".executeUpdate()
      }
    catch
      case e: SQLException if PgErrors.sqlState(e).contains(PgErrors.ForeignKeyViolation) =>
        throw AppError.unprocessable("UNKNOWN_SKILL", "Unknown skill")
    id

  def setTechnicianActive(dealershipId: String, technicianId: String, isActive: Boolean): Boolean = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"UPDATE technicians SET is_active = $isActive WHERE id = $technicianId::uuid AND dealership_id = $dealershipId::uuid".executeUpdate() == 1
  }
