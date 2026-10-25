package scheduler.modules.scheduling.adapters

import java.sql.SQLException
import java.time.{Instant, ZoneId}

import anorm.*
import anorm.SqlParser.scalar

import scheduler.modules.catalog.Money
import scheduler.modules.scheduling.application.*
import scheduler.modules.scheduling.domain.{AppointmentStatus, Bay, BusyPeriod, Technician, VehicleType}
import scheduler.shared.{DbSession, PgErrors, Sql}
import scheduler.shared.Sql.*

/** PostgreSQL adapter for every scheduling port. */
final class PgSchedulingStore extends SchedulingStore:

  private val ViewSelect = """
    SELECT a.id, a.reference, a.status, lower(a.period) AS start_at, upper(a.period) AS end_at,
           a.customer_note, a.cancel_reason, a.created_at,
           d.id AS d_id, d.name AS d_name, d.address AS d_address, d.phone AS d_phone, d.timezone AS d_tz,
           v.id AS v_id, v.vehicle_type, v.make, v.model, v.plate,
           u.id AS u_id, u.full_name AS u_name, u.phone AS u_phone, u.email AS u_email,
           a.service_package_id, a.consultation_request_id,
           sp.name AS sp_name, sp.checklist AS sp_checklist, sp.price_amount AS sp_price, sp.currency AS sp_currency,
           cr.kind AS cr_kind, cr.areas AS cr_areas, cr.estimated_price AS cr_price,
           b.id AS b_id, b.name AS b_name, t.id AS t_id, t.display_name AS t_name
    FROM appointments a
    JOIN dealerships d ON d.id = a.dealership_id
    JOIN vehicles v ON v.id = a.vehicle_id
    JOIN users u ON u.id = a.customer_id
    JOIN service_bays b ON b.id = a.bay_id
    JOIN technicians t ON t.id = a.technician_id
    LEFT JOIN service_packages sp ON sp.id = a.service_package_id
    LEFT JOIN consultation_requests cr ON cr.id = a.consultation_request_id"""

  private val view: RowParser[AppointmentView] = Sql.rows { r =>
    val zone = ZoneId.of(r.str("d_tz"))
    val packageId = r.uuidOpt("service_package_id")
    val kind = if packageId.isDefined then "PACKAGE" else r.str("cr_kind")
    AppointmentView(
      id = r.uuid("id"),
      reference = r.str("reference"),
      status = AppointmentStatus.valueOf(r.str("status")),
      start = r.instant("start_at").atZone(zone),
      end = r.instant("end_at").atZone(zone),
      dealership = AppointmentView.DealershipRef(r.uuid("d_id"), r.str("d_name"), r.str("d_address"), r.strOpt("d_phone"), zone.getId),
      vehicle = AppointmentView.VehicleRef(r.uuid("v_id"), VehicleType.valueOf(r.str("vehicle_type")), r.str("make"), r.str("model"), r.str("plate")),
      customer = AppointmentView.CustomerRef(r.uuid("u_id"), r.str("u_name"), r.strOpt("u_phone"), r.str("u_email")),
      service = AppointmentView.ServiceInfo(
        kind = kind,
        name =
          if packageId.isDefined then r.str("sp_name")
          else if kind == "MODIFICATION" then "Customisation (consulted)"
          else "Inspection on request",
        packageId = packageId,
        consultationRequestId = r.uuidOpt("consultation_request_id"),
        checklist = if packageId.isDefined then r.strings("sp_checklist") else r.strings("cr_areas")
      ),
      price =
        if packageId.isDefined then Some(Money(r.int("sp_price"), r.str("sp_currency")))
        else r.intOpt("cr_price").map(Money(_, "VND")),
      bay = AppointmentView.Ref(r.uuid("b_id"), r.str("b_name")),
      technician = AppointmentView.Ref(r.uuid("t_id"), r.str("t_name")),
      customerNote = r.strOpt("customer_note"),
      cancelReason = r.strOpt("cancel_reason"),
      createdAt = r.instant("created_at")
    )
  }

  def activeResources(s: DbSession, dealershipId: String): WorkshopResources =
    given java.sql.Connection = Sql.connection(s)
    val bays = SQL"SELECT id, name, bay_type FROM service_bays WHERE dealership_id = $dealershipId::uuid AND is_active"
      .as(Sql.rows(r => Bay(r.uuid("id"), r.str("name"), r.str("bay_type"))).*)
    val technicians = SQL"""
      SELECT t.id, t.display_name, t.shift_start, t.shift_end, t.work_days,
             array_remove(array_agg(ts.skill_code ORDER BY ts.skill_code), NULL) AS skills
      FROM technicians t LEFT JOIN technician_skills ts ON ts.technician_id = t.id
      WHERE t.dealership_id = $dealershipId::uuid AND t.is_active
      GROUP BY t.id""".as(Sql.rows { r =>
      Technician(r.uuid("id"), r.str("display_name"), r.strings("skills"), r.time("shift_start"), r.time("shift_end"), r.ints("work_days").toSet)
    }.*)
    WorkshopResources(bays, technicians)

  def busyPeriods(s: DbSession, dealershipId: String, from: Instant, to: Instant): Seq[BusyPeriod] =
    given java.sql.Connection = Sql.connection(s)
    SQL"""
      SELECT bay_id, technician_id, lower(period) AS s, upper(period) AS e
      FROM appointments
      WHERE dealership_id = $dealershipId::uuid AND status IN ('CONFIRMED', 'IN_PROGRESS')
        AND period && tstzrange(${ts(from)}, ${ts(to)}, '[)')"""
      .as(Sql.rows(r => BusyPeriod(r.uuid("bay_id"), r.uuid("technician_id"), r.instant("s").toEpochMilli, r.instant("e").toEpochMilli)).*)

  def insert(s: DbSession, a: NewAppointment): String =
    given java.sql.Connection = Sql.connection(s)
    try
      SQL"""
        INSERT INTO appointments (reference, dealership_id, customer_id, vehicle_id, service_package_id,
          consultation_request_id, bay_id, technician_id, period, status, customer_note, idempotency_key, created_by)
        VALUES (${a.reference}, ${a.dealershipId}::uuid, ${a.customerId}::uuid, ${a.vehicleId}::uuid, ${a.servicePackageId}::uuid,
          ${a.consultationRequestId}::uuid, ${a.bayId}::uuid, ${a.technicianId}::uuid, tstzrange(${ts(a.start)}, ${ts(a.end)}, '[)'),
          'CONFIRMED', ${a.customerNote}, ${a.idempotencyKey}, ${a.createdBy}::uuid)
        RETURNING id""".as(scalar[java.util.UUID].single).toString
    catch
      // Translate database outcomes into the port's errors, so use cases never see SQL codes.
      case e: SQLException if PgErrors.constraint(e).contains("appointments_no_vehicle_overlap") => throw VehicleBusyError()
      case e: SQLException if PgErrors.sqlState(e).contains(PgErrors.ExclusionViolation)         => throw SlotTakenError()
      // A generated reference collided (astronomically rare): a retry draws a new one.
      case e: SQLException if PgErrors.constraint(e).contains("appointments_reference_key") => throw SlotTakenError()

  def findIdByIdempotencyKey(s: DbSession, customerId: String, key: String): Option[String] =
    given java.sql.Connection = Sql.connection(s)
    SQL"SELECT id FROM appointments WHERE customer_id = $customerId::uuid AND idempotency_key = $key"
      .as(scalar[java.util.UUID].singleOpt).map(_.toString)

  def lockIdempotencyKey(s: DbSession, customerId: String, key: String): Unit =
    given java.sql.Connection = Sql.connection(s)
    // Transaction-scoped advisory lock: released automatically on commit or rollback.
    val lockName = s"booking:$customerId:$key"
    SQL"SELECT pg_advisory_xact_lock(hashtextextended($lockName, 0))".execute()
    ()

  def get(s: DbSession, id: String): Option[AppointmentView] =
    given java.sql.Connection = Sql.connection(s)
    SQL(s"$ViewSelect WHERE a.id = {id}::uuid").on("id" -> id).as(view.singleOpt)

  def list(s: DbSession, f: AppointmentFilter): Seq[AppointmentView] =
    given java.sql.Connection = Sql.connection(s)
    SQL(s"""$ViewSelect
      WHERE (CAST({customerId} AS uuid) IS NULL OR a.customer_id = CAST({customerId} AS uuid))
        AND (CAST({dealershipId} AS uuid) IS NULL OR a.dealership_id = CAST({dealershipId} AS uuid))
        AND (CAST({technicianId} AS uuid) IS NULL OR a.technician_id = CAST({technicianId} AS uuid))
        AND (CAST({from} AS timestamptz) IS NULL OR upper(a.period) > CAST({from} AS timestamptz))
        AND (CAST({to} AS timestamptz) IS NULL OR lower(a.period) < CAST({to} AS timestamptz))
      ORDER BY lower(a.period)""")
      .on(
        "customerId" -> f.customerId,
        "dealershipId" -> f.dealershipId,
        "technicianId" -> f.technicianId,
        "from" -> f.from.map(ts),
        "to" -> f.to.map(ts)
      )
      .as(view.*)

  def lockForUpdate(s: DbSession, id: String): Option[LockedAppointment] =
    given java.sql.Connection = Sql.connection(s)
    SQL"""
      SELECT id, status, lower(period) AS s, dealership_id, customer_id, technician_id, consultation_request_id
      FROM appointments WHERE id = $id::uuid FOR UPDATE""".as(Sql.rows { r =>
      LockedAppointment(
        id = r.uuid("id"),
        status = AppointmentStatus.valueOf(r.str("status")),
        start = r.instant("s"),
        dealershipId = r.uuid("dealership_id"),
        customerId = r.uuid("customer_id"),
        technicianId = r.uuid("technician_id"),
        consultationRequestId = r.uuidOpt("consultation_request_id")
      )
    }.singleOpt)

  def setStatus(s: DbSession, id: String, status: AppointmentStatus, cancelReason: Option[String]): Unit =
    given java.sql.Connection = Sql.connection(s)
    SQL"""UPDATE appointments SET status = ${status.toString}, cancel_reason = COALESCE(${cancelReason}, cancel_reason), updated_at = now()
          WHERE id = $id::uuid""".executeUpdate()
    ()

  def recordEvent(s: DbSession, e: AppointmentEvent): Unit =
    given java.sql.Connection = Sql.connection(s)
    SQL"""INSERT INTO appointment_events (appointment_id, from_status, to_status, actor_id, note)
          VALUES (${e.appointmentId}::uuid, ${e.from.map(_.toString)}, ${e.to.toString}, ${e.actorId}::uuid, ${e.note})""".executeUpdate()
    ()

  def technicianIdForUser(s: DbSession, userId: String): Option[String] =
    given java.sql.Connection = Sql.connection(s)
    SQL"SELECT id FROM technicians WHERE user_id = $userId::uuid".as(scalar[java.util.UUID].singleOpt).map(_.toString)
