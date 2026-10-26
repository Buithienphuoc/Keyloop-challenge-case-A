package scheduler.modules.scheduling.domain

import java.time.{LocalDate, LocalTime}

/** How busy one bay or technician is on one day, in minutes. */
final case class ResourceLoad(
    id: String,
    name: String,
    /** Bay type for a bay, skills for a technician. */
    tags: Seq[String],
    availableMinutes: Int,
    bookedMinutes: Int,
    jobs: Int
):
  /** Booked share of the available time, 0–100 (0 when the resource is off that day). */
  def utilisation: Int = if availableMinutes <= 0 then 0 else math.round(bookedMinutes * 100.0 / availableMinutes).toInt

final case class DayLoad(technicians: Seq[ResourceLoad], bays: Seq[ResourceLoad])

/**
 * Workshop load for the controller's view. Pure, like Availability: the caller passes the
 * day's resources and busy periods.
 */
object Workload:
  private def minutes(t: LocalTime): Int = t.getHour * 60 + t.getMinute

  def forDay(rules: WorkshopRules, date: LocalDate, bays: Seq[Bay], technicians: Seq[Technician], busy: Seq[BusyPeriod]): DayLoad =
    val dayStart = date.atStartOfDay(rules.timezone).toInstant.toEpochMilli
    val dayEnd = date.plusDays(1).atStartOfDay(rules.timezone).toInstant.toEpochMilli
    val open = rules.openDays.contains(date.getDayOfWeek.getValue)
    val openMinutes = if open then minutes(rules.closingTime) - minutes(rules.openingTime) else 0

    /** Minutes of [start, end) that fall on this day, and the number of jobs. */
    def booked(periods: Seq[BusyPeriod]): (Int, Int) =
      val onDay = periods.filter(p => Availability.overlaps(p.startMs, p.endMs, dayStart, dayEnd))
      val total = onDay.map(p => (math.min(p.endMs, dayEnd) - math.max(p.startMs, dayStart)) / 60_000).sum
      (total.toInt, onDay.size)

    val techLoads = technicians.sortBy(_.name).map { t =>
      // Shift time inside opening hours on a day the technician works.
      val shift =
        if !open || !t.workDays.contains(date.getDayOfWeek.getValue) then 0
        else math.max(0, math.min(minutes(t.shiftEnd), minutes(rules.closingTime)) - math.max(minutes(t.shiftStart), minutes(rules.openingTime)))
      val (mins, jobs) = booked(busy.filter(_.technicianId == t.id))
      ResourceLoad(t.id, t.name, t.skills, shift, mins, jobs)
    }
    val bayLoads = bays.sortBy(_.name).map { b =>
      val (mins, jobs) = booked(busy.filter(_.bayId == b.id))
      ResourceLoad(b.id, b.name, Seq(b.bayType), openMinutes, mins, jobs)
    }
    DayLoad(techLoads, bayLoads)
