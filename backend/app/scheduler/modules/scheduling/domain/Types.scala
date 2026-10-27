package scheduler.modules.scheduling.domain

import java.time.{Instant, LocalDate, LocalTime, ZoneId, ZonedDateTime}

enum VehicleType:
  case MOTORBIKE, CAR

enum AppointmentStatus:
  /**
   * HELD: reserved for a few minutes while the customer confirms. EXPIRED: a hold that ran out.
   * AWAITING_PAYMENT: the work is done and the customer has checked the vehicle; the dealership
   * marks it COMPLETED once it has been paid.
   */
  case HELD, CONFIRMED, IN_PROGRESS, AWAITING_PAYMENT, COMPLETED, CANCELLED, NO_SHOW, EXPIRED

object AppointmentStatus:
  /** Statuses in which an appointment still holds its bay, technician and vehicle. */
  val Active: Set[AppointmentStatus] = Set(HELD, CONFIRMED, IN_PROGRESS)

/** Opening hours and booking rules of one dealership's workshop. */
final case class WorkshopRules(
    timezone: ZoneId,
    openingTime: LocalTime,
    closingTime: LocalTime,
    openDays: Set[Int], // ISO weekdays, 1 = Monday … 7 = Sunday
    slotMinutes: Int, // grid the customer can choose start times on
    minLeadMinutes: Int, // earliest start = now + lead
    maxDaysAhead: Int // latest bookable day
)

object LocalDay:
  /** A local day in a time zone as a half-open range of instants [start of day, start of next day). */
  def bounds(date: LocalDate, zone: ZoneId): (Instant, Instant) =
    (date.atStartOfDay(zone).toInstant, date.plusDays(1).atStartOfDay(zone).toInstant)

/** What a job needs from the workshop. Comes from a service package or an approved consultation. */
final case class Requirement(durationMinutes: Int, requiredSkill: String, bayType: String)

final case class Bay(id: String, name: String, bayType: String)

final case class Technician(
    id: String,
    name: String,
    skills: Seq[String],
    shiftStart: LocalTime,
    shiftEnd: LocalTime,
    workDays: Set[Int]
)

/** A time range during which a bay and a technician are taken. Epoch milliseconds, half-open [start, end). */
final case class BusyPeriod(bayId: String, technicianId: String, startMs: Long, endMs: Long)

final case class Allocation(bay: Bay, technician: Technician)

/** Why a slot cannot be booked. */
enum SlotUnavailable:
  case PAST_OR_TOO_SOON, FULLY_BOOKED, TOO_FAR_AHEAD

final case class Slot(
    start: ZonedDateTime, // in the dealership's zone
    end: ZonedDateTime,
    available: Boolean,
    /** How many more jobs of this kind fit at this start time. 0 when unavailable. */
    capacity: Int,
    reason: Option[SlotUnavailable]
)
