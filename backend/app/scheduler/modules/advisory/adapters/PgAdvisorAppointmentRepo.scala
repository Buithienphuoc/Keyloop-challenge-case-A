package scheduler.modules.advisory.adapters

import java.sql.SQLException
import java.time.ZoneId

import anorm.*
import anorm.SqlParser.scalar

import scheduler.modules.advisory.*
import scheduler.shared.{DbSession, PgErrors, Sql}
import scheduler.shared.Sql.*

/** PostgreSQL adapter for advisor appointments. */
final class PgAdvisorAppointmentRepo extends AdvisorAppointmentRepository:

  private val ViewSelect = """
    SELECT a.id, a.reference, a.status, a.starts_at, a.message, a.cancel_reason, a.created_at,
           d.id AS d_id, d.name AS d_name, d.address AS d_address, d.timezone AS d_tz,
           c.id AS c_id, c.full_name AS c_name, c.phone AS c_phone, c.email AS c_email,
           adv.id AS adv_id, adv.full_name AS adv_name
    FROM advisor_appointments a
    JOIN dealerships d ON d.id = a.dealership_id
    JOIN users c ON c.id = a.customer_id
    LEFT JOIN users adv ON adv.id = a.advisor_id"""

  private val view: RowParser[AdvisorAppointmentView] = Sql.rows { r =>
    val zone = ZoneId.of(r.str("d_tz"))
    AdvisorAppointmentView(
      id = r.uuid("id"),
      reference = r.str("reference"),
      status = AdvisorAppointmentStatus.valueOf(r.str("status")),
      start = r.instant("starts_at").atZone(zone),
      dealership = AdvisorAppointmentView.DealershipRef(r.uuid("d_id"), r.str("d_name"), r.str("d_address"), zone.getId),
      customer = AdvisorAppointmentView.CustomerRef(r.uuid("c_id"), r.str("c_name"), r.strOpt("c_phone"), r.str("c_email")),
      message = r.str("message"),
      advisor = r.uuidOpt("adv_id").map(AdvisorAppointmentView.Ref(_, r.str("adv_name"))),
      cancelReason = r.strOpt("cancel_reason"),
      createdAt = r.instant("created_at")
    )
  }

  def insert(s: DbSession, a: NewAdvisorAppointment): String =
    given java.sql.Connection = Sql.connection(s)
    SQL"""
      INSERT INTO advisor_appointments (reference, dealership_id, customer_id, starts_at, message, status, created_by)
      VALUES (${a.reference}, ${a.dealershipId}::uuid, ${a.customerId}::uuid, ${ts(a.start)}, ${a.message}, 'SCHEDULED',
              ${a.createdBy}::uuid)
      RETURNING id""".as(scalar[java.util.UUID].single).toString

  def get(s: DbSession, id: String): Option[AdvisorAppointmentView] =
    given java.sql.Connection = Sql.connection(s)
    SQL(s"$ViewSelect WHERE a.id = {id}::uuid").on("id" -> id).as(view.singleOpt)

  def list(s: DbSession, f: AdvisorAppointmentFilter): Seq[AdvisorAppointmentView] =
    given java.sql.Connection = Sql.connection(s)
    SQL(s"""$ViewSelect
      WHERE (CAST({dealershipId} AS uuid) IS NULL OR a.dealership_id = CAST({dealershipId} AS uuid))
        AND (CAST({customerId} AS uuid) IS NULL OR a.customer_id = CAST({customerId} AS uuid))
        AND (CAST({from} AS timestamptz) IS NULL OR a.starts_at >= CAST({from} AS timestamptz))
        AND (CAST({to} AS timestamptz) IS NULL OR a.starts_at < CAST({to} AS timestamptz))
        AND (NOT {openOnly} OR (a.status = 'SCHEDULED' AND a.advisor_id IS NULL))
      ORDER BY a.starts_at
      LIMIT 1000""")
      .on(
        "dealershipId" -> f.dealershipId,
        "customerId" -> f.customerId,
        "from" -> f.from.map(ts),
        "to" -> f.to.map(ts),
        "openOnly" -> f.openOnly
      )
      .as(view.*)

  def lockForUpdate(s: DbSession, id: String): Option[LockedAdvisorAppointment] =
    given java.sql.Connection = Sql.connection(s)
    SQL"""SELECT id, status, dealership_id, customer_id, advisor_id, starts_at
          FROM advisor_appointments WHERE id = $id::uuid FOR UPDATE""".as(Sql.rows { r =>
      LockedAdvisorAppointment(
        id = r.uuid("id"),
        status = AdvisorAppointmentStatus.valueOf(r.str("status")),
        dealershipId = r.uuid("dealership_id"),
        customerId = r.uuid("customer_id"),
        advisorId = r.uuidOpt("advisor_id"),
        start = r.instant("starts_at")
      )
    }.singleOpt)

  def setAdvisor(s: DbSession, id: String, advisorId: String): Unit =
    given java.sql.Connection = Sql.connection(s)
    try
      SQL"UPDATE advisor_appointments SET advisor_id = $advisorId::uuid, updated_at = now() WHERE id = $id::uuid".executeUpdate()
      ()
    catch
      case e: SQLException if PgErrors.constraint(e).contains("advisor_appointments_advisor_slot") => throw AdvisorBusyError()

  def setStatus(s: DbSession, id: String, status: AdvisorAppointmentStatus, cancelReason: Option[String]): Unit =
    given java.sql.Connection = Sql.connection(s)
    SQL"""UPDATE advisor_appointments SET status = ${status.toString}, cancel_reason = COALESCE($cancelReason, cancel_reason),
            updated_at = now() WHERE id = $id::uuid""".executeUpdate()
    ()
