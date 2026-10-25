package scheduler.unit

import java.time.{LocalDate, LocalTime, ZoneId, ZonedDateTime}
import java.time.format.DateTimeFormatter

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scheduler.modules.scheduling.domain.*
import scheduler.modules.scheduling.domain.Availability.*
import scheduler.modules.scheduling.domain.Availability.StartValidationError.*
import scheduler.shared.JsonFormats

object AvailabilityFixtures:
  val Tz: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
  val rules: WorkshopRules = WorkshopRules(
    timezone = Tz,
    openingTime = LocalTime.of(8, 0),
    closingTime = LocalTime.of(17, 30),
    openDays = Set(1, 2, 3, 4, 5, 6),
    slotMinutes = 30,
    minLeadMinutes = 60,
    maxDaysAhead = 60
  )
  val Monday: LocalDate = LocalDate.parse("2026-10-26")
  def at(hhmm: String, date: LocalDate = Monday): ZonedDateTime = date.atTime(LocalTime.parse(hhmm)).atZone(Tz)
  val generalJob: Requirement = Requirement(durationMinutes = 60, requiredSkill = "GENERAL", bayType = "STAND")

  def stand(name: String): Bay = Bay(s"bay-$name", name, "STAND")
  def tech(
      name: String,
      skills: Seq[String],
      shiftStart: String = "08:00",
      shiftEnd: String = "17:30",
      workDays: Set[Int] = Set(1, 2, 3, 4, 5, 6)
  ): Technician = Technician(s"tech-$name", name, skills, LocalTime.parse(shiftStart), LocalTime.parse(shiftEnd), workDays)
  def busy(bay: Bay, t: Technician, from: String, to: String): BusyPeriod =
    BusyPeriod(bay.id, t.id, at(from).toInstant.toEpochMilli, at(to).toInstant.toEpochMilli)

class AvailabilitySpec extends AnyFunSpec with Matchers:
  import AvailabilityFixtures.*

  private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

  describe("overlaps (half-open intervals, same rule as PostgreSQL tstzrange)") {
    it("back-to-back jobs do not overlap") {
      overlaps(9, 10, 10, 11) shouldBe false
      overlaps(10, 11, 9, 10) shouldBe false
    }
    it("partial, identical and contained ranges overlap") {
      overlaps(90, 100, 95, 105) shouldBe true
      overlaps(9, 10, 9, 10) shouldBe true
      overlaps(8, 12, 9, 10) shouldBe true
    }
  }

  describe("candidateStarts") {
    it("offers every grid time at which the whole job fits before closing") {
      val starts = candidateStarts(rules, Monday, 60).map(_.format(hhmm))
      starts.head shouldBe "08:00"
      starts.last shouldBe "16:30" // 16:30 + 60 min = 17:30 = closing time
      starts should have length 18
    }
    it("offers nothing on a closed day") {
      candidateStarts(rules, LocalDate.parse("2026-10-25"), 60) shouldBe empty // Sunday
    }
    it("keeps local wall-clock times across a daylight-saving change") {
      val london = rules.copy(timezone = ZoneId.of("Europe/London"), openDays = (1 to 7).toSet)
      val springForward = candidateStarts(london, LocalDate.parse("2026-03-29"), 60)
      JsonFormats.offsetIso(springForward.head) shouldBe "2026-03-29T08:00:00.000+01:00"
      val fallBack = candidateStarts(london, LocalDate.parse("2026-10-25"), 60)
      JsonFormats.offsetIso(fallBack.head) shouldBe "2026-10-25T08:00:00.000+00:00"
      fallBack should have length 18
    }
  }

  describe("isOnShift") {
    val lateStarter = tech("Khoa", Seq("GENERAL"), shiftStart = "09:00", workDays = Set(2, 3, 4, 5, 6))
    it("requires the job to sit fully inside the shift on a working day") {
      isOnShift(lateStarter, at("10:00"), at("11:00")) shouldBe false // Monday is a day off
      val tue = LocalDate.parse("2026-10-27")
      isOnShift(lateStarter, at("08:30", tue), at("09:30", tue)) shouldBe false // starts before shift
      isOnShift(lateStarter, at("16:30", tue), at("17:30", tue)) shouldBe true // ends exactly at shift end
      isOnShift(lateStarter, at("17:00", tue), at("18:00", tue)) shouldBe false // runs past shift end
    }
  }

  describe("allocate") {
    val (a, b) = (stand("A"), stand("B"))
    val generalist = tech("Minh", Seq("GENERAL"))
    val specialist = tech("Hung", Seq("ENGINE", "ELECTRICAL", "GENERAL"))

    it("needs BOTH a free bay of the right type and a free qualified technician") {
      allocate(generalJob, at("09:00"), Seq(a), Seq(generalist), Nil).allocation shouldBe Some(Allocation(a, generalist))
      allocate(generalJob.copy(bayType = "CAR_LIFT"), at("09:00"), Seq(a), Seq(generalist), Nil).allocation shouldBe None
      allocate(generalJob.copy(requiredSkill = "ENGINE"), at("09:00"), Seq(a), Seq(generalist), Nil).allocation shouldBe None
    }

    it("a free bay is not enough when every qualified technician is busy (and vice versa)") {
      val techBusy = allocate(generalJob, at("09:00"), Seq(a, b), Seq(generalist), Seq(busy(a, generalist, "09:30", "10:30")))
      techBusy.freeBays shouldBe Seq(b)
      techBusy.allocation shouldBe None

      val bayBusy = allocate(generalJob, at("09:00"), Seq(a), Seq(generalist, specialist), Seq(busy(a, specialist, "08:30", "09:30")))
      bayBusy.allocation shouldBe None
    }

    it("keeps specialists free: picks the technician with the fewest skills first") {
      allocate(generalJob, at("09:00"), Seq(a, b), Seq(specialist, generalist), Nil).allocation.map(_.technician.name) shouldBe Some("Minh")
    }

    it("falls back to the specialist when the generalist is taken") {
      val r = allocate(generalJob, at("09:00"), Seq(a, b), Seq(specialist, generalist), Seq(busy(a, generalist, "09:00", "10:00")))
      r.allocation shouldBe Some(Allocation(b, specialist))
    }

    it("breaks ties by who has the least work booked that day") {
      val lan = tech("Lan", Seq("GENERAL"))
      val r = allocate(generalJob, at("14:00"), Seq(a, b), Seq(generalist, lan), Seq(busy(a, generalist, "08:00", "10:00")))
      r.allocation.map(_.technician.name) shouldBe Some("Lan")
    }

    it("allows a job to start exactly when the previous one ends") {
      allocate(generalJob, at("10:00"), Seq(a), Seq(generalist), Seq(busy(a, generalist, "09:00", "10:00"))).allocation should not be empty
    }
  }

  describe("daySlots") {
    val bays = Seq(stand("A"), stand("B"))
    val techs = Seq(tech("Minh", Seq("GENERAL")), tech("Lan", Seq("GENERAL")))
    def byTime(slots: Seq[Slot]) = slots.map(s => s.start.format(hhmm) -> s).toMap

    it("marks times before now + lead time as not bookable") {
      val slots = byTime(daySlots(rules, Monday, generalJob, bays, techs, Nil, at("08:10").toInstant))
      slots("09:00").available shouldBe false
      slots("09:00").reason shouldBe Some(SlotUnavailable.PAST_OR_TOO_SOON)
      slots("09:30").available shouldBe true
      slots("09:30").capacity shouldBe 2
    }

    it("reports capacity and fully booked times") {
      val slots = byTime(daySlots(rules, Monday, generalJob, bays, techs, Seq(busy(bays(0), techs(0), "10:00", "11:00")), at("07:00").toInstant))
      slots("09:00").capacity shouldBe 2
      slots("09:30").capacity shouldBe 1 // overlaps 10:00-11:00
      slots("11:00").capacity shouldBe 2
    }
  }

  describe("validateStart") {
    val now = at("07:00").toInstant
    def check(iso: String) = validateStart(rules, generalJob, iso, now).fold(_.toString, _ => "OK")

    it("accepts a grid time inside opening hours, given in any offset") {
      check("2026-10-26T09:00:00+07:00") shouldBe "OK"
      check("2026-10-26T02:00:00Z") shouldBe "OK" // same instant
    }
    it("rejects times that break workshop rules") {
      check("2026-10-25T09:00:00+07:00") shouldBe CLOSED_DAY.toString
      check("2026-10-26T07:30:00+07:00") shouldBe OUTSIDE_OPENING_HOURS.toString
      check("2026-10-26T17:00:00+07:00") shouldBe OUTSIDE_OPENING_HOURS.toString // would end at 18:00
      check("2026-10-26T09:15:00+07:00") shouldBe NOT_ON_SLOT_GRID.toString
      check("2026-10-26T09:00:30+07:00") shouldBe NOT_ON_SLOT_GRID.toString
      check("2026-10-26T07:59:00+07:00") shouldBe OUTSIDE_OPENING_HOURS.toString
      check("2027-02-01T09:00:00+07:00") shouldBe TOO_FAR_AHEAD.toString
      check("not a date") shouldBe INVALID_START.toString
    }
    it("enforces the minimum lead time") {
      validateStart(rules, generalJob, "2026-10-26T09:00:00+07:00", at("08:30").toInstant) shouldBe Left(TOO_SOON)
    }
  }

  describe("bookableDates") {
    it("lists open days starting today") {
      bookableDates(rules, at("10:00", LocalDate.parse("2026-10-24")).toInstant, 4).map(_.toString) shouldBe
        Seq("2026-10-24", "2026-10-26", "2026-10-27")
    }
  }
