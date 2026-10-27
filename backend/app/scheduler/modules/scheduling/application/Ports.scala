package scheduler.modules.scheduling.application

import java.time.{Instant, ZonedDateTime}

import scheduler.modules.catalog.Money
import scheduler.modules.consultation.BookableConsultation
import scheduler.modules.scheduling.domain.{AppointmentStatus, Bay, BusyPeriod, Requirement, Technician, TechnicianAssignment, VehicleType}
import scheduler.shared.DbSession

/** Read model returned by the API. */
final case class AppointmentView(
    id: String,
    reference: String,
    status: AppointmentStatus,
    start: ZonedDateTime,
    end: ZonedDateTime,
    dealership: AppointmentView.DealershipRef,
    vehicle: AppointmentView.VehicleRef,
    customer: AppointmentView.CustomerRef,
    service: AppointmentView.ServiceInfo,
    price: Option[Money],
    bay: AppointmentView.Ref,
    technician: AppointmentView.Ref,
    customerNote: Option[String],
    cancelReason: Option[String],
    createdAt: Instant,
    /** Only while HELD: when the hold runs out unless the customer confirms. */
    holdExpiresAt: Option[Instant] = None,
    /**
     * False while the service job is open: the technician is only reserved by the booking and
     * nobody has taken the job yet (a technician taking it, or an advisor assigning one).
     */
    technicianAssigned: Boolean = true
)

object AppointmentView:
  final case class DealershipRef(id: String, name: String, address: String, phone: Option[String], timezone: String)
  final case class VehicleRef(id: String, vehicleType: VehicleType, make: String, model: String, plate: String)
  final case class CustomerRef(id: String, fullName: String, phone: Option[String], email: String)
  /** kind is PACKAGE, INSPECTION or MODIFICATION. */
  final case class ServiceInfo(
      kind: String,
      name: String,
      packageId: Option[String],
      consultationRequestId: Option[String],
      checklist: Seq[String]
  )
  final case class Ref(id: String, name: String)

final case class NewAppointment(
    reference: String,
    dealershipId: String,
    customerId: String,
    vehicleId: String,
    servicePackageId: Option[String],
    consultationRequestId: Option[String],
    bayId: String,
    technicianId: String,
    start: Instant,
    end: Instant,
    customerNote: Option[String],
    idempotencyKey: Option[String],
    createdBy: String,
    status: AppointmentStatus = AppointmentStatus.CONFIRMED,
    holdExpiresAt: Option[Instant] = None,
    /** Customer bookings start open (false); staff-made or migrated jobs may already be assigned. */
    technicianAssigned: Boolean = false
)

final case class AppointmentFilter(
    customerId: Option[String] = None,
    dealershipId: Option[String] = None,
    technicianId: Option[String] = None,
    from: Option[Instant] = None,
    to: Option[Instant] = None,
    /** Empty = every status (EXPIRED holds are never listed). */
    statuses: Set[AppointmentStatus] = Set.empty,
    newestFirst: Boolean = false,
    /** Only service jobs nobody has taken yet. */
    openOnly: Boolean = false,
    limit: Int = 1000
)

final case class LockedAppointment(
    id: String,
    status: AppointmentStatus,
    start: Instant,
    dealershipId: String,
    customerId: String,
    technicianId: String,
    consultationRequestId: Option[String],
    holdExpiresAt: Option[Instant] = None
)

final case class AppointmentEvent(
    appointmentId: String,
    from: Option[AppointmentStatus],
    to: AppointmentStatus,
    actorId: String,
    note: Option[String]
)

final case class WorkshopResources(bays: Seq[Bay], technicians: Seq[Technician])

/** The appointment that still claims a vehicle (on hold, booked or in the workshop). */
final case class LiveAppointment(reference: String, status: AppointmentStatus, start: Instant)

/** A hold that ran out and was released (its consultation, if any, must be reopened). */
final case class ExpiredHold(appointmentId: String, consultationRequestId: Option[String])

/** Which holds an expiry sweep looks at. */
enum HoldScope:
  /** Every dealership (the background sweeper). */
  case Everywhere
  /** Holds that could block one booking: same dealership and time range, or the same vehicle. */
  case Around(dealershipId: String, vehicleId: Option[String], from: Instant, to: Instant)

/** What moving a job to another bay or technician needs to know about it. */
final case class JobDetails(
    id: String,
    dealershipId: String,
    status: AppointmentStatus,
    start: Instant,
    end: Instant,
    bayId: String,
    technicianId: String,
    requirement: Requirement,
    technicianAssigned: Boolean = true
)

// ---- persistence ports, split by who uses them (ISP) ----------------------------------------
// Every method takes the session so the caller controls the transaction.

/** What computing availability needs. */
trait ResourceCalendar:
  def activeResources(s: DbSession, dealershipId: String): WorkshopResources
  /** Bookings and unexpired holds (as of `asOf`) that overlap [from, to). */
  def busyPeriods(s: DbSession, dealershipId: String, from: Instant, to: Instant, asOf: Instant): Seq[BusyPeriod]

/** Reading appointments. */
trait AppointmentReader:
  def get(s: DbSession, id: String): Option[AppointmentView]
  def list(s: DbSession, filter: AppointmentFilter): Seq[AppointmentView]
  def technicianIdForUser(s: DbSession, userId: String): Option[String]
  def jobDetails(s: DbSession, id: String): Option[JobDetails]
  /** The account a technician profile signs in with, if it has one. */
  def technicianAccount(s: DbSession, technicianId: String): Option[TechnicianAssignment.Account]

/** Writing appointments. */
trait AppointmentWriter:
  /**
   * Throws SlotTakenError when the bay or technician was taken concurrently, and
   * VehicleBusyError when the vehicle already has an overlapping appointment.
   */
  def insert(s: DbSession, appointment: NewAppointment): String
  def findIdByIdempotencyKey(s: DbSession, customerId: String, key: String): Option[String]

  /** Serialises transactions that use the same customer + Idempotency-Key (held until commit). */
  def lockIdempotencyKey(s: DbSession, customerId: String, key: String): Unit
  /** Queues booking attempts for one vehicle until the current transaction ends. */
  def lockVehicle(s: DbSession, vehicleId: String): Unit
  /** The vehicle's appointment that is on a hold that has not run out, booked or in the workshop, if any. */
  def liveAppointmentOf(s: DbSession, vehicleId: String, asOf: Instant): Option[LiveAppointment]

  /** Row-locks an appointment for a status change. */
  def lockForUpdate(s: DbSession, id: String): Option[LockedAppointment]
  def setStatus(s: DbSession, id: String, status: AppointmentStatus, cancelReason: Option[String]): Unit
  def recordEvent(s: DbSession, e: AppointmentEvent): Unit

  /** Moves holds that ran out (as of `asOf`) to EXPIRED, with an audit event each. */
  def expireHolds(s: DbSession, asOf: Instant, scope: HoldScope): Seq[ExpiredHold]

  /** Moves a job to another bay and/or technician. Throws SlotTakenError when either is busy then. */
  def reassign(s: DbSession, id: String, bayId: String, technicianId: String): Unit

  /** Marks the job's technician as taken for good (no longer an open job). */
  def markTechnicianAssigned(s: DbSession, id: String, by: String): Unit

/** The whole store, as implemented by the PostgreSQL adapter. */
trait SchedulingStore extends ResourceCalendar, AppointmentReader, AppointmentWriter

/**
 * What booking needs from the consultation module. Declared here, by the consumer (DIP);
 * ConsultationService implements it.
 */
trait ConsultationBookingPort:
  def forBooking(s: DbSession, id: String): Option[BookableConsultation]

  /** APPROVED → BOOKED; false if it is no longer approved. */
  def markBooked(s: DbSession, id: String): Boolean

  /** BOOKED → APPROVED, when its appointment is cancelled. */
  def reopen(s: DbSession, id: String): Unit

// ---- errors the store raises (no database codes leak into the use cases) ---------------------

/** Another booking took the chosen bay or technician first. Retry with fresh data. */
final class SlotTakenError extends RuntimeException("The chosen bay or technician was just taken")

/** The vehicle already has an appointment that overlaps. */
final class VehicleBusyError extends RuntimeException("The vehicle already has an overlapping appointment")
