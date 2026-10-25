package scheduler.modules.consultation.adapters

import anorm.*

import scheduler.modules.consultation.*
import scheduler.modules.scheduling.domain.{Requirement, VehicleType}
import scheduler.shared.{Db, DbSession, Sql}
import scheduler.shared.Sql.*

final class PgConsultationRepo(db: Db) extends ConsultationRepository:

  private val ViewSelect = """
    SELECT c.*, v.vehicle_type, v.make, v.model, v.plate, d.name AS d_name, d.timezone AS d_tz,
           u.full_name AS u_name, u.phone AS u_phone, u.email AS u_email, adv.full_name AS adv_name,
           ap.id AS ap_id, ap.reference AS ap_ref, lower(ap.period) AS ap_start, ap.status AS ap_status
    FROM consultation_requests c
    JOIN vehicles v ON v.id = c.vehicle_id
    JOIN dealerships d ON d.id = c.dealership_id
    JOIN users u ON u.id = c.customer_id
    LEFT JOIN users adv ON adv.id = c.advisor_id
    LEFT JOIN LATERAL (
      SELECT * FROM appointments a WHERE a.consultation_request_id = c.id
      ORDER BY a.created_at DESC LIMIT 1
    ) ap ON true"""

  private val view: RowParser[ConsultationView] = Sql.rows { r =>
    ConsultationView(
      id = r.uuid("id"),
      reference = r.str("reference"),
      kind = ConsultationKind.valueOf(r.str("kind")),
      status = ConsultationStatus.valueOf(r.str("status")),
      areas = r.strings("areas"),
      description = r.str("description"),
      vehicle = ConsultationView.VehicleRef(r.uuid("vehicle_id"), VehicleType.valueOf(r.str("vehicle_type")), r.str("make"), r.str("model"), r.str("plate")),
      dealership = ConsultationView.DealershipRef(r.uuid("dealership_id"), r.str("d_name")),
      customer = ConsultationView.CustomerRef(r.uuid("customer_id"), r.str("u_name"), r.strOpt("u_phone"), r.str("u_email")),
      advisorNote = r.strOpt("advisor_note"),
      advisorName = r.strOpt("adv_name"),
      durationMinutes = r.intOpt("duration_minutes"),
      requiredSkill = r.strOpt("required_skill"),
      bayType = r.strOpt("bay_type"),
      estimatedPrice = r.intOpt("estimated_price"),
      createdAt = r.instant("created_at"),
      reviewedAt = r.instantOpt("reviewed_at"),
      appointment = r.uuidOpt("ap_id").map { id =>
        ConsultationView.AppointmentRef(id, r.str("ap_ref"), r.instant("ap_start"), r.str("ap_status"))
      }
    )
  }

  def insert(c: NewConsultation): String = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"""INSERT INTO consultation_requests (reference, customer_id, vehicle_id, dealership_id, kind, areas, description, status)
          VALUES (${c.reference}, ${c.customerId}::uuid, ${c.vehicleId}::uuid, ${c.dealershipId}::uuid, ${c.kind.toString},
                  ${textArray(c.areas)}, ${c.description}, 'SUBMITTED')
          RETURNING id""".as(SqlParser.scalar[java.util.UUID].single).toString
  }

  def get(id: String): Option[ConsultationView] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL(s"$ViewSelect WHERE c.id = {id}::uuid").on("id" -> id).as(view.singleOpt)
  }

  def listByCustomer(customerId: String): Seq[ConsultationView] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL(s"$ViewSelect WHERE c.customer_id = {customerId}::uuid ORDER BY c.created_at DESC").on("customerId" -> customerId).as(view.*)
  }

  def listByDealership(dealershipId: String, status: Option[ConsultationStatus]): Seq[ConsultationView] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL(s"""$ViewSelect WHERE c.dealership_id = {dealershipId}::uuid AND (CAST({status} AS text) IS NULL OR c.status = CAST({status} AS text))
            ORDER BY (c.status = 'SUBMITTED') DESC, c.created_at""")
      .on("dealershipId" -> dealershipId, "status" -> status.map(_.toString))
      .as(view.*)
  }

  def lockForReview(s: DbSession, id: String): Option[ReviewTarget] =
    given java.sql.Connection = Sql.connection(s)
    SQL"""SELECT c.status, c.dealership_id, c.kind, v.vehicle_type
          FROM consultation_requests c JOIN vehicles v ON v.id = c.vehicle_id
          WHERE c.id = $id::uuid FOR UPDATE OF c""".as(Sql.rows { r =>
      ReviewTarget(
        ConsultationStatus.valueOf(r.str("status")),
        r.uuid("dealership_id"),
        ConsultationKind.valueOf(r.str("kind")),
        VehicleType.valueOf(r.str("vehicle_type"))
      )
    }.singleOpt)

  def approve(s: DbSession, id: String, a: Approval): Unit =
    given java.sql.Connection = Sql.connection(s)
    SQL"""UPDATE consultation_requests SET status = 'APPROVED', advisor_id = ${a.advisorId}::uuid, advisor_note = ${a.note},
            duration_minutes = ${a.durationMinutes}, required_skill = ${a.requiredSkill}, bay_type = ${a.bayType},
            estimated_price = ${a.estimatedPrice}, reviewed_at = now()
          WHERE id = $id::uuid""".executeUpdate()
    ()

  def reject(s: DbSession, id: String, advisorId: String, note: String): Unit =
    given java.sql.Connection = Sql.connection(s)
    SQL"""UPDATE consultation_requests SET status = 'REJECTED', advisor_id = $advisorId::uuid, advisor_note = $note, reviewed_at = now()
          WHERE id = $id::uuid""".executeUpdate()
    ()

  def cancelByCustomer(id: String, customerId: String): Boolean = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"""UPDATE consultation_requests SET status = 'CANCELLED'
          WHERE id = $id::uuid AND customer_id = $customerId::uuid AND status IN ('SUBMITTED', 'APPROVED')""".executeUpdate() == 1
  }

  def forBooking(s: DbSession, id: String): Option[BookableConsultation] =
    given java.sql.Connection = Sql.connection(s)
    SQL"SELECT * FROM consultation_requests WHERE id = $id::uuid".as(Sql.rows { r =>
      BookableConsultation(
        id = r.uuid("id"),
        kind = ConsultationKind.valueOf(r.str("kind")),
        dealershipId = r.uuid("dealership_id"),
        vehicleId = r.uuid("vehicle_id"),
        customerId = r.uuid("customer_id"),
        status = ConsultationStatus.valueOf(r.str("status")),
        requirement = r.intOpt("duration_minutes").map(d => Requirement(d, r.str("required_skill"), r.str("bay_type")))
      )
    }.singleOpt)

  def markBooked(s: DbSession, id: String): Boolean =
    given java.sql.Connection = Sql.connection(s)
    SQL"UPDATE consultation_requests SET status = 'BOOKED' WHERE id = $id::uuid AND status = 'APPROVED'".executeUpdate() == 1

  def reopen(s: DbSession, id: String): Unit =
    given java.sql.Connection = Sql.connection(s)
    SQL"UPDATE consultation_requests SET status = 'APPROVED' WHERE id = $id::uuid AND status = 'BOOKED'".executeUpdate()
    ()
