package scheduler.unit

import java.time.{LocalDate, LocalTime, ZoneId}

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scheduler.modules.scheduling.domain.*

class WorkloadSpec extends AnyFunSpec with Matchers:
  private val Zone = ZoneId.of("Asia/Ho_Chi_Minh")
  private val Rules = WorkshopRules(Zone, LocalTime.of(8, 0), LocalTime.of(17, 0), Set(1, 2, 3, 4, 5, 6), 30, 0, 60)
  private val Tue = LocalDate.of(2026, 11, 3)
  private val bays = Seq(Bay("b1", "Stand A", "MOTORBIKE_STAND"), Bay("b2", "Stand B", "MOTORBIKE_STAND"))
  private val techs = Seq(
    Technician("t1", "Minh", Seq("GENERAL_SERVICE"), LocalTime.of(7, 0), LocalTime.of(12, 0), Set(1, 2, 3, 4, 5)),
    Technician("t2", "Lan", Seq("GENERAL_SERVICE"), LocalTime.of(8, 0), LocalTime.of(17, 0), Set(6))
  )
  private def at(h: Int, m: Int = 0) = Tue.atTime(h, m).atZone(Zone).toInstant.toEpochMilli

  describe("workshop load for a day") {
    it("counts booked minutes against shift time inside opening hours") {
      val busy = Seq(BusyPeriod("b1", "t1", at(8), at(9, 30)), BusyPeriod("b1", "t1", at(10), at(11)))
      val load = Workload.forDay(Rules, Tue, bays, techs, busy)
      val minh = load.technicians.find(_.id == "t1").get
      minh.availableMinutes shouldBe 240 // shift 07:00–12:00, but the workshop opens at 08:00
      minh.bookedMinutes shouldBe 150
      minh.jobs shouldBe 2
      minh.utilisation shouldBe 63
      load.technicians.find(_.id == "t2").get.availableMinutes shouldBe 0 // Saturdays only
      load.bays.find(_.id == "b1").get.utilisation shouldBe 28 // 150 of 540 minutes
      load.bays.find(_.id == "b2").get.bookedMinutes shouldBe 0
    }
  }
