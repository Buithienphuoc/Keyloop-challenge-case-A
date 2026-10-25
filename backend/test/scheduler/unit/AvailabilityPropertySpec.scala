package scheduler.unit

import java.time.{LocalDate, LocalTime, ZonedDateTime}

import org.scalacheck.Gen
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scheduler.modules.scheduling.domain.*
import scheduler.modules.scheduling.domain.Availability.*

/**
 * Property-based tests: instead of a handful of hand-picked cases, ScalaCheck generates
 * thousands of random workshops (bays, technicians, shifts) and random streams of booking
 * requests, and checks that the scheduling rules hold for every one of them. When a property
 * fails, ScalaCheck shrinks the input towards a small counter-example.
 */
class AvailabilityPropertySpec extends AnyFunSpec with Matchers with ScalaCheckPropertyChecks:
  import AvailabilityFixtures.Tz

  private val Date = LocalDate.parse("2026-10-27") // a Tuesday
  private val rules = AvailabilityFixtures.rules.copy(minLeadMinutes = 0)
  private val Skills = Seq("GENERAL", "ENGINE", "BRAKES")
  private val BayTypes = Seq("STAND", "LIFT")
  private def time(min: Int) = LocalTime.of(min / 60, min % 60)

  private final case class Workshop(bays: Seq[Bay], technicians: Seq[Technician])
  private final case class Request(requirement: Requirement, slotIndex: Int)
  private final case class Booked(period: BusyPeriod, requirement: Requirement, bay: Bay, tech: Technician, start: ZonedDateTime, end: ZonedDateTime)

  private def bayGen(i: Int) = Gen.oneOf(BayTypes).map(t => Bay(s"b$i", s"Bay $i", t))
  private def techGen(i: Int) =
    for
      skills <- Gen.atLeastOne(Skills)
      startMin <- Gen.choose(16, 22).map(_ * 30) // 08:00 … 11:00
      endMin <- Gen.choose(28, 35).map(_ * 30) // 14:00 … 17:30
      worksTuesday <- Gen.oneOf(true, false)
    yield Technician(s"t$i", s"Tech $i", skills.toSeq, time(startMin), time(endMin),
      if worksTuesday then Set(1, 2, 3, 4, 5) else Set(1, 3, 4, 5))

  private val workshopGen =
    for
      nb <- Gen.choose(1, 4)
      nt <- Gen.choose(1, 5)
      bays <- Gen.sequence[Seq[Bay], Bay]((0 until nb).map(bayGen))
      techs <- Gen.sequence[Seq[Technician], Technician]((0 until nt).map(techGen))
    yield Workshop(bays, techs)

  private val requirementGen =
    for
      duration <- Gen.oneOf(30, 60, 90, 120, 180)
      skill <- Gen.oneOf(Skills)
      bayType <- Gen.oneOf(BayTypes)
    yield Requirement(duration, skill, bayType)

  private val requestGen = for r <- requirementGen; i <- Gen.choose(0, 30) yield Request(r, i)
  private def requestsGen(max: Int) = Gen.choose(0, max).flatMap(Gen.listOfN(_, requestGen))

  /** Replays a stream of requests against the pure allocator, as the booking service does. */
  private def simulate(w: Workshop, requests: Seq[Request]): Vector[Booked] =
    requests.foldLeft(Vector.empty[Booked]) { (booked, req) =>
      val starts = candidateStarts(rules, Date, req.requirement.durationMinutes)
      val start = starts(req.slotIndex % starts.size)
      allocate(req.requirement, start, w.bays, w.technicians, booked.map(_.period)).allocation match
        case None => booked
        case Some(a) =>
          val end = start.plusMinutes(req.requirement.durationMinutes)
          val period = BusyPeriod(a.bay.id, a.technician.id, start.toInstant.toEpochMilli, end.toInstant.toEpochMilli)
          booked :+ Booked(period, req.requirement, a.bay, a.technician, start, end)
    }

  describe("scheduling invariants (property-based)") {
    it("never double-books a bay or a technician") {
      forAll(workshopGen, requestsGen(40), minSuccessful(500)) { (w, requests) =>
        val booked = simulate(w, requests)
        for
          i <- booked.indices
          j <- (i + 1) until booked.size
          x = booked(i).period
          y = booked(j).period
          if overlaps(x.startMs, x.endMs, y.startMs, y.endMs)
        do
          x.bayId should not be y.bayId
          x.technicianId should not be y.technicianId
      }
    }

    it("only assigns a qualified, rostered technician and a bay of the right type") {
      forAll(workshopGen, requestsGen(40), minSuccessful(500)) { (w, requests) =>
        for b <- simulate(w, requests) do
          b.tech.skills should contain(b.requirement.requiredSkill)
          b.bay.bayType shouldBe b.requirement.bayType
          isOnShift(b.tech, b.start, b.end) shouldBe true
      }
    }

    it("advertised capacity is honest: exactly that many more jobs fit at that time, and no more") {
      forAll(workshopGen, requestsGen(25), requirementGen, minSuccessful(200)) { (w, requests, probe) =>
        val booked = simulate(w, requests).map(_.period)
        val now = Date.atStartOfDay(Tz).toInstant
        for slot <- daySlots(rules, Date, probe, w.bays, w.technicians, booked, now) do
          val busy = (0 until slot.capacity).foldLeft(booked) { (busy, _) =>
            val a = allocate(probe, slot.start, w.bays, w.technicians, busy).allocation
            a should not be empty
            busy :+ BusyPeriod(a.get.bay.id, a.get.technician.id, slot.start.toInstant.toEpochMilli, slot.end.toInstant.toEpochMilli)
          }
          allocate(probe, slot.start, w.bays, w.technicians, busy).allocation shouldBe None
          slot.available shouldBe (slot.capacity > 0)
      }
    }
  }

  describe("the slot grid never offers a time that booking would refuse") {
    // Found while reviewing the code: daySlots ignored maxDaysAhead, so a far date (or a
    // "nearest alternative" 14 days out at a dealership that books only 7 ahead) looked free,
    // and the booking then failed with TOO_FAR_AHEAD.
    val customGen =
      for
        slotMinutes <- Gen.oneOf(15, 30, 60)
        minLead <- Gen.choose(0, 3 * 24 * 60)
        maxDaysAhead <- Gen.choose(1, 20)
        dayOffset <- Gen.choose(-2, 25)
        nowMinute <- Gen.choose(0, 24 * 60 - 1)
      yield (rules.copy(slotMinutes = slotMinutes, minLeadMinutes = minLead, maxDaysAhead = maxDaysAhead), dayOffset, nowMinute)

    it("every available slot passes validateStart, for random rules, dates and clocks") {
      forAll(workshopGen, requirementGen, customGen, minSuccessful(400)) { case (w, requirement, (custom, dayOffset, nowMinute)) =>
        val now = LocalDate.parse("2026-10-26").atStartOfDay(Tz).plusMinutes(nowMinute)
        val date = now.toLocalDate.plusDays(dayOffset)
        for slot <- daySlots(custom, date, requirement, w.bays, w.technicians, Nil, now.toInstant) if slot.available do
          validateStart(custom, requirement, slot.start.toOffsetDateTime.toString, now.toInstant).isRight shouldBe true
      }
    }
  }
