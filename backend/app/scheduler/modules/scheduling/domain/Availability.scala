package scheduler.modules.scheduling.domain

import java.text.Collator
import java.time.{Instant, LocalDate, LocalTime, OffsetDateTime, ZonedDateTime}
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pure scheduling logic. No database, no clock: everything it needs is passed in,
 * which is what makes it cheap to test exhaustively (see AvailabilitySpec and
 * AvailabilityPropertySpec).
 *
 * All intervals are half-open: [start, end). A job ending at 10:00 and one
 * starting at 10:00 do not overlap. PostgreSQL's tstzrange uses the same rule,
 * so this code and the database constraints agree.
 */
object Availability:

  def overlaps(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean = aStart < bEnd && bStart < aEnd

  private def minutesOfDay(t: LocalTime): Int = t.getHour * 60 + t.getMinute
  private def localMinutes(z: ZonedDateTime): Int = z.getHour * 60 + z.getMinute
  private def millis(z: ZonedDateTime): Long = z.toInstant.toEpochMilli
  private val names = Collator.getInstance(Locale.ROOT)

  /** Start times on the slot grid for a local date at which a job of `durationMinutes` fits inside opening hours. */
  def candidateStarts(rules: WorkshopRules, date: LocalDate, durationMinutes: Int): Seq[ZonedDateTime] =
    if !rules.openDays.contains(date.getDayOfWeek.getValue) then Seq.empty
    else
      val open = minutesOfDay(rules.openingTime)
      val close = minutesOfDay(rules.closingTime)
      // Set the local wall-clock time rather than adding minutes to midnight: on a
      // daylight-saving day, "midnight + 480 min" is 09:00, not 08:00.
      Iterator.iterate(open)(_ + rules.slotMinutes)
        .takeWhile(m => m + durationMinutes <= close)
        .map(m => date.atTime(m / 60, m % 60).atZone(rules.timezone))
        .toSeq

  /** The parts of a job's time window that shift checks need, computed once per start time. */
  private final case class JobWindow(weekday: Int, startMin: Int, endMin: Int, sameDay: Boolean)

  private def jobWindow(start: ZonedDateTime, end: ZonedDateTime): JobWindow =
    JobWindow(
      weekday = start.getDayOfWeek.getValue,
      startMin = localMinutes(start),
      endMin = localMinutes(end),
      sameDay = start.toLocalDate == end.minusNanos(1).toLocalDate // jobs do not span midnight
    )

  private def fitsShift(tech: Technician, w: JobWindow): Boolean =
    w.sameDay &&
      tech.workDays.contains(w.weekday) &&
      w.startMin >= minutesOfDay(tech.shiftStart) &&
      w.endMin <= minutesOfDay(tech.shiftEnd)

  /** Is the technician rostered for the whole of [start, end) on that local day? */
  def isOnShift(tech: Technician, start: ZonedDateTime, end: ZonedDateTime): Boolean = fitsShift(tech, jobWindow(start, end))

  final case class AllocationResult(allocation: Option[Allocation], freeBays: Seq[Bay], freeTechnicians: Seq[Technician])

  /**
   * Finds a bay of the right type and a qualified, rostered technician that are both
   * free for the whole job.
   *
   * Choice when several fit ("best fit"):
   *  - technician: the one with the FEWEST skills first, so specialists stay free for
   *    work only they can do; ties go to whoever has the least booked time that day.
   *  - bay: the first free one by name (bays of one type are interchangeable).
   */
  def allocate(
      requirement: Requirement,
      start: ZonedDateTime,
      bays: Seq[Bay],
      technicians: Seq[Technician],
      busy: Seq[BusyPeriod]
  ): AllocationResult =
    val end = start.plusMinutes(requirement.durationMinutes)
    val (s, e) = (millis(start), millis(end))

    val freeBays = bays
      .filter(_.bayType == requirement.bayType)
      .filterNot(b => busy.exists(p => p.bayId == b.id && overlaps(s, e, p.startMs, p.endMs)))
      .sortWith((a, b) => names.compare(a.name, b.name) < 0)

    val window = jobWindow(start, end)
    val dayStart = millis(start.toLocalDate.atStartOfDay(start.getZone))
    val dayEnd = millis(start.toLocalDate.plusDays(1).atStartOfDay(start.getZone))
    val candidates = technicians
      .filter(_.skills.contains(requirement.requiredSkill))
      .filter(fitsShift(_, window))
      .filterNot(t => busy.exists(p => p.technicianId == t.id && overlaps(s, e, p.startMs, p.endMs)))
    // Booked minutes that day, computed once per candidate (not inside the sort comparator).
    val booked: Map[String, Long] = candidates.map { t =>
      t.id -> busy
        .filter(p => p.technicianId == t.id && overlaps(dayStart, dayEnd, p.startMs, p.endMs))
        .map(p => (p.endMs - p.startMs) / 60_000)
        .sum
    }.toMap
    val freeTechnicians = candidates.sortWith { (a, b) =>
      if a.skills.size != b.skills.size then a.skills.size < b.skills.size
      else if booked(a.id) != booked(b.id) then booked(a.id) < booked(b.id)
      else names.compare(a.name, b.name) < 0
    }

    val allocation = for bay <- freeBays.headOption; tech <- freeTechnicians.headOption yield Allocation(bay, tech)
    AllocationResult(allocation, freeBays, freeTechnicians)

  /** Every start time of the day with whether it can be booked and how many more jobs fit. */
  def daySlots(
      rules: WorkshopRules,
      date: LocalDate,
      requirement: Requirement,
      bays: Seq[Bay],
      technicians: Seq[Technician],
      busy: Seq[BusyPeriod],
      now: Instant
  ): Seq[Slot] =
    val earliest = now.plusSeconds(rules.minLeadMinutes * 60L).toEpochMilli
    val lastDay = lastBookableDay(rules, now)
    candidateStarts(rules, date, requirement.durationMinutes).map { start =>
      val end = start.plusMinutes(requirement.durationMinutes)
      def unavailable(reason: SlotUnavailable) = Slot(start, end, available = false, capacity = 0, reason = Some(reason))
      if millis(start) < earliest then unavailable(SlotUnavailable.PAST_OR_TOO_SOON)
      // Same rule as validateStart: never offer a time that booking would then refuse.
      else if start.toLocalDate.isAfter(lastDay) then unavailable(SlotUnavailable.TOO_FAR_AHEAD)
      else
        val r = allocate(requirement, start, bays, technicians, busy)
        val capacity = math.min(r.freeBays.size, r.freeTechnicians.size)
        if capacity > 0 then Slot(start, end, available = true, capacity = capacity, reason = None)
        else unavailable(SlotUnavailable.FULLY_BOOKED)
    }

  enum StartValidationError:
    case INVALID_START, CLOSED_DAY, OUTSIDE_OPENING_HOURS, NOT_ON_SLOT_GRID, TOO_SOON, TOO_FAR_AHEAD

  /**
   * Checks a requested start time against the workshop rules (not against availability).
   * Returns the start converted to the dealership's zone, or the reason it is invalid.
   */
  def validateStart(
      rules: WorkshopRules,
      requirement: Requirement,
      startIso: String,
      now: Instant
  ): Either[StartValidationError, ZonedDateTime] =
    import StartValidationError.*
    val parsed =
      try Some(OffsetDateTime.parse(startIso))
      catch case _: DateTimeParseException => None
    parsed match
      case None => Left(INVALID_START)
      case Some(odt) =>
        val start = odt.atZoneSameInstant(rules.timezone)
        val startMin = localMinutes(start)
        val open = minutesOfDay(rules.openingTime)
        val close = minutesOfDay(rules.closingTime)
        if start.getSecond != 0 || start.getNano != 0 then Left(NOT_ON_SLOT_GRID)
        else if !rules.openDays.contains(start.getDayOfWeek.getValue) then Left(CLOSED_DAY)
        else if startMin < open || startMin + requirement.durationMinutes > close then Left(OUTSIDE_OPENING_HOURS)
        else if (startMin - open) % rules.slotMinutes != 0 then Left(NOT_ON_SLOT_GRID)
        else if millis(start) < now.plusSeconds(rules.minLeadMinutes * 60L).toEpochMilli then Left(TOO_SOON)
        else if start.toLocalDate.isAfter(lastBookableDay(rules, now)) then Left(TOO_FAR_AHEAD)
        else Right(start)

  /** The last local day that can be booked (today + maxDaysAhead), in the dealership's zone. */
  private def lastBookableDay(rules: WorkshopRules, now: Instant): LocalDate =
    now.atZone(rules.timezone).toLocalDate.plusDays(rules.maxDaysAhead)

  /** Bookable local dates from today, for the date strip in the UI. */
  def bookableDates(rules: WorkshopRules, now: Instant, days: Int): Seq[LocalDate] =
    val today = now.atZone(rules.timezone).toLocalDate
    (0 until math.min(days, rules.maxDaysAhead + 1))
      .map(i => today.plusDays(i))
      .filter(d => rules.openDays.contains(d.getDayOfWeek.getValue))
