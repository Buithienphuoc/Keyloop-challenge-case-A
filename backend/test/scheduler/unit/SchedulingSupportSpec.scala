package scheduler.unit

import java.time.{Instant, LocalDate, LocalTime, OffsetDateTime}

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scheduler.modules.consultation.{BookableConsultation, ConsultationKind, ConsultationStatus}
import scheduler.modules.identity.Role
import scheduler.modules.scheduling.application.*
import scheduler.modules.scheduling.domain.*
import scheduler.modules.vehicles.Vehicle
import scheduler.shared.{AppError, Log}
import scheduler.support.Fakes.*

/** WorkshopContext (what a job needs), AvailabilityService (free slots) and HoldSweeper, against fakes. */
class SchedulingSupportSpec extends AnyFunSpec with Matchers:
  private val bike = Vehicle("v1", VehicleType.MOTORBIKE, "Honda", "Wave", None, "X", None, None)
  private val car = bike.copy(id = "v2", vehicleType = VehicleType.CAR)
  private val inspection = Requirement(45, "ENGINE", "DIAGNOSTIC_BAY")

  private def consultation(status: ConsultationStatus = ConsultationStatus.APPROVED, customerId: String = "u1",
      dealershipId: String = "d1", requirement: Option[Requirement] = Some(inspection)) =
    BookableConsultation("c1", ConsultationKind.INSPECTION, dealershipId, "v1", customerId, status, requirement)

  private def context(c: BookableConsultation = consultation()) =
    WorkshopContext(FakeCatalog(), FakeConsultations(Map("c1" -> c)))

  private final case class Source(packageId: Option[String], consultationRequestId: Option[String]) extends ServiceSource
  private val byPackage = Source(Some("p1"), None)
  private val byRequest = Source(None, Some("c1"))

  private def codeOf(f: => Any): String = (the[AppError] thrownBy f).code

  describe("WorkshopContext.requirementFor") {
    it("takes duration, skill and bay type from the package") {
      context().requirementFor(Session, customer, "d1", byPackage, Some(bike)) shouldBe Requirement(60, "GENERAL_SERVICE", "MOTORBIKE_STAND")
    }

    it("refuses a package made for another vehicle type") {
      codeOf(context().requirementFor(Session, customer, "d1", byPackage, Some(car))) shouldBe "PACKAGE_NOT_FOR_VEHICLE"
    }

    it("answers 404 for an unknown package or dealership") {
      (the[AppError] thrownBy context().requirementFor(Session, customer, "d1", Source(Some("zz"), None))).status shouldBe 404
      (the[AppError] thrownBy context().dealership("zz")).status shouldBe 404
    }

    it("needs exactly one of package or consultation") {
      codeOf(context().requirementFor(Session, customer, "d1", Source(None, None))) shouldBe "INVALID_SERVICE"
      codeOf(context().requirementFor(Session, customer, "d1", Source(Some("p1"), Some("c1")))) shouldBe "INVALID_SERVICE"
    }

    it("uses the advisor's assessment of an approved consultation") {
      context().requirementFor(Session, customer, "d1", byRequest, Some(bike)) shouldBe inspection
    }

    it("only books consultations that are approved and have an assessment") {
      codeOf(context(consultation(status = ConsultationStatus.SUBMITTED)).requirementFor(Session, customer, "d1", byRequest)) shouldBe
        "CONSULTATION_NOT_BOOKABLE"
      codeOf(context(consultation(status = ConsultationStatus.BOOKED)).requirementFor(Session, customer, "d1", byRequest)) shouldBe
        "CONSULTATION_NOT_BOOKABLE"
      codeOf(context(consultation(requirement = None)).requirementFor(Session, customer, "d1", byRequest)) shouldBe
        "CONSULTATION_NOT_BOOKABLE"
    }

    it("hides another customer's consultation behind a 404") {
      (the[AppError] thrownBy context(consultation(customerId = "other")).requirementFor(Session, customer, "d1", byRequest)).status shouldBe 404
    }

    it("keeps staff outside the every-workshop roles to their own dealership's consultations") {
      val controller = auth(Role.WORKSHOP_CONTROLLER, dealershipId = Some("d2"))
      (the[AppError] thrownBy context().requirementFor(Session, controller, "d1", byRequest)).status shouldBe 403
    }

    it("refuses a consultation reviewed by another dealership or for another vehicle") {
      codeOf(context(consultation(dealershipId = "d2")).requirementFor(Session, customer, "d1", byRequest)) shouldBe "WRONG_DEALERSHIP"
      codeOf(context().requirementFor(Session, customer, "d1", byRequest, Some(car))) shouldBe "WRONG_VEHICLE"
    }
  }

  describe("Workshop.isOfferedHere") {
    val req = Requirement(60, "GENERAL_SERVICE", "MOTORBIKE_STAND")
    val stand = Bay("b1", "S", "MOTORBIKE_STAND")
    val t = Technician("t1", "T", Seq("GENERAL_SERVICE"), LocalTime.of(8, 0), LocalTime.of(17, 0), Set(1))

    it("needs both a bay of the type and a technician with the skill") {
      Workshop.isOfferedHere(req, Seq(stand), Seq(t)) shouldBe true
      Workshop.isOfferedHere(req, Seq(stand.copy(bayType = "CAR_LIFT")), Seq(t)) shouldBe false
      Workshop.isOfferedHere(req, Seq(stand), Seq(t.copy(skills = Seq("ENGINE")))) shouldBe false
      Workshop.isOfferedHere(req, Nil, Nil) shouldBe false
    }

    it("copies the dealership's booking rules") {
      val rules = Workshop.rulesOf(dealership)
      rules.openDays shouldBe Set(1, 2, 3, 4, 5, 6)
      rules.slotMinutes shouldBe 30
      rules.timezone shouldBe Zone
    }
  }

  describe("AvailabilityService") {
    // Monday 2 Nov 2026 07:00 local; dealership: 08:00-17:30, Mon-Sat, 30-minute grid, 60-minute lead.
    val now = OffsetDateTime.parse("2026-11-02T07:00:00+07:00").toInstant
    val stand = Bay("b1", "Stand 1", "MOTORBIKE_STAND")
    val minh = Technician("t1", "Minh", Seq("GENERAL_SERVICE"), LocalTime.of(8, 0), LocalTime.of(17, 30), Set(1, 2, 3, 4, 5, 6))
    val req = Requirement(60, "GENERAL_SERVICE", "MOTORBIKE_STAND")

    def service(store: FakeStore) =
      val consultations = FakeConsultations()
      AvailabilityService(FakeDb(), store, WorkshopContext(FakeCatalog(), consultations), () => now)

    def at(local: String) = OffsetDateTime.parse(s"${local}+07:00").atZoneSameInstant(Zone)
    def hhmm(s: Slot) = s"${s.start.toLocalDate} ${s.start.toLocalTime}"

    it("offers the free slots closest to the requested time first") {
      val store = FakeStore(bays = Seq(stand), technicians = Seq(minh))
      val nearest = service(store).nearest(Session, Workshop.rulesOf(dealership), req, "d1", at("2026-11-03T10:00:00"))
      nearest.map(hhmm) shouldBe Seq("2026-11-03 10:00", "2026-11-03 09:30", "2026-11-03 10:30")
    }

    it("moves on to the following days when the requested day is full") {
      val dayStart = at("2026-11-03T00:00:00").toInstant.toEpochMilli
      val dayEnd = at("2026-11-04T00:00:00").toInstant.toEpochMilli
      val store = FakeStore(bays = Seq(stand), technicians = Seq(minh), busy = Seq(BusyPeriod("b1", "t1", dayStart, dayEnd)))
      val nearest = service(store).nearest(Session, Workshop.rulesOf(dealership), req, "d1", at("2026-11-03T10:00:00"))
      nearest.map(hhmm) shouldBe Seq("2026-11-04 08:00", "2026-11-04 08:30", "2026-11-04 09:00")
      store.busyQueries.size shouldBe 2 // stops loading days once it has enough
    }

    it("returns nothing at once when the dealership cannot do the job") {
      val store = FakeStore(bays = Seq(stand.copy(bayType = "CAR_LIFT")), technicians = Seq(minh))
      service(store).nearest(Session, Workshop.rulesOf(dealership), req, "d1", at("2026-11-03T10:00:00")) shouldBe empty
      store.busyQueries shouldBe empty
    }

    it("refuses slots inside the booking lead time") {
      val store = FakeStore(bays = Seq(stand), technicians = Seq(minh))
      val late = AvailabilityService(FakeDb(), store, WorkshopContext(FakeCatalog(), FakeConsultations()), () => now.plusSeconds(60))
      val r = late.forDay(customer, AvailabilityQuery("d1", LocalDate.parse("2026-11-02"), Some("p1"), None))
      r.slots.head.reason shouldBe Some(SlotUnavailable.PAST_OR_TOO_SOON)
      r.slots(1).available shouldBe true
    }

    it("answers a day's slots with the dates that can be booked") {
      val store = FakeStore(bays = Seq(stand), technicians = Seq(minh))
      val r = service(store).forDay(customer, AvailabilityQuery("d1", LocalDate.parse("2026-11-02"), Some("p1"), None))
      r.offeredHere shouldBe true
      r.durationMinutes shouldBe 60
      r.timezone shouldBe "Asia/Ho_Chi_Minh"
      r.slots.head.available shouldBe true // 08:00 is exactly now + the 60-minute lead
      r.slots.last.start.toLocalTime shouldBe LocalTime.of(16, 30)
      r.bookableDates.head shouldBe LocalDate.parse("2026-11-02")
      r.bookableDates should not contain LocalDate.parse("2026-11-08") // Sunday
    }

    it("still answers, with offeredHere = false, when nobody can do the job") {
      val store = FakeStore(bays = Nil, technicians = Seq(minh))
      val r = service(store).forDay(customer, AvailabilityQuery("d1", LocalDate.parse("2026-11-03"), Some("p1"), None))
      r.offeredHere shouldBe false
      r.slots.forall(!_.available) shouldBe true
    }
  }

  describe("HoldSweeper") {
    it("releases expired holds everywhere and reopens their consultations") {
      val store = FakeStore(expiredHolds = Seq(ExpiredHold("h1", Some("c1")), ExpiredHold("h2", None)))
      val consultations = FakeConsultations()
      val db = FakeDb()
      HoldSweeper(db, store, consultations, () => Instant.EPOCH, Log.silent).sweep() shouldBe 2
      store.holdScopes.toSeq shouldBe Seq(HoldScope.Everywhere)
      consultations.reopened.toSeq shouldBe Seq("c1")
      db.transactions shouldBe 1
    }

    it("does nothing when no hold has run out") {
      val consultations = FakeConsultations()
      HoldSweeper(FakeDb(), FakeStore(), consultations, () => Instant.EPOCH, Log.silent).sweep() shouldBe 0
      consultations.reopened shouldBe empty
    }
  }
