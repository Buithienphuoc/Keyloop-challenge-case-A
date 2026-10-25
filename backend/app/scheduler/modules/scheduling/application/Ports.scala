package scheduler.modules.scheduling.application

import java.time.{Instant, ZonedDateTime}

import scheduler.modules.catalog.Money
import scheduler.modules.consultation.BookableConsultation
import scheduler.modules.scheduling.domain.{AppointmentStatus, Bay, BusyPeriod, Technician, VehicleType}
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
    createdAt: Instant
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
    createdBy: String
)

final case class AppointmentFilter(
    customerId: Option[String] = None,
    dealershipId: Option[String] = None,
    technicianId: Option[String] = None,
    from: Option[Instant] = None,
    to: Option[Instant] = None
)

final case class LockedAppointment(
    id: String,
    status: AppointmentStatus,
    start: Instant,
    dealershipId: String,
    customerId: String,
    technicianId: String,
    consultationRequestId: Option[String]
)

final case class AppointmentEvent(
    appointmentId: String,
    from: Option[AppointmentStatus],
    to: AppointmentStatus,
    actorId: String,
    note: Option[String]
)

final case class WorkshopResources(bays: Seq[Bay], technicians: Seq[Technician])

// ---- persistence ports, split by who uses them (ISP) ----------------------------------------
// Every method takes the session so the caller controls the transaction.

/** What computing availability needs. */
trait ResourceCalendar:
  def activeResources(s: DbSession, dealershipId: String): WorkshopResources
  def busyPeriods(s: DbSession, dealershipId: String, from: Instant, to: Instant): Seq[BusyPeriod]

/** Reading appointments. */
trait AppointmentReader:
  def get(s: DbSession, id: String): Option[AppointmentView]
  def list(s: DbSession, filter: AppointmentFilter): Seq[AppointmentView]
  def technicianIdForUser(s: DbSession, userId: String): Option[String]

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

  /** Row-locks an appointment for a status change. */
  def lockForUpdate(s: DbSession, id: String): Option[LockedAppointment]
  def setStatus(s: DbSession, id: String, status: AppointmentStatus, cancelReason: Option[String]): Unit
  def recordEvent(s: DbSession, e: AppointmentEvent): Unit

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
