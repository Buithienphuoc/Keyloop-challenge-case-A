package scheduler.modules.consultation

import java.time.Instant

import scheduler.modules.scheduling.domain.{Requirement, VehicleType}
import scheduler.shared.DbSession

/**
 * "Check on request" and "customisation" share one flow:
 *
 *   customer submits ──▶ SUBMITTED ──advisor approves──▶ APPROVED ──customer picks a slot──▶ BOOKED
 *                              │                              │
 *                              └──advisor rejects──▶ REJECTED └──customer cancels──▶ CANCELLED
 *
 * The advisor's review turns a free-text problem into what the scheduler needs:
 * a duration, a skill and a bay type. Only then can a slot be offered.
 */
enum ConsultationKind:
  case INSPECTION, MODIFICATION

enum ConsultationStatus:
  case SUBMITTED, APPROVED, REJECTED, BOOKED, CANCELLED

final case class ConsultationView(
    id: String,
    reference: String,
    kind: ConsultationKind,
    status: ConsultationStatus,
    areas: Seq[String],
    description: String,
    vehicle: ConsultationView.VehicleRef,
    dealership: ConsultationView.DealershipRef,
    customer: ConsultationView.CustomerRef,
    advisorNote: Option[String],
    advisorName: Option[String],
    durationMinutes: Option[Int],
    requiredSkill: Option[String],
    bayType: Option[String],
    estimatedPrice: Option[Int],
    createdAt: Instant,
    reviewedAt: Option[Instant],
    appointment: Option[ConsultationView.AppointmentRef]
)

object ConsultationView:
  final case class VehicleRef(id: String, vehicleType: VehicleType, make: String, model: String, plate: String)
  final case class DealershipRef(id: String, name: String)
  final case class CustomerRef(id: String, fullName: String, phone: Option[String], email: String)
  final case class AppointmentRef(id: String, reference: String, start: Instant, status: String)

/** The facts the booking flow needs about a request. */
final case class BookableConsultation(
    id: String,
    kind: ConsultationKind,
    dealershipId: String,
    vehicleId: String,
    customerId: String,
    status: ConsultationStatus,
    requirement: Option[Requirement]
)

final case class NewConsultation(
    reference: String,
    customerId: String,
    vehicleId: String,
    dealershipId: String,
    kind: ConsultationKind,
    areas: Seq[String],
    description: String
)

final case class Approval(
    advisorId: String,
    note: Option[String],
    durationMinutes: Int,
    requiredSkill: String,
    bayType: String,
    estimatedPrice: Option[Int]
)

final case class ReviewTarget(status: ConsultationStatus, dealershipId: String, kind: ConsultationKind, vehicleType: VehicleType)

/**
 * Port: consultation persistence. Methods that run inside a transaction (the advisor's
 * review, the booking flow) take the session; plain reads use the adapter's own connection.
 */
trait ConsultationRepository:
  def insert(c: NewConsultation): String
  def get(id: String): Option[ConsultationView]
  def listByCustomer(customerId: String): Seq[ConsultationView]
  /** `dealershipId` None = every dealership. */
  def listByDealership(dealershipId: Option[String], status: Option[ConsultationStatus]): Seq[ConsultationView]

  /** Row-locks the request for review. */
  def lockForReview(s: DbSession, id: String): Option[ReviewTarget]
  def approve(s: DbSession, id: String, a: Approval): Unit
  def reject(s: DbSession, id: String, advisorId: String, note: String): Unit

  /** SUBMITTED or APPROVED → CANCELLED for the owner. False if not allowed. */
  def cancelByCustomer(id: String, customerId: String): Boolean
  def forBooking(s: DbSession, id: String): Option[BookableConsultation]

  /** APPROVED → BOOKED. False if someone else got there first. */
  def markBooked(s: DbSession, id: String): Boolean

  /** BOOKED → APPROVED. */
  def reopen(s: DbSession, id: String): Unit
