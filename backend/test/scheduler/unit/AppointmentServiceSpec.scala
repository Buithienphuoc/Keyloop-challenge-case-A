package scheduler.unit

import java.time.{Instant, LocalDate}

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scheduler.modules.identity.Role
import scheduler.modules.scheduling.application.*
import scheduler.modules.scheduling.domain.AppointmentStatus
import scheduler.modules.scheduling.domain.AppointmentStatus.*
import scheduler.shared.{AppError, Log}
import scheduler.support.Fakes.*

/** AppointmentService against in-memory ports: who may see and move which appointment, and holds. */
class AppointmentServiceSpec extends AnyFunSpec with Matchers:
  private val Now = Instant.parse("2026-11-02T02:00:00Z")
  private val Later = Now.plusSeconds(3600 * 24)

  private def locked(status: AppointmentStatus, start: Instant = Later, customerId: String = "u1", dealershipId: String = "d1",
      technicianId: String = "t1", consultation: Option[String] = None, holdExpiresAt: Option[Instant] = None) =
    LockedAppointment("a1", status, start, dealershipId, customerId, technicianId, consultation, holdExpiresAt)

  private final class Fixture(row: LockedAppointment, technicianUsers: Map[String, String] = Map("tu1" -> "t1")):
    val store = FakeStore(technicianUsers = technicianUsers).add(row)
    val consultations = FakeConsultations()
    val db = FakeDb()
    val service = AppointmentService(db, store, store, consultations, WorkshopContext(FakeCatalog(), consultations), () => Now)

  private def codeOf(f: => Any): String = (the[AppError] thrownBy f).code
  private def statusOf(f: => Any): Int = (the[AppError] thrownBy f).status

  describe("get") {
    it("shows customers only their own appointments, as 404 so ids are not confirmed") {
      val f = Fixture(locked(CONFIRMED, customerId = "someone-else"))
      statusOf(f.service.get(customer, "a1")) shouldBe 404
    }

    it("shows staff only appointments of their own dealership") {
      val f = Fixture(locked(CONFIRMED, dealershipId = "d2"))
      statusOf(f.service.get(auth(Role.SERVICE_ADVISOR), "a1")) shouldBe 403
      f.service.get(auth(Role.SYSTEM_MANAGER, dealershipId = None), "a1").id shouldBe "a1"
    }

    it("shows a technician only the jobs assigned to them") {
      val f = Fixture(locked(CONFIRMED, technicianId = "t2"))
      statusOf(f.service.get(auth(Role.TECHNICIAN, userId = "tu1"), "a1")) shouldBe 404
      val mine = Fixture(locked(CONFIRMED))
      mine.service.get(auth(Role.TECHNICIAN, userId = "tu1"), "a1").id shouldBe "a1"
    }

    it("answers 404 for an unknown id") {
      val f = Fixture(locked(CONFIRMED))
      statusOf(f.service.get(customer, "nope")) shouldBe 404
    }
  }

  describe("listing") {
    it("lists a dealership day as [local midnight, next local midnight)") {
      val f = Fixture(locked(CONFIRMED))
      f.service.listForDealershipDay(auth(Role.SERVICE_ADVISOR), LocalDate.parse("2026-11-03"))
      val filter = f.store.lastFilter.get
      filter.dealershipId shouldBe Some("d1")
      filter.from shouldBe Some(Instant.parse("2026-11-02T17:00:00Z")) // 00:00 in UTC+7
      filter.to shouldBe Some(Instant.parse("2026-11-03T17:00:00Z"))
    }

    it("refuses a dealership day to an account without a dealership") {
      val f = Fixture(locked(CONFIRMED))
      statusOf(f.service.listForDealershipDay(auth(Role.SYSTEM_MANAGER, dealershipId = None), LocalDate.parse("2026-11-03"))) shouldBe 403
    }

    it("lists a technician's own day, and refuses a login with no technician profile") {
      val f = Fixture(locked(CONFIRMED))
      f.service.listTechnicianDay(auth(Role.TECHNICIAN, userId = "tu1"), LocalDate.parse("2026-11-03"))
      f.store.lastFilter.get.technicianId shouldBe Some("t1")
      statusOf(f.service.listTechnicianDay(auth(Role.TECHNICIAN, userId = "nobody"), LocalDate.parse("2026-11-03"))) shouldBe 403
    }

    it("lists a customer's own appointments only") {
      val f = Fixture(locked(CONFIRMED))
      f.service.listMine(customer)
      f.store.lastFilter shouldBe Some(AppointmentFilter(customerId = Some("u1")))
    }
  }

  describe("changeStatus") {
    it("cancels, records the event with its reason and reopens the consultation request") {
      val f = Fixture(locked(CONFIRMED, consultation = Some("c1")))
      f.service.changeStatus(customer, "a1", CANCELLED, Some("Sick"), Log.silent).status shouldBe CANCELLED
      f.store.statusChanges.toSeq shouldBe Seq(("a1", CANCELLED, Some("Sick")))
      f.store.events.map(e => (e.from, e.to, e.note)).toSeq shouldBe Seq((Some(CONFIRMED), CANCELLED, Some("Sick")))
      f.consultations.reopened.toSeq shouldBe Seq("c1")
    }

    it("stores a cancel reason only for cancellations") {
      val f = Fixture(locked(CONFIRMED, start = Now.minusSeconds(60)))
      f.service.changeStatus(auth(Role.SERVICE_ADVISOR), "a1", IN_PROGRESS, Some("ignored"), Log.silent)
      f.store.statusChanges.toSeq shouldBe Seq(("a1", IN_PROGRESS, None))
      f.consultations.reopened shouldBe empty
    }

    it("hides other customers' appointments behind a 404") {
      val f = Fixture(locked(CONFIRMED, customerId = "other"))
      statusOf(f.service.changeStatus(customer, "a1", CANCELLED, None, Log.silent)) shouldBe 404
      f.store.statusChanges shouldBe empty
    }

    it("keeps staff to their own dealership") {
      val f = Fixture(locked(CONFIRMED, dealershipId = "d2"))
      statusOf(f.service.changeStatus(auth(Role.SERVICE_ADVISOR), "a1", CANCELLED, None, Log.silent)) shouldBe 403
    }

    it("lets a technician start only their own job") {
      val other = Fixture(locked(CONFIRMED, technicianId = "t2"))
      statusOf(other.service.changeStatus(auth(Role.TECHNICIAN, userId = "tu1"), "a1", IN_PROGRESS, None, Log.silent)) shouldBe 403
      val mine = Fixture(locked(CONFIRMED))
      mine.service.changeStatus(auth(Role.TECHNICIAN, userId = "tu1"), "a1", IN_PROGRESS, None, Log.silent).status shouldBe IN_PROGRESS
    }

    it("maps every lifecycle rule to its API error") {
      codeOf(Fixture(locked(COMPLETED)).service.changeStatus(auth(Role.SERVICE_ADVISOR), "a1", CANCELLED, None, Log.silent)) shouldBe
        "INVALID_STATUS_TRANSITION"
      statusOf(Fixture(locked(CONFIRMED)).service.changeStatus(customer, "a1", IN_PROGRESS, None, Log.silent)) shouldBe 403
      codeOf(Fixture(locked(CONFIRMED, start = Now)).service.changeStatus(customer, "a1", CANCELLED, None, Log.silent)) shouldBe
        "TOO_LATE_TO_CANCEL"
      codeOf(Fixture(locked(CONFIRMED)).service.changeStatus(auth(Role.SERVICE_ADVISOR), "a1", NO_SHOW, None, Log.silent)) shouldBe
        "NOT_STARTED_YET"
    }

    it("changes nothing when a rule is broken") {
      val f = Fixture(locked(COMPLETED))
      an[AppError] should be thrownBy f.service.changeStatus(auth(Role.ADMIN), "a1", CANCELLED, None, Log.silent)
      f.store.statusChanges shouldBe empty
      f.store.events shouldBe empty
    }

    it("lets a customer release their own hold") {
      val f = Fixture(locked(HELD, holdExpiresAt = Some(Now.plusSeconds(300))))
      f.service.changeStatus(customer, "a1", CANCELLED, None, Log.silent).status shouldBe CANCELLED
    }
  }

  describe("confirmHold") {
    it("confirms a running hold and records HELD → CONFIRMED") {
      val f = Fixture(locked(HELD, holdExpiresAt = Some(Now.plusSeconds(1))))
      f.service.confirmHold(customer, "a1", Log.silent).status shouldBe CONFIRMED
      f.store.events.map(e => (e.from, e.to)).toSeq shouldBe Seq((Some(HELD), CONFIRMED))
    }

    it("answers with the same booking when confirmed twice") {
      val f = Fixture(locked(CONFIRMED))
      f.service.confirmHold(customer, "a1", Log.silent).status shouldBe CONFIRMED
      f.store.statusChanges shouldBe empty
    }

    it("releases a hold that ran out (expiry is exclusive) and reopens its consultation") {
      val f = Fixture(locked(HELD, holdExpiresAt = Some(Now), consultation = Some("c1")))
      codeOf(f.service.confirmHold(customer, "a1", Log.silent)) shouldBe "HOLD_EXPIRED"
      f.store.statusChanges.map(_._2).toSeq shouldBe Seq(EXPIRED)
      f.consultations.reopened.toSeq shouldBe Seq("c1")
    }

    it("treats a hold without an expiry as expired") {
      val f = Fixture(locked(HELD, holdExpiresAt = None))
      codeOf(f.service.confirmHold(customer, "a1", Log.silent)) shouldBe "HOLD_EXPIRED"
    }

    it("answers HOLD_EXPIRED again for an already expired hold, without writing") {
      val f = Fixture(locked(EXPIRED))
      codeOf(f.service.confirmHold(customer, "a1", Log.silent)) shouldBe "HOLD_EXPIRED"
      f.store.statusChanges shouldBe empty
    }

    it("refuses appointments that are not on hold") {
      codeOf(Fixture(locked(CANCELLED)).service.confirmHold(customer, "a1", Log.silent)) shouldBe "NOT_HELD"
    }

    it("hides other customers' holds behind a 404") {
      val f = Fixture(locked(HELD, customerId = "other", holdExpiresAt = Some(Later)))
      statusOf(f.service.confirmHold(customer, "a1", Log.silent)) shouldBe 404
    }
  }
