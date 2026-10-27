package scheduler.modules.scheduling.adapters

import java.sql.SQLException
import java.time.{Instant, ZoneId}

import anorm.*
import anorm.SqlParser.scalar

import scheduler.modules.catalog.Money
import scheduler.modules.scheduling.application.*
import scheduler.modules.identity.Role
import scheduler.modules.scheduling.domain.{AppointmentStatus, Bay, BusyPeriod, Requirement, Technician, TechnicianAssignment, VehicleType}
import scheduler.shared.{DbSession, PgErrors, Sql}
import scheduler.shared.Sql.*

/** PostgreSQL adapter for every scheduling port. */
final class PgSchedulingStore extends SchedulingStore:

  private val ViewSelect = """
    SELECT a.id, a.reference, a.status, lower(a.period) AS start_at, upper(a.period) AS end_at,
           a.customer_note, a.cancel_reason, a.created_at, a.hold_expires_at, a.technician_assigned_at,
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
      createdAt = r.instant("created_at"),
      holdExpiresAt = if r.str("status") == "HELD" then r.instantOpt("hold_expires_at") else None,
      technicianAssigned = r.instantOpt("technician_assigned_at").isDefined
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

  def busyPeriods(s: DbSession, dealershipId: String, from: Instant, to: Instant, asOf: Instant): Seq[BusyPeriod] =
    given java.sql.Connection = Sql.connection(s)
    // A hold that has run out no longer counts, even before the sweeper has marked it EXPIRED.
    SQL"""
      SELECT bay_id, technician_id, lower(period) AS s, upper(period) AS e
      FROM appointments
      WHERE dealership_id = $dealershipId::uuid
        AND (status IN ('CONFIRMED', 'IN_PROGRESS') OR (status = 'HELD' AND hold_expires_at > ${ts(asOf)}))
        AND period && tstzrange(${ts(from)}, ${ts(to)}, '[)')"""
      .as(Sql.rows(r => BusyPeriod(r.uuid("bay_id"), r.uuid("technician_id"), r.instant("s").toEpochMilli, r.instant("e").toEpochMilli)).*)

  def insert(s: DbSession, a: NewAppointment): String =
    given java.sql.Connection = Sql.connection(s)
    try
      SQL"""
        INSERT INTO appointments (reference, dealership_id, customer_id, vehicle_id, service_package_id,
          consultation_request_id, bay_id, technician_id, period, status, customer_note, idempotency_key, created_by,
          hold_expires_at, technician_assigned_at, technician_assigned_by)
        VALUES (${a.reference}, ${a.dealershipId}::uuid, ${a.customerId}::uuid, ${a.vehicleId}::uuid, ${a.servicePackageId}::uuid,
          ${a.consultationRequestId}::uuid, ${a.bayId}::uuid, ${a.technicianId}::uuid, tstzrange(${ts(a.start)}, ${ts(a.end)}, '[)'),
          ${a.status.toString}, ${a.customerNote}, ${a.idempotencyKey}, ${a.createdBy}::uuid, ${a.holdExpiresAt.map(ts)},
          CASE WHEN ${a.technicianAssigned} THEN now() END, CASE WHEN ${a.technicianAssigned} THEN ${a.createdBy}::uuid END)
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

  def lockVehicle(s: DbSession, vehicleId: String): Unit =
    given java.sql.Connection = Sql.connection(s)
    val lockName = s"booking-vehicle:$vehicleId"
    SQL"SELECT pg_advisory_xact_lock(hashtextextended($lockName, 0))".execute()
    ()

  def liveAppointmentOf(s: DbSession, vehicleId: String, asOf: Instant): Option[LiveAppointment] =
    given java.sql.Connection = Sql.connection(s)
    SQL"""
      SELECT reference, status, lower(period) AS s FROM appointments
      WHERE vehicle_id = $vehicleId::uuid
        AND (status IN ('CONFIRMED', 'IN_PROGRESS') OR (status = 'HELD' AND hold_expires_at > ${ts(asOf)}))
      ORDER BY lower(period) LIMIT 1""".as(Sql.rows { r =>
      LiveAppointment(r.str("reference"), AppointmentStatus.valueOf(r.str("status")), r.instant("s"))
    }.singleOpt)

  def get(s: DbSession, id: String): Option[AppointmentView] =
    given java.sql.Connection = Sql.connection(s)
    SQL(s"$ViewSelect WHERE a.id = {id}::uuid").on("id" -> id).as(view.singleOpt)

  def list(s: DbSession, f: AppointmentFilter): Seq[AppointmentView] =
    given java.sql.Connection = Sql.connection(s)
    SQL(s"""$ViewSelect
      WHERE a.status <> 'EXPIRED'
        AND (CAST({customerId} AS uuid) IS NULL OR a.customer_id = CAST({customerId} AS uuid))
        AND (CAST({dealershipId} AS uuid) IS NULL OR a.dealership_id = CAST({dealershipId} AS uuid))
        AND (CAST({technicianId} AS uuid) IS NULL OR a.technician_id = CAST({technicianId} AS uuid))
        AND (CAST({from} AS timestamptz) IS NULL OR upper(a.period) > CAST({from} AS timestamptz))
        AND (CAST({to} AS timestamptz) IS NULL OR lower(a.period) < CAST({to} AS timestamptz))
        AND ({anyStatus} OR a.status = ANY({statuses}))
        AND (NOT {openOnly} OR a.technician_assigned_at IS NULL)
      ORDER BY lower(a.period) ${if f.newestFirst then "DESC" else "ASC"}
      LIMIT {limit}""")
      .on(
        "customerId" -> f.customerId,
        "dealershipId" -> f.dealershipId,
        "technicianId" -> f.technicianId,
        "from" -> f.from.map(ts),
        "to" -> f.to.map(ts),
        "anyStatus" -> f.statuses.isEmpty,
        "statuses" -> textArray(f.statuses.toSeq.map(_.toString)),
        "openOnly" -> f.openOnly,
        "limit" -> f.limit
      )
      .as(view.*)

  def lockForUpdate(s: DbSession, id: String): Option[LockedAppointment] =
    given java.sql.Connection = Sql.connection(s)
    SQL"""
      SELECT id, status, lower(period) AS s, dealership_id, customer_id, technician_id, consultation_request_id, hold_expires_at
      FROM appointments WHERE id = $id::uuid FOR UPDATE""".as(Sql.rows { r =>
      LockedAppointment(
        id = r.uuid("id"),
        status = AppointmentStatus.valueOf(r.str("status")),
        start = r.instant("s"),
        dealershipId = r.uuid("dealership_id"),
        customerId = r.uuid("customer_id"),
        technicianId = r.uuid("technician_id"),
        consultationRequestId = r.uuidOpt("consultation_request_id"),
        holdExpiresAt = r.instantOpt("hold_expires_at")
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

  def technicianAccount(s: DbSession, technicianId: String): Option[TechnicianAssignment.Account] =
    given java.sql.Connection = Sql.connection(s)
    SQL"SELECT u.id, u.role FROM technicians t JOIN users u ON u.id = t.user_id WHERE t.id = $technicianId::uuid"
      .as(Sql.rows(r => TechnicianAssignment.Account(r.uuid("id"), Role.valueOf(r.str("role")))).singleOpt)

  def jobDetails(s: DbSession, id: String): Option[JobDetails] =
    given java.sql.Connection = Sql.connection(s)
    SQL"""
      SELECT a.id, a.dealership_id, a.status, lower(a.period) AS s, upper(a.period) AS e, a.bay_id, a.technician_id,
             a.technician_assigned_at,
             COALESCE(sp.required_skill, cr.required_skill) AS skill, COALESCE(sp.bay_type, cr.bay_type) AS bay_type
      FROM appointments a
      LEFT JOIN service_packages sp ON sp.id = a.service_package_id
      LEFT JOIN consultation_requests cr ON cr.id = a.consultation_request_id
      WHERE a.id = $id::uuid""".as(Sql.rows { r =>
      val (start, end) = (r.instant("s"), r.instant("e"))
      JobDetails(
        id = r.uuid("id"),
        dealershipId = r.uuid("dealership_id"),
        status = AppointmentStatus.valueOf(r.str("status")),
        start = start,
        end = end,
        bayId = r.uuid("bay_id"),
        technicianId = r.uuid("technician_id"),
        requirement = Requirement(((end.toEpochMilli - start.toEpochMilli) / 60_000).toInt, r.str("skill"), r.str("bay_type")),
        technicianAssigned = r.instantOpt("technician_assigned_at").isDefined
      )
    }.singleOpt)

  def expireHolds(s: DbSession, asOf: Instant, scope: HoldScope): Seq[ExpiredHold] =
    given java.sql.Connection = Sql.connection(s)
    val (dealershipId, vehicleId, from, to) = scope match
      case HoldScope.Everywhere             => (None, None, None, None)
      case HoldScope.Around(d, v, from, to) => (Some(d), v, Some(ts(from)), Some(ts(to)))
    // One statement: release the holds and write their audit events. Rows another
    // transaction is expiring at the same moment are skipped, not waited for.
    SQL("""
      WITH due AS (
        SELECT id FROM appointments
        WHERE status = 'HELD' AND hold_expires_at <= {asOf}
          AND (CAST({dealershipId} AS uuid) IS NULL
               OR (dealership_id = CAST({dealershipId} AS uuid)
                   AND period && tstzrange(CAST({from} AS timestamptz), CAST({to} AS timestamptz), '[)'))
               OR vehicle_id = CAST({vehicleId} AS uuid))
        FOR UPDATE SKIP LOCKED
      ), expired AS (
        UPDATE appointments a SET status = 'EXPIRED', updated_at = now()
        FROM due WHERE a.id = due.id
        RETURNING a.id, a.customer_id, a.consultation_request_id
      ), audit AS (
        INSERT INTO appointment_events (appointment_id, from_status, to_status, actor_id, note)
        SELECT id, 'HELD', 'EXPIRED', customer_id, 'Hold expired' FROM expired
      )
      SELECT id, consultation_request_id FROM expired""")
      .on("asOf" -> ts(asOf), "dealershipId" -> dealershipId, "vehicleId" -> vehicleId, "from" -> from, "to" -> to)
      .as(Sql.rows(r => ExpiredHold(r.uuid("id"), r.uuidOpt("consultation_request_id"))).*)

  def reassign(s: DbSession, id: String, bayId: String, technicianId: String): Unit =
    given java.sql.Connection = Sql.connection(s)
    try
      SQL"""UPDATE appointments SET bay_id = $bayId::uuid, technician_id = $technicianId::uuid, updated_at = now()
            WHERE id = $id::uuid""".executeUpdate()
      ()
    catch case e: SQLException if PgErrors.sqlState(e).contains(PgErrors.ExclusionViolation) => throw SlotTakenError()

  def markTechnicianAssigned(s: DbSession, id: String, by: String): Unit =
    given java.sql.Connection = Sql.connection(s)
    SQL"""UPDATE appointments SET technician_assigned_at = now(), technician_assigned_by = $by::uuid, updated_at = now()
          WHERE id = $id::uuid""".executeUpdate()
    ()
