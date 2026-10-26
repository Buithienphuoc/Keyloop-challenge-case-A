package scheduler.unit

import java.time.{Instant, LocalDate, LocalTime}

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scheduler.modules.identity.Role
import scheduler.modules.scheduling.application.*
import scheduler.modules.scheduling.domain.*
import scheduler.shared.{AppError, Log}
import scheduler.support.Fakes.*

/** The workshop controller's tools: day load and moving a job to another bay or technician. */
class DispatchServiceSpec extends AnyFunSpec with Matchers:
  private val Now = Instant.parse("2026-11-02T00:00:00Z")
  // Tuesday 3 Nov 2026, 09:00-10:00 in Ho Chi Minh City.
  private val JobStart = Instant.parse("2026-11-03T02:00:00Z")
  private val controller = auth(Role.WORKSHOP_CONTROLLER)

  private val stand1 = Bay("b1", "Stand 1", "MOTORBIKE_STAND")
  private val stand2 = Bay("b2", "Stand 2", "MOTORBIKE_STAND")
  private val lift = Bay("b3", "Lift", "CAR_LIFT")
  private def tech(id: String, skills: Seq[String] = Seq("GENERAL_SERVICE"), from: Int = 8, to: Int = 17) =
    Technician(id, s"Tech $id", skills, LocalTime.of(from, 0), LocalTime.of(to, 0), Set(1, 2, 3, 4, 5, 6))

  private val requirement = Requirement(60, "GENERAL_SERVICE", "MOTORBIKE_STAND")

  private final class Fixture(status: AppointmentStatus = AppointmentStatus.CONFIRMED, technicians: Seq[Technician] =
      Seq(tech("t1"), tech("t2"), tech("t3", skills = Seq("ENGINE")), tech("t4", from = 13))):
    val store = FakeStore(bays = Seq(stand1, stand2, lift), technicians = technicians)
    store.add(LockedAppointment("a1", status, JobStart, "d1", "u1", "t1", None))
    store.jobs("a1") = JobDetails("a1", "d1", status, JobStart, JobStart.plusSeconds(3600), "b1", "t1", requirement)
    val consultations = FakeConsultations()
    val service = DispatchService(FakeDb(), store, store, store, consultations, WorkshopContext(FakeCatalog(), consultations), () => Now)

    def move(bay: Option[String] = None, technician: Option[String] = None, reason: Option[String] = None) =
      service.reassign(controller, "a1", Reassignment(bay, technician, reason), Log.silent)

  private def codeOf(f: => Any): String = (the[AppError] thrownBy f).code

  describe("reassign") {
    it("moves a job to another bay and records what changed") {
      val f = Fixture()
      f.move(bay = Some("b2"), reason = Some("lift broken"))
      f.store.reassigned.toSeq shouldBe Seq(("a1", "b2", "t1"))
      f.store.events.head.note shouldBe Some("Moved: bay Stand 1 → Stand 2; reason: lift broken")
      f.store.events.head.from shouldBe Some(AppointmentStatus.CONFIRMED)
      f.store.events.head.to shouldBe AppointmentStatus.CONFIRMED
    }

    it("moves a job to another technician and keeps the bay") {
      val f = Fixture()
      f.move(technician = Some("t2"))
      f.store.reassigned.toSeq shouldBe Seq(("a1", "b1", "t2"))
      f.store.events.head.note shouldBe Some("Moved: technician Tech t1 → Tech t2")
    }

    it("first releases expired holds around the job and reopens their consultations") {
      val f = Fixture()
      f.store.expiredHolds = Seq(ExpiredHold("h1", Some("c9")), ExpiredHold("h2", None))
      f.move(technician = Some("t2"))
      f.store.holdScopes.toSeq shouldBe Seq(HoldScope.Around("d1", None, JobStart, JobStart.plusSeconds(3600)))
      f.consultations.reopened.toSeq shouldBe Seq("c9")
    }

    it("only moves confirmed jobs") {
      codeOf(Fixture(status = AppointmentStatus.IN_PROGRESS).move(bay = Some("b2"))) shouldBe "NOT_REASSIGNABLE"
    }

    it("refuses a move that changes nothing") {
      codeOf(Fixture().move(bay = Some("b1"), technician = Some("t1"))) shouldBe "NOTHING_TO_CHANGE"
    }

    it("checks the new bay exists and is the right type") {
      codeOf(Fixture().move(bay = Some("nope"))) shouldBe "UNKNOWN_BAY"
      codeOf(Fixture().move(bay = Some("b3"))) shouldBe "BAY_TYPE_MISMATCH"
    }

    it("checks the new technician exists, is qualified and is on shift for the whole job") {
      codeOf(Fixture().move(technician = Some("nope"))) shouldBe "UNKNOWN_TECHNICIAN"
      codeOf(Fixture().move(technician = Some("t3"))) shouldBe "TECHNICIAN_NOT_QUALIFIED"
      codeOf(Fixture().move(technician = Some("t4"))) shouldBe "TECHNICIAN_NOT_ON_SHIFT"
    }

    it("answers RESOURCE_BUSY when the database finds an overlap") {
      val f = Fixture()
      f.store.reassignFails = true
      codeOf(f.move(bay = Some("b2"))) shouldBe "RESOURCE_BUSY"
      f.store.events shouldBe empty
    }

    it("keeps a controller to their own dealership") {
      val f = Fixture()
      f.store.add(LockedAppointment("a1", AppointmentStatus.CONFIRMED, JobStart, "d2", "u1", "t1", None))
      (the[AppError] thrownBy f.move(bay = Some("b2"))).status shouldBe 403
    }

    it("answers 404 for an unknown appointment") {
      val f = Fixture()
      (the[AppError] thrownBy f.service.reassign(controller, "zzz", Reassignment(Some("b2"), None, None), Log.silent)).status shouldBe 404
    }
  }

  describe("workload") {
    it("loads the busy periods of the local day and summarises them") {
      val f = Fixture()
      f.store.busy = Seq(BusyPeriod("b1", "t1", JobStart.toEpochMilli, JobStart.plusSeconds(3600).toEpochMilli))
      val view = f.service.workload(controller, LocalDate.parse("2026-11-03"))
      view.timezone shouldBe "Asia/Ho_Chi_Minh"
      f.store.busyQueries.toSeq shouldBe Seq(Instant.parse("2026-11-02T17:00:00Z") -> Instant.parse("2026-11-03T17:00:00Z"))
      view.load.bays.find(_.id == "b1").map(_.bookedMinutes) shouldBe Some(60)
      view.load.technicians.find(_.id == "t1").map(_.jobs) shouldBe Some(1)
    }

    it("needs an account attached to a dealership") {
      val f = Fixture()
      (the[AppError] thrownBy f.service.workload(auth(Role.SYSTEM_MANAGER, dealershipId = None), LocalDate.parse("2026-11-03"))).status shouldBe 403
    }
  }
