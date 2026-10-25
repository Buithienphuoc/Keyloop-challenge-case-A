package scheduler.modules.parts

import scheduler.shared.DbSession

final case class NewPart(sku: String, name: String, unitPrice: Int, stockQty: Int)

final case class NewPartOrder(
    reference: String,
    dealershipId: String,
    appointmentId: Option[String],
    note: Option[String],
    requestedBy: String,
    /** part id → quantity */
    lines: Seq[(String, Int)]
)

final case class LockedPartOrder(id: String, dealershipId: String, status: PartOrderStatus)

/** Parts that are short for an order, by name. */
final class OutOfStockError(val parts: Seq[String]) extends RuntimeException(s"Not enough stock: ${parts.mkString(", ")}")

/** Port: the parts shelf and parts orders of a dealership. */
trait PartsRepository:
  def listParts(dealershipId: String): Seq[Part]

  /** 409 SKU_TAKEN. */
  def addPart(dealershipId: String, part: NewPart): String

  /** Adds `delta` (may be negative) to the stock; None when the part is not in this dealership. Throws OutOfStockError below zero. */
  def adjustStock(dealershipId: String, partId: String, delta: Int): Option[Int]

  def appointmentBelongsTo(s: DbSession, appointmentId: String, dealershipId: String): Boolean

  /** Prices are copied from the parts list at the time of ordering. 422 UNKNOWN_PART. */
  def createOrder(s: DbSession, order: NewPartOrder): String

  def lockOrder(s: DbSession, id: String): Option[LockedPartOrder]
  def setOrderStatus(s: DbSession, id: String, status: PartOrderStatus, actorId: String, from: PartOrderStatus): Unit

  /** Moves the order's quantities onto (+1) or off (-1) the shelf. Throws OutOfStockError, changing nothing. */
  def applyStock(s: DbSession, orderId: String, sign: Int): Unit

  def getOrder(s: DbSession, id: String): Option[PartOrderView]
  def listOrders(dealershipId: String, status: Option[PartOrderStatus], appointmentId: Option[String]): Seq[PartOrderView]
