package scheduler.modules.parts.adapters

import java.sql.SQLException

import anorm.*

import scheduler.modules.parts.*
import scheduler.shared.{AppError, Db, DbSession, PgErrors, Sql}
import scheduler.shared.Sql.*

final class PgPartsRepo(db: Db) extends PartsRepository:

  private val part: RowParser[Part] = Sql.rows { r =>
    Part(r.uuid("id"), r.str("sku"), r.str("name"), r.int("unit_price"), r.str("currency"), r.int("stock_qty"), r.bool("is_active"))
  }

  def listParts(dealershipId: String): Seq[Part] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"SELECT * FROM parts WHERE dealership_id = $dealershipId::uuid ORDER BY name".as(part.*)
  }

  override def getPart(dealershipId: String, partId: String): Option[Part] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"SELECT * FROM parts WHERE id = $partId::uuid AND dealership_id = $dealershipId::uuid".as(part.singleOpt)
  }

  def addPart(dealershipId: String, p: NewPart): String = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    try
      SQL"""INSERT INTO parts (dealership_id, sku, name, unit_price, stock_qty)
            VALUES ($dealershipId::uuid, ${p.sku}, ${p.name}, ${p.unitPrice}, ${p.stockQty}) RETURNING id"""
        .as(SqlParser.scalar[java.util.UUID].single).toString
    catch
      case e: SQLException if PgErrors.sqlState(e).contains(PgErrors.UniqueViolation) =>
        throw AppError.conflict("SKU_TAKEN", "A part with this SKU already exists")
  }

  def adjustStock(dealershipId: String, partId: String, delta: Int): Option[Int] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    try
      SQL"""UPDATE parts SET stock_qty = stock_qty + $delta WHERE id = $partId::uuid AND dealership_id = $dealershipId::uuid
            RETURNING stock_qty""".as(SqlParser.scalar[Int].singleOpt)
    catch case e: SQLException if PgErrors.sqlState(e).contains(PgErrors.CheckViolation) => throw OutOfStockError(Nil)
  }

  def appointmentBelongsTo(s: DbSession, appointmentId: String, dealershipId: String): Boolean =
    given java.sql.Connection = Sql.connection(s)
    SQL"SELECT 1 FROM appointments WHERE id = $appointmentId::uuid AND dealership_id = $dealershipId::uuid"
      .as(SqlParser.scalar[Int].singleOpt).isDefined

  def createOrder(s: DbSession, o: NewPartOrder): String =
    given java.sql.Connection = Sql.connection(s)
    val id = SQL"""
      INSERT INTO part_orders (reference, dealership_id, appointment_id, status, note, requested_by)
      VALUES (${o.reference}, ${o.dealershipId}::uuid, ${o.appointmentId}::uuid, 'REQUESTED', ${o.note}, ${o.requestedBy}::uuid)
      RETURNING id""".as(SqlParser.scalar[java.util.UUID].single).toString
    for (partId, quantity) <- o.lines do
      // The price is copied from the part; no row means the part is not in this dealership.
      val inserted = SQL"""
        INSERT INTO part_order_lines (order_id, part_id, dealership_id, quantity, unit_price)
        SELECT $id::uuid, p.id, p.dealership_id, $quantity, p.unit_price
        FROM parts p WHERE p.id = $partId::uuid AND p.dealership_id = ${o.dealershipId}::uuid AND p.is_active""".executeUpdate()
      if inserted != 1 then throw AppError.unprocessable("UNKNOWN_PART", "One of the parts is not in this dealership's list")
    SQL"""INSERT INTO part_order_events (order_id, from_status, to_status, actor_id)
          VALUES ($id::uuid, NULL, 'REQUESTED', ${o.requestedBy}::uuid)""".executeUpdate()
    id

  def lockOrder(s: DbSession, id: String): Option[LockedPartOrder] =
    given java.sql.Connection = Sql.connection(s)
    SQL"SELECT id, dealership_id, status FROM part_orders WHERE id = $id::uuid FOR UPDATE"
      .as(Sql.rows(r => LockedPartOrder(r.uuid("id"), r.uuid("dealership_id"), PartOrderStatus.valueOf(r.str("status")))).singleOpt)

  def setOrderStatus(s: DbSession, id: String, status: PartOrderStatus, actorId: String, from: PartOrderStatus): Unit =
    given java.sql.Connection = Sql.connection(s)
    SQL"UPDATE part_orders SET status = ${status.toString}, updated_at = now() WHERE id = $id::uuid".executeUpdate()
    SQL"""INSERT INTO part_order_events (order_id, from_status, to_status, actor_id)
          VALUES ($id::uuid, ${from.toString}, ${status.toString}, $actorId::uuid)""".executeUpdate()
    ()

  def applyStock(s: DbSession, orderId: String, sign: Int): Unit =
    given java.sql.Connection = Sql.connection(s)
    // Lock the parts rows first (in id order, so two orders never deadlock), then check.
    // Under the lock no one else can take the same units; the CHECK constraint is the backstop.
    val short = SQL"""
      SELECT p.name, p.stock_qty + $sign * l.quantity AS after
      FROM part_order_lines l JOIN parts p ON p.id = l.part_id
      WHERE l.order_id = $orderId::uuid
      ORDER BY p.id
      FOR UPDATE OF p""".as(Sql.rows(r => r.str("name") -> r.int("after")).*).collect { case (name, after) if after < 0 => name }
    if short.nonEmpty then throw OutOfStockError(short)
    SQL"""UPDATE parts p SET stock_qty = p.stock_qty + $sign * l.quantity
          FROM part_order_lines l WHERE l.order_id = $orderId::uuid AND p.id = l.part_id""".executeUpdate()
    ()

  private val OrderSelect = """
    SELECT o.id, o.reference, o.status, o.note, o.created_at, o.updated_at, u.full_name AS requested_by,
           a.id AS a_id, a.reference AS a_ref, lower(a.period) AS a_start, v.plate AS a_plate,
           COALESCE(json_agg(json_build_object('partId', p.id, 'sku', p.sku, 'name', p.name, 'quantity', l.quantity,
             'unitPrice', l.unit_price, 'inStock', p.stock_qty) ORDER BY p.name) FILTER (WHERE l.part_id IS NOT NULL), '[]')::text AS lines
    FROM part_orders o
    JOIN users u ON u.id = o.requested_by
    LEFT JOIN appointments a ON a.id = o.appointment_id
    LEFT JOIN vehicles v ON v.id = a.vehicle_id
    LEFT JOIN part_order_lines l ON l.order_id = o.id
    LEFT JOIN parts p ON p.id = l.part_id"""

  private val order: RowParser[PartOrderView] = Sql.rows { r =>
    val lines = play.api.libs.json.Json.parse(r.str("lines")).as[Seq[play.api.libs.json.JsObject]].map { j =>
      PartOrderLine((j \ "partId").as[String], (j \ "sku").as[String], (j \ "name").as[String], (j \ "quantity").as[Int],
        (j \ "unitPrice").as[Int], (j \ "inStock").as[Int])
    }
    PartOrderView(
      id = r.uuid("id"),
      reference = r.str("reference"),
      status = PartOrderStatus.valueOf(r.str("status")),
      appointment = r.uuidOpt("a_id").map(id => PartOrderAppointment(id, r.str("a_ref"), r.instant("a_start"), r.str("a_plate"))),
      note = r.strOpt("note"),
      requestedBy = r.str("requested_by"),
      createdAt = r.instant("created_at"),
      updatedAt = r.instant("updated_at"),
      lines = lines
    )
  }

  private val GroupBy = " GROUP BY o.id, u.full_name, a.id, v.plate"

  def getOrder(s: DbSession, id: String): Option[PartOrderView] =
    given java.sql.Connection = Sql.connection(s)
    SQL(s"$OrderSelect WHERE o.id = {id}::uuid $GroupBy").on("id" -> id).as(order.singleOpt)

  def listOrders(dealershipId: String, status: Option[PartOrderStatus], appointmentId: Option[String]): Seq[PartOrderView] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL(s"""$OrderSelect
      WHERE o.dealership_id = {dealershipId}::uuid
        AND (CAST({status} AS text) IS NULL OR o.status = CAST({status} AS text))
        AND (CAST({appointmentId} AS uuid) IS NULL OR o.appointment_id = CAST({appointmentId} AS uuid))
      $GroupBy ORDER BY o.created_at DESC LIMIT 200""")
      .on("dealershipId" -> dealershipId, "status" -> status.map(_.toString), "appointmentId" -> appointmentId)
      .as(order.*)
  }
