package scheduler.modules.parts

import java.time.Instant

import PartOrderStatus.*

/**
 * A parts order moves through:
 *
 *   REQUESTED ──order from supplier──▶ ORDERED ──delivery──▶ RECEIVED ──hand to technician──▶ ISSUED
 *       │                                 │
 *       ├──────── from stock ─────────────┼──────────────────────────────────────────────▶ ISSUED
 *       └──cancel──▶ CANCELLED ◀──cancel──┘
 *
 * Stock follows the order: RECEIVED adds the quantities to the shelf, ISSUED takes them off.
 */
enum PartOrderStatus:
  case REQUESTED, ORDERED, RECEIVED, ISSUED, CANCELLED

object PartOrderLifecycle:
  private val Transitions: Map[PartOrderStatus, Set[PartOrderStatus]] = Map(
    REQUESTED -> Set(ORDERED, ISSUED, CANCELLED),
    ORDERED -> Set(RECEIVED, CANCELLED),
    RECEIVED -> Set(ISSUED),
    ISSUED -> Set.empty,
    CANCELLED -> Set.empty
  )

  def canTransition(from: PartOrderStatus, to: PartOrderStatus): Boolean = Transitions(from).contains(to)

  /** +1: the order's quantities go onto the shelf; -1: they come off it; 0: no stock change. */
  def stockEffect(to: PartOrderStatus): Int = to match
    case RECEIVED => 1
    case ISSUED   => -1
    case _        => 0

final case class Part(
    id: String,
    sku: String,
    name: String,
    unitPrice: Int,
    currency: String,
    stockQty: Int,
    isActive: Boolean
)

final case class PartOrderLine(partId: String, sku: String, name: String, quantity: Int, unitPrice: Int, inStock: Int)

final case class PartOrderAppointment(id: String, reference: String, start: Instant, vehiclePlate: String)

final case class PartOrderView(
    id: String,
    reference: String,
    status: PartOrderStatus,
    appointment: Option[PartOrderAppointment],
    note: Option[String],
    requestedBy: String,
    createdAt: Instant,
    updatedAt: Instant,
    lines: Seq[PartOrderLine]
):
  def total: Int = lines.map(l => l.quantity * l.unitPrice).sum
