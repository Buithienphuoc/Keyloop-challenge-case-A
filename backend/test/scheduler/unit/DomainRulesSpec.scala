package scheduler.unit

import java.time.{Instant, LocalDate, LocalTime, ZoneId}

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scheduler.modules.identity.Role
import scheduler.modules.parts.{PartOrderLifecycle, PartOrderLine, PartOrderStatus, PartOrderView}
import scheduler.modules.parts.PartOrderStatus.*
import scheduler.modules.scheduling.domain.*
import scheduler.shared.Ids

/** Small pure rules not covered elsewhere: bay compatibility, parts order lifecycle, load figures, references. */
class DomainRulesSpec extends AnyFunSpec with Matchers:

  describe("Compatibility") {
    it("matches bay types to the vehicles they can take") {
      Compatibility.bayFitsVehicle("MOTORBIKE_STAND", VehicleType.MOTORBIKE) shouldBe true
      Compatibility.bayFitsVehicle("MOTORBIKE_STAND", VehicleType.CAR) shouldBe false
      Compatibility.bayFitsVehicle("CAR_LIFT", VehicleType.CAR) shouldBe true
      Compatibility.bayFitsVehicle("CAR_LIFT", VehicleType.MOTORBIKE) shouldBe false
      for v <- VehicleType.values do Compatibility.bayFitsVehicle("DIAGNOSTIC_BAY", v) shouldBe true
    }

    it("refuses unknown bay types") {
      Compatibility.bayFitsVehicle("SPACESHIP_DOCK", VehicleType.CAR) shouldBe false
      Compatibility.bayFitsVehicle("car_lift", VehicleType.CAR) shouldBe false
    }
  }

  describe("parts order lifecycle") {
    it("allows exactly the documented transitions") {
      val allowed = for
        from <- PartOrderStatus.values.toSeq
        to <- PartOrderStatus.values.toSeq
        if PartOrderLifecycle.canTransition(from, to)
      yield from -> to
      allowed should contain theSameElementsAs Seq(
        REQUESTED -> ORDERED, REQUESTED -> ISSUED, REQUESTED -> CANCELLED,
        ORDERED -> RECEIVED, ORDERED -> CANCELLED,
        RECEIVED -> ISSUED
      )
    }

    it("moves stock only on delivery and issue") {
      PartOrderStatus.values.map(s => s -> PartOrderLifecycle.stockEffect(s)).toMap shouldBe
        Map(REQUESTED -> 0, ORDERED -> 0, RECEIVED -> 1, ISSUED -> -1, CANCELLED -> 0)
    }

    it("totals an order line by line") {
      val lines = Seq(PartOrderLine("p1", "A", "A", 2, 150, 0), PartOrderLine("p2", "B", "B", 3, 10, 0))
      PartOrderView("o", "PO-1", REQUESTED, None, None, "x", Instant.EPOCH, Instant.EPOCH, lines).total shouldBe 330
      PartOrderView("o", "PO-1", REQUESTED, None, None, "x", Instant.EPOCH, Instant.EPOCH, Nil).total shouldBe 0
    }
  }

  describe("appointment lifecycle, exhaustively") {
    import AppointmentStatus.*
    import Lifecycle.*

    it("allows exactly the documented transitions") {
      val allowed = for
        from <- AppointmentStatus.values.toSeq
        to <- AppointmentStatus.values.toSeq
        if canTransition(from, to)
      yield from -> to
      allowed should contain theSameElementsAs Seq(
        HELD -> CONFIRMED, HELD -> CANCELLED, HELD -> EXPIRED,
        CONFIRMED -> IN_PROGRESS, CONFIRMED -> CANCELLED, CONFIRMED -> NO_SHOW,
        IN_PROGRESS -> AWAITING_PAYMENT, AWAITING_PAYMENT -> COMPLETED
      )
    }

    it("never lets a person expire a hold: only the system does that") {
      for role <- Role.values do roleMayMoveTo(role, EXPIRED) shouldBe false
    }

    it("gives the workshop controller, parts staff and system manager no lifecycle actions") {
      for role <- Seq(Role.WORKSHOP_CONTROLLER, Role.PARTS_STAFF, Role.SYSTEM_MANAGER); to <- AppointmentStatus.values do
        roleMayMoveTo(role, to) shouldBe false
    }

    it("lets staff cancel after the start time (only customers are held to it)") {
      checkTransition(CONFIRMED, CANCELLED, Role.SERVICE_ADVISOR, 1000L, 5000L) shouldBe None
    }

    it("treats every terminal status as final") {
      for from <- Seq(COMPLETED, CANCELLED, NO_SHOW, EXPIRED); to <- AppointmentStatus.values do canTransition(from, to) shouldBe false
    }

    it("keeps bay, technician and vehicle only while active") {
      AppointmentStatus.Active shouldBe Set(HELD, CONFIRMED, IN_PROGRESS)
    }
  }

  describe("Workload") {
    val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    val rules = WorkshopRules(zone, LocalTime.of(8, 0), LocalTime.of(17, 0), Set(1, 2, 3, 4, 5, 6), 30, 0, 30)
    val tuesday = LocalDate.parse("2026-11-03")
    def ms(date: LocalDate, h: Int, m: Int = 0) = date.atTime(h, m).atZone(zone).toInstant.toEpochMilli
    val bay = Bay("b1", "Stand", "MOTORBIKE_STAND")
    def tech(from: Int, to: Int, days: Set[Int] = Set(2)) =
      Technician("t1", "T", Seq("X"), LocalTime.of(from, 0), LocalTime.of(to, 0), days)

    it("rounds utilisation and treats a day off as 0%") {
      ResourceLoad("x", "x", Nil, 480, 160, 2).utilisation shouldBe 33
      ResourceLoad("x", "x", Nil, 480, 240, 2).utilisation shouldBe 50
      ResourceLoad("x", "x", Nil, 0, 60, 1).utilisation shouldBe 0
    }

    it("clips a technician's shift to opening hours") {
      Workload.forDay(rules, tuesday, Nil, Seq(tech(6, 20)), Nil).technicians.head.availableMinutes shouldBe 9 * 60
      Workload.forDay(rules, tuesday, Nil, Seq(tech(12, 20)), Nil).technicians.head.availableMinutes shouldBe 5 * 60
      Workload.forDay(rules, tuesday, Nil, Seq(tech(18, 20)), Nil).technicians.head.availableMinutes shouldBe 0
      Workload.forDay(rules, tuesday, Nil, Seq(tech(8, 17, days = Set(1))), Nil).technicians.head.availableMinutes shouldBe 0
    }

    it("counts only the part of a booking that falls on the day") {
      val overnight = BusyPeriod("b1", "t1", ms(tuesday.minusDays(1), 23), ms(tuesday, 1))
      val load = Workload.forDay(rules, tuesday, Seq(bay), Nil, Seq(overnight)).bays.head
      (load.bookedMinutes, load.jobs) shouldBe (60, 1)
    }

    it("gives bays no available time on a closed day") {
      val sunday = LocalDate.parse("2026-11-08")
      Workload.forDay(rules, sunday, Seq(bay), Nil, Nil).bays.head.availableMinutes shouldBe 0
    }
  }

  describe("Availability edge cases") {
    val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    val rules = WorkshopRules(zone, LocalTime.of(8, 0), LocalTime.of(17, 0), Set(1, 2, 3, 4, 5, 6), 30, 0, 30)

    it("offers no start time when the job is longer than the opening hours") {
      Availability.candidateStarts(rules, LocalDate.parse("2026-11-03"), 10 * 60) shouldBe empty
      Availability.candidateStarts(rules, LocalDate.parse("2026-11-03"), 9 * 60).map(_.toLocalTime) shouldBe Seq(LocalTime.of(8, 0))
    }

    it("treats touching intervals as free (half-open ranges)") {
      Availability.overlaps(0, 10, 10, 20) shouldBe false
      Availability.overlaps(0, 11, 10, 20) shouldBe true
      Availability.overlaps(5, 6, 0, 20) shouldBe true
    }

    it("caps the date strip at the booking horizon") {
      val now = LocalDate.parse("2026-11-02").atTime(9, 0).atZone(zone).toInstant // Monday
      val shortHorizon = rules.copy(maxDaysAhead = 2)
      Availability.bookableDates(shortHorizon, now, 21).map(_.toString) shouldBe Seq("2026-11-02", "2026-11-03", "2026-11-04")
    }
  }

  describe("Ids.humanReference") {
    it("is the prefix and six characters without look-alikes") {
      val refs = Seq.fill(200)(Ids.humanReference("SV"))
      all(refs) should fullyMatch regex "SV-[2-9A-HJKMNP-Z]{6}"
      refs.distinct.size should be > 190
      Ids.humanReference("PO", length = 3) should fullyMatch regex "PO-.{3}"
    }
  }
