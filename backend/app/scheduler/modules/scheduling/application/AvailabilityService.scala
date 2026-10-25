package scheduler.modules.scheduling.application

import java.time.{Instant, LocalDate, ZonedDateTime}

import scheduler.modules.identity.AuthContext
import scheduler.modules.scheduling.domain.{Availability, Requirement, Slot, WorkshopRules}
import scheduler.shared.{Db, DbSession}
import Workshop.{isOfferedHere, rulesOf}

final case class AvailabilityResponse(
    dealershipId: String,
    date: LocalDate,
    timezone: String,
    durationMinutes: Int,
    /** False when this dealership has no bay or no technician able to do this job at all. */
    offeredHere: Boolean,
    bookableDates: Seq[LocalDate],
    slots: Seq[Slot]
)

/** Read side of scheduling: free slots for a day, and the nearest free slots to a time. */
final class AvailabilityService(db: Db, calendar: ResourceCalendar, workshop: WorkshopContext, clock: () => Instant):

  def forDay(auth: AuthContext, query: AvailabilityQuery): AvailabilityResponse = db.run { s =>
    val dealership = workshop.dealership(query.dealershipId)
    val requirement = workshop.requirementFor(s, auth, dealership.id, query)
    val rules = rulesOf(dealership)
    val WorkshopResources(bays, technicians) = calendar.activeResources(s, dealership.id)
    val busy = calendar.busyPeriods(s, dealership.id, dayStart(query.date, rules), dayStart(query.date.plusDays(1), rules))
    val now = clock()
    AvailabilityResponse(
      dealershipId = dealership.id,
      date = query.date,
      timezone = rules.timezone.getId,
      durationMinutes = requirement.durationMinutes,
      offeredHere = isOfferedHere(requirement, bays, technicians),
      bookableDates = Availability.bookableDates(rules, now, 21),
      slots = Availability.daySlots(rules, query.date, requirement, bays, technicians, busy, now)
    )
  }

  /** Nearest free start times: same day closest to the requested time first, then the following days. */
  def nearest(
      s: DbSession,
      rules: WorkshopRules,
      requirement: Requirement,
      dealershipId: String,
      requested: ZonedDateTime,
      limit: Int = 3
  ): Seq[Slot] =
    val WorkshopResources(bays, technicians) = calendar.activeResources(s, dealershipId)
    if !isOfferedHere(requirement, bays, technicians) then Seq.empty
    else
      val now = clock()
      val requestedMs = requested.toInstant.toEpochMilli
      // Lazy: days are only loaded until `limit` free slots have been found (at most two weeks).
      LazyList.range(0, 14).flatMap { i =>
        val day = requested.toLocalDate.plusDays(i)
        val busy = calendar.busyPeriods(s, dealershipId, dayStart(day, rules), dayStart(day.plusDays(1), rules))
        val free = Availability.daySlots(rules, day, requirement, bays, technicians, busy, now).filter(_.available)
        if i == 0 then free.sortBy(slot => math.abs(slot.start.toInstant.toEpochMilli - requestedMs)) else free
      }.take(limit).toList

  private def dayStart(date: LocalDate, rules: WorkshopRules): Instant = date.atStartOfDay(rules.timezone).toInstant
