package scheduler.modules.system.adapters

import java.sql.SQLException

import anorm.*

import scheduler.modules.system.*
import scheduler.shared.{AppError, Db, DbSession, PgErrors, Sql}
import scheduler.shared.Sql.{*, given}

final class PgTenantRepo(db: Db) extends TenantRepository:

  def listWithCapacity(): Seq[DealershipSummary] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"""
      SELECT d.id, d.code, d.name, d.address, d.timezone, d.opening_time, d.closing_time, d.open_days,
             (SELECT count(*)::int FROM service_bays b WHERE b.dealership_id = d.id AND b.is_active) AS active_bays,
             (SELECT count(*)::int FROM technicians t WHERE t.dealership_id = d.id AND t.is_active) AS active_technicians,
             (SELECT count(*)::int FROM users u WHERE u.dealership_id = d.id) AS staff_accounts,
             (SELECT count(*)::int FROM appointments a WHERE a.dealership_id = d.id AND a.status IN ('CONFIRMED', 'IN_PROGRESS')
                AND upper(a.period) > now()) AS upcoming_appointments
      FROM dealerships d ORDER BY d.name""".as(Sql.rows { r =>
      DealershipSummary(
        id = r.uuid("id"),
        code = r.str("code"),
        name = r.str("name"),
        address = r.str("address"),
        timezone = r.str("timezone"),
        openingTime = r.time("opening_time"),
        closingTime = r.time("closing_time"),
        openDays = r.ints("open_days"),
        activeBays = r.int("active_bays"),
        activeTechnicians = r.int("active_technicians"),
        staffAccounts = r.int("staff_accounts"),
        upcomingAppointments = r.int("upcoming_appointments")
      )
    }.*)
  }

  def insert(s: DbSession, d: NewDealershipRecord): String =
    given java.sql.Connection = Sql.connection(s)
    try
      SQL"""
        INSERT INTO dealerships (code, name, address, phone, timezone, opening_time, closing_time, open_days,
          slot_minutes, min_lead_minutes, max_days_ahead)
        VALUES (${d.code}, ${d.name}, ${d.address}, ${d.phone}, ${d.timezone.getId}, ${d.openingTime.toString}::time,
          ${d.closingTime.toString}::time, ${intArray(d.openDays)}, ${d.slotMinutes}, ${d.minLeadMinutes}, ${d.maxDaysAhead})
        RETURNING id""".as(SqlParser.scalar[java.util.UUID].single).toString
    catch
      case e: SQLException if PgErrors.sqlState(e).contains(PgErrors.UniqueViolation) =>
        throw AppError.conflict("DEALERSHIP_CODE_TAKEN", "This code is already used")
