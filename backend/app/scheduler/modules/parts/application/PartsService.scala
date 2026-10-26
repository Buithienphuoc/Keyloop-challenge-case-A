package scheduler.modules.parts.application

import play.api.libs.functional.syntax.*
import play.api.libs.json.*

import scheduler.modules.identity.{AuthContext, assertSameDealership, requireDealership}
import scheduler.modules.parts.*
import scheduler.shared.{AppError, Db, Ids, Log, Metrics, Validate}

object PartsRequests:
  final case class AddPart(sku: String, name: String, unitPrice: Int, stockQty: Int)
  given Reads[AddPart] = (
    (__ \ "sku").read(Validate.matching("[A-Za-z0-9._-]{2,40}".r, "2-40 letters, digits, dots or dashes").map(_.toUpperCase)) and
      (__ \ "name").read(Validate.text(2, 120)) and
      (__ \ "unitPrice").read(Validate.int(0, 1_000_000_000)) and
      (__ \ "stockQty").readWithDefault(0)(Validate.int(0, 100_000))
  )(AddPart.apply)

  final case class StockAdjustment(delta: Int)
  given Reads[StockAdjustment] = (__ \ "delta").read(Validate.int(-100_000, 100_000)).map(StockAdjustment.apply)

  final case class Line(partId: String, quantity: Int)
  private given Reads[Line] = ((__ \ "partId").read(Validate.uuid) and (__ \ "quantity").read(Validate.int(1, 100)))(Line.apply)

  final case class NewOrder(appointmentId: Option[String], note: Option[String], lines: List[Line])
  given Reads[NewOrder] = (
    (__ \ "appointmentId").readNullable(Validate.uuid) and
      (__ \ "note").readNullable(Validate.text(0, 500)).map(_.filter(_.nonEmpty)) and
      (__ \ "lines").read(Validate.listOf(summon[Reads[Line]], min = 1, max = 30, tooFew = "Add at least one part"))
  )(NewOrder.apply)

  final case class OrderStatusChange(status: PartOrderStatus)
  given Reads[OrderStatusChange] = (__ \ "status").read(Validate.oneOf(PartOrderStatus.values)).map(OrderStatusChange.apply)

  final case class OrderQuery(status: Option[PartOrderStatus], appointmentId: Option[String])
  given Reads[OrderQuery] = (
    (__ \ "status").readNullable(Validate.oneOf(PartOrderStatus.values)) and
      (__ \ "appointmentId").readNullable(Validate.uuid)
  )(OrderQuery.apply)

/**
 * Replacement parts for one dealership: the shelf (stock levels) and the orders that
 * advisors and technicians raise, usually for a booked job. Stock can never go below zero,
 * even when two people issue the last unit at once (row locks + a CHECK constraint).
 */
final class PartsService(db: Db, parts: PartsRepository):
  import PartsRequests.*

  def listParts(auth: AuthContext): Seq[Part] = parts.listParts(auth.requireDealership)

  def addPart(auth: AuthContext, input: AddPart, log: Log): Part =
    val dealershipId = auth.requireDealership
    val id = parts.addPart(dealershipId, NewPart(input.sku, input.name, input.unitPrice, input.stockQty))
    log.info("parts.part_added", "partId" -> id, "sku" -> input.sku, "stockQty" -> input.stockQty)
    parts.getPart(dealershipId, id).getOrElse(throw IllegalStateException(s"Part $id vanished after insert"))

  def adjustStock(auth: AuthContext, partId: String, input: StockAdjustment, log: Log): Part =
    val dealershipId = auth.requireDealership
    val qty =
      try parts.adjustStock(dealershipId, partId, input.delta).getOrElse(throw AppError.notFound("Part"))
      catch case _: OutOfStockError => throw AppError.conflict("OUT_OF_STOCK", "Stock cannot go below zero")
    log.info("parts.stock_adjusted", "partId" -> partId, "delta" -> input.delta, "stockQty" -> qty)
    parts.getPart(dealershipId, partId).getOrElse(throw AppError.notFound("Part"))

  def createOrder(auth: AuthContext, input: NewOrder, log: Log): PartOrderView =
    val dealershipId = auth.requireDealership
    val lines = input.lines.groupMapReduce(_.partId)(_.quantity)(_ + _).toSeq
    val view = db.tx { s =>
      input.appointmentId.foreach { id =>
        if !parts.appointmentBelongsTo(s, id, dealershipId) then throw AppError.unprocessable("UNKNOWN_APPOINTMENT", "No such appointment here")
      }
      val id = parts.createOrder(s, NewPartOrder(Ids.humanReference("PO"), dealershipId, input.appointmentId, input.note, auth.userId, lines))
      parts.getOrder(s, id).get
    }
    Metrics.partOrders.labelValues("created").inc()
    log.info("parts.order_created", "orderId" -> view.id, "reference" -> view.reference, "appointmentId" -> input.appointmentId,
      "lines" -> lines.size, "total" -> view.total)
    view

  def listOrders(auth: AuthContext, q: OrderQuery): Seq[PartOrderView] = parts.listOrders(auth.requireDealership, q.status, q.appointmentId)

  def changeStatus(auth: AuthContext, id: String, to: PartOrderStatus, log: Log): PartOrderView =
    val (from, view) = db.tx { s =>
      val order = parts.lockOrder(s, id).getOrElse(throw AppError.notFound("Parts order"))
      assertSameDealership(auth, order.dealershipId)
      if !PartOrderLifecycle.canTransition(order.status, to) then
        throw AppError.conflict("INVALID_STATUS_TRANSITION", s"Cannot go from ${order.status} to $to")
      val sign = PartOrderLifecycle.stockEffect(to)
      if sign != 0 then
        try parts.applyStock(s, id, sign)
        catch
          case e: OutOfStockError =>
            throw AppError.conflict("OUT_OF_STOCK", s"Not enough on the shelf: ${e.parts.mkString(", ")}. Order it from the supplier first.")
      parts.setOrderStatus(s, id, to, auth.userId, order.status)
      (order.status, parts.getOrder(s, id).get)
    }
    Metrics.partOrders.labelValues(to.toString).inc()
    log.info("parts.order_status_changed", "orderId" -> id, "from" -> from, "to" -> to)
    view
