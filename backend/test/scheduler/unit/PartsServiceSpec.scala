package scheduler.unit

import java.time.Instant

import scala.collection.mutable

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scheduler.modules.identity.Role
import scheduler.modules.parts.*
import scheduler.modules.parts.PartOrderStatus.*
import scheduler.modules.parts.application.PartsRequests.*
import scheduler.modules.parts.application.PartsService
import scheduler.shared.{AppError, DbSession, Log}
import scheduler.support.Fakes.*

/** The parts shelf and parts orders, against an in-memory repository that keeps real stock levels. */
class PartsServiceSpec extends AnyFunSpec with Matchers:
  private val staff = auth(Role.PARTS_STAFF)

  /** Stock and orders in memory, with the same "never below zero" rule as the database. */
  private final class FakeParts extends PartsRepository:
    val parts = mutable.LinkedHashMap("p1" -> Part("p1", "OIL", "Oil", 100, "VND", 5, isActive = true),
      "p2" -> Part("p2", "PAD", "Brake pad", 50, "VND", 0, isActive = true))
    val orders = mutable.Map.empty[String, (LockedPartOrder, Seq[(String, Int)])]
    val statusLog = mutable.Buffer.empty[(String, PartOrderStatus, PartOrderStatus)]
    var created: Option[NewPartOrder] = None
    val appointments = Set("ap1")

    def listParts(dealershipId: String): Seq[Part] = parts.values.toSeq
    def addPart(dealershipId: String, part: NewPart): String =
      val id = s"p${parts.size + 1}"
      parts(id) = Part(id, part.sku, part.name, part.unitPrice, "VND", part.stockQty, isActive = true)
      id
    def adjustStock(dealershipId: String, partId: String, delta: Int): Option[Int] =
      parts.get(partId).map { p =>
        if p.stockQty + delta < 0 then throw OutOfStockError(Seq(p.name))
        parts(partId) = p.copy(stockQty = p.stockQty + delta)
        p.stockQty + delta
      }
    def appointmentBelongsTo(s: DbSession, appointmentId: String, dealershipId: String): Boolean = appointments(appointmentId)
    def createOrder(s: DbSession, order: NewPartOrder): String =
      created = Some(order)
      val id = s"o${orders.size + 1}"
      orders(id) = (LockedPartOrder(id, order.dealershipId, REQUESTED), order.lines)
      id
    def lockOrder(s: DbSession, id: String): Option[LockedPartOrder] = orders.get(id).map(_._1)
    def setOrderStatus(s: DbSession, id: String, status: PartOrderStatus, actorId: String, from: PartOrderStatus): Unit =
      statusLog += ((id, from, status))
      orders(id) = orders(id).copy(_1 = orders(id)._1.copy(status = status))
    def applyStock(s: DbSession, orderId: String, sign: Int): Unit =
      val lines = orders(orderId)._2
      val short = lines.collect { case (id, q) if parts(id).stockQty + sign * q < 0 => parts(id).name }
      if short.nonEmpty then throw OutOfStockError(short)
      lines.foreach((id, q) => parts(id) = parts(id).copy(stockQty = parts(id).stockQty + sign * q))
    def getOrder(s: DbSession, id: String): Option[PartOrderView] = orders.get(id).map { (o, lines) =>
      PartOrderView(id, "PO-1", o.status, None, None, "Name", Instant.EPOCH, Instant.EPOCH,
        lines.map((pid, q) => PartOrderLine(pid, parts(pid).sku, parts(pid).name, q, parts(pid).unitPrice, parts(pid).stockQty)))
    }
    def listOrders(dealershipId: String, status: Option[PartOrderStatus], appointmentId: Option[String]): Seq[PartOrderView] = Nil

  private final class Fixture:
    val repo = FakeParts()
    val db = FakeDb()
    val service = PartsService(db, repo)
    def order(lines: (String, Int)*): PartOrderView =
      service.createOrder(staff, NewOrder(None, None, lines.map(Line.apply).toList), Log.silent)
    def move(id: String, to: PartOrderStatus): PartOrderView = service.changeStatus(staff, id, to, Log.silent)

  private def codeOf(f: => Any): String = (the[AppError] thrownBy f).code

  describe("the shelf") {
    it("adds a part and returns it with its stock") {
      val f = Fixture()
      val p = f.service.addPart(staff, AddPart("FILTER", "Air filter", 30, 4), Log.silent)
      (p.sku, p.stockQty) shouldBe ("FILTER", 4)
    }

    it("adjusts stock, never below zero") {
      val f = Fixture()
      f.service.adjustStock(staff, "p1", StockAdjustment(-5), Log.silent).stockQty shouldBe 0
      codeOf(f.service.adjustStock(staff, "p1", StockAdjustment(-1), Log.silent)) shouldBe "OUT_OF_STOCK"
      f.repo.parts("p1").stockQty shouldBe 0
    }

    it("answers 404 for a part of another dealership") {
      (the[AppError] thrownBy Fixture().service.adjustStock(staff, "zz", StockAdjustment(1), Log.silent)).status shouldBe 404
    }

    it("needs an account attached to a dealership") {
      (the[AppError] thrownBy Fixture().service.listParts(auth(Role.SYSTEM_MANAGER, dealershipId = None))).status shouldBe 403
    }
  }

  describe("orders") {
    it("merges lines for the same part and totals the order") {
      val f = Fixture()
      val view = f.order("p1" -> 2, "p1" -> 1, "p2" -> 4)
      f.repo.created.get.lines.toMap shouldBe Map("p1" -> 3, "p2" -> 4)
      view.total shouldBe 3 * 100 + 4 * 50
      f.repo.created.get.reference should startWith("PO-")
      f.repo.created.get.requestedBy shouldBe staff.userId
    }

    it("refuses an appointment of another dealership") {
      val f = Fixture()
      codeOf(f.service.createOrder(staff, NewOrder(Some("elsewhere"), None, List(Line("p1", 1))), Log.silent)) shouldBe "UNKNOWN_APPOINTMENT"
      f.repo.created shouldBe empty
    }

    it("issues straight from stock and takes the parts off the shelf") {
      val f = Fixture()
      val id = f.order("p1" -> 2).id
      f.move(id, ISSUED).status shouldBe ISSUED
      f.repo.parts("p1").stockQty shouldBe 3
    }

    it("refuses to issue what is not on the shelf, changing nothing") {
      val f = Fixture()
      val id = f.order("p1" -> 1, "p2" -> 1).id
      val e = the[AppError] thrownBy f.move(id, ISSUED)
      e.code shouldBe "OUT_OF_STOCK"
      e.message should include("Brake pad")
      f.repo.parts("p1").stockQty shouldBe 5
      f.repo.statusLog shouldBe empty
    }

    it("puts delivered parts on the shelf, then issues them") {
      val f = Fixture()
      val id = f.order("p2" -> 2).id
      f.move(id, ORDERED)
      f.repo.parts("p2").stockQty shouldBe 0
      f.move(id, RECEIVED)
      f.repo.parts("p2").stockQty shouldBe 2
      f.move(id, ISSUED)
      f.repo.parts("p2").stockQty shouldBe 0
      f.repo.statusLog.map(t => (t._2, t._3)).toSeq shouldBe Seq(REQUESTED -> ORDERED, ORDERED -> RECEIVED, RECEIVED -> ISSUED)
    }

    it("refuses transitions the lifecycle does not allow") {
      val f = Fixture()
      val id = f.order("p1" -> 1).id
      f.move(id, CANCELLED)
      codeOf(f.move(id, ORDERED)) shouldBe "INVALID_STATUS_TRANSITION"
      f.repo.parts("p1").stockQty shouldBe 5
    }

    it("keeps staff to their own dealership's orders") {
      val f = Fixture()
      val id = f.order("p1" -> 1).id
      val other = auth(Role.PARTS_STAFF, dealershipId = Some("d2"))
      (the[AppError] thrownBy f.service.changeStatus(other, id, ISSUED, Log.silent)).status shouldBe 403
    }
  }
