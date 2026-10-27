package scheduler.unit

import java.time.Instant

import scala.collection.mutable

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scheduler.modules.consultation.*
import scheduler.modules.consultation.ConsultationStatus.*
import scheduler.modules.consultation.application.{ConsultationService, Review, SubmitRequest}
import scheduler.modules.identity.Role
import scheduler.modules.scheduling.domain.VehicleType
import scheduler.modules.vehicles.{OwnedVehicles, Vehicle}
import scheduler.shared.{AppError, DbSession, Log}
import scheduler.support.Fakes.*

/** Inspection and customisation requests: submit, review, cancel, against in-memory ports. */
class ConsultationServiceSpec extends AnyFunSpec with Matchers:
  private val advisor = auth(Role.SERVICE_ADVISOR)

  private final class FakeRepo extends ConsultationRepository:
    val rows = mutable.Map.empty[String, (NewConsultation, ConsultationStatus)]
    var approval: Option[Approval] = None
    var rejection: Option[String] = None
    var vehicleType: VehicleType = VehicleType.MOTORBIKE

    def insert(c: NewConsultation): String =
      val id = s"c${rows.size + 1}"
      rows(id) = (c, SUBMITTED)
      id
    def get(id: String): Option[ConsultationView] = rows.get(id).map { (c, status) =>
      ConsultationView(id, c.reference, c.kind, status, c.areas, c.description,
        ConsultationView.VehicleRef(c.vehicleId, vehicleType, "Honda", "Wave", "X"), ConsultationView.DealershipRef(c.dealershipId, "D"),
        ConsultationView.CustomerRef(c.customerId, "C", None, "c@x"), None, None, None, None, None, None, Instant.EPOCH, None, None)
    }
    def listByCustomer(customerId: String): Seq[ConsultationView] = Nil
    def listByDealership(dealershipId: Option[String], status: Option[ConsultationStatus]): Seq[ConsultationView] = Nil
    def lockForReview(s: DbSession, id: String): Option[ReviewTarget] =
      rows.get(id).map((c, status) => ReviewTarget(status, c.dealershipId, c.kind, vehicleType))
    def approve(s: DbSession, id: String, a: Approval): Unit =
      approval = Some(a)
      rows(id) = rows(id).copy(_2 = APPROVED)
    def reject(s: DbSession, id: String, advisorId: String, note: String): Unit =
      rejection = Some(note)
      rows(id) = rows(id).copy(_2 = REJECTED)
    def cancelByCustomer(id: String, customerId: String): Boolean = rows.get(id) match
      case Some((c, status)) if c.customerId == customerId && Set(SUBMITTED, APPROVED)(status) =>
        rows(id) = (c, CANCELLED)
        true
      case _ => false
    def forBooking(s: DbSession, id: String): Option[BookableConsultation] = None
    def markBooked(s: DbSession, id: String): Boolean = false
    def reopen(s: DbSession, id: String): Unit = ()

  private val bike = Vehicle("v1", VehicleType.MOTORBIKE, "Honda", "Wave", None, "X", None, None)
  private object Vehicles extends OwnedVehicles:
    def getOwned(ownerId: String, vehicleId: String): Vehicle =
      if ownerId == "u1" && vehicleId == "v1" then bike else throw AppError.notFound("Vehicle")

  private final class Fixture:
    val repo = FakeRepo()
    val service = ConsultationService(FakeDb(), repo, Vehicles, FakeCatalog())
    def submit(dealershipId: String = "d1", areas: List[String] = List("BRAKES", "BRAKES", "ENGINE")) =
      service.submit(customer, SubmitRequest(ConsultationKind.INSPECTION, "v1", dealershipId, areas, "Squeaky brakes"), Log.silent)

  private def approveWith(skill: String = "GENERAL_SERVICE", bayType: String = "MOTORBIKE_STAND"): Review =
    Review.Approve(60, skill, bayType, Some(100), None)
  private val approve = approveWith()
  private def codeOf(f: => Any): String = (the[AppError] thrownBy f).code
  private def statusOf(f: => Any): Int = (the[AppError] thrownBy f).status

  describe("submit") {
    it("stores the request once per area, with a CR reference") {
      val f = Fixture()
      val view = f.submit()
      view.status shouldBe SUBMITTED
      view.areas shouldBe Seq("BRAKES", "ENGINE")
      view.reference should startWith("CR-")
    }

    it("only for the customer's own vehicle and a known dealership") {
      val f = Fixture()
      statusOf(f.service.submit(customer.copy(userId = "u2"),
        SubmitRequest(ConsultationKind.INSPECTION, "v1", "d1", List("BRAKES"), "Squeaky brakes"), Log.silent)) shouldBe 404
      statusOf(f.submit(dealershipId = "nowhere")) shouldBe 404
      f.repo.rows shouldBe empty
    }
  }

  describe("review") {
    it("approves with what the scheduler needs") {
      val f = Fixture()
      val id = f.submit().id
      f.service.review(advisor, id, approve, Log.silent).status shouldBe APPROVED
      f.repo.approval.map(a => (a.advisorId, a.durationMinutes, a.bayType)) shouldBe Some((advisor.userId, 60, "MOTORBIKE_STAND"))
    }

    it("rejects with a note for the customer") {
      val f = Fixture()
      val id = f.submit().id
      f.service.review(advisor, id, Review.Reject("Not something we do"), Log.silent).status shouldBe REJECTED
      f.repo.rejection shouldBe Some("Not something we do")
    }

    it("reviews each request only once") {
      val f = Fixture()
      val id = f.submit().id
      f.service.review(advisor, id, approve, Log.silent)
      codeOf(f.service.review(advisor, id, Review.Reject("Changed my mind"), Log.silent)) shouldBe "ALREADY_REVIEWED"
    }

    it("refuses an unknown skill or a bay that cannot take the vehicle") {
      val f = Fixture()
      val id = f.submit().id
      codeOf(f.service.review(advisor, id, approveWith(skill = "WELDING"), Log.silent)) shouldBe "UNKNOWN_SKILL"
      codeOf(f.service.review(advisor, id, approveWith(bayType = "CAR_LIFT"), Log.silent)) shouldBe "BAY_TYPE_NOT_FOR_VEHICLE"
      f.repo.approval shouldBe empty
    }

    it("lets advisors, technicians and admins of another dealership review it, but not other staff") {
      for role <- Seq(Role.SERVICE_ADVISOR, Role.TECHNICIAN, Role.ADMIN) do
        val f = Fixture()
        val id = f.submit().id
        f.service.review(auth(role, dealershipId = Some("d2")), id, approve, Log.silent).id shouldBe id
      val f = Fixture()
      val id = f.submit().id
      statusOf(f.service.review(auth(Role.WORKSHOP_CONTROLLER, dealershipId = Some("d2")), id, approve, Log.silent)) shouldBe 403
    }
  }

  describe("getVisible and cancel") {
    it("hides other customers' requests behind a 404 and shows them to advisors of every dealership") {
      val f = Fixture()
      val id = f.submit().id
      statusOf(f.service.getVisible(customer.copy(userId = "u2"), id)) shouldBe 404
      f.service.getVisible(auth(Role.SERVICE_ADVISOR, dealershipId = Some("d2")), id).id shouldBe id
      f.service.getVisible(auth(Role.TECHNICIAN, dealershipId = Some("d2")), id).id shouldBe id
      statusOf(f.service.getVisible(auth(Role.PARTS_STAFF, dealershipId = Some("d2")), id)) shouldBe 403
      f.service.getVisible(advisor, id).id shouldBe id
    }

    it("lets the customer cancel before booking, not after a rejection") {
      val f = Fixture()
      val open = f.submit().id
      f.service.cancel(customer, open, Log.silent).status shouldBe CANCELLED
      val rejected = f.submit().id
      f.service.review(advisor, rejected, Review.Reject("Not something we do"), Log.silent)
      codeOf(f.service.cancel(customer, rejected, Log.silent)) shouldBe "CANNOT_CANCEL"
    }

    it("answers 404 when another customer tries to cancel") {
      val f = Fixture()
      val id = f.submit().id
      statusOf(f.service.cancel(customer.copy(userId = "u2"), id, Log.silent)) shouldBe 404
    }

    it("refuses the dealership list to an account without a dealership") {
      statusOf(Fixture().service.listForDealership(auth(Role.SYSTEM_MANAGER, dealershipId = None), None)) shouldBe 403
    }
  }
