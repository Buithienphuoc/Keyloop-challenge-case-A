package scheduler.modules.advisory

import java.time.{Instant, ZonedDateTime}

import scheduler.shared.DbSession

/**
 * An advisor appointment is a meeting with a service advisor, not work on the vehicle (that
 * is a service job, in the scheduling module):
 *
 *   customer books ──▶ SCHEDULED (open: no advisor yet) ──advisor takes it──▶ SCHEDULED (advisor set)
 *                          │                                                      │
 *                          └──customer or staff cancels──▶ CANCELLED              └──advisor holds it──▶ DONE
 *
 * It carries the customer, the time, the workshop (dealership), the message to be advised
 * and the advisor.
 */
enum AdvisorAppointmentStatus:
  case SCHEDULED, DONE, CANCELLED

final case class AdvisorAppointmentView(
    id: String,
    reference: String,
    status: AdvisorAppointmentStatus,
    start: ZonedDateTime,
    dealership: AdvisorAppointmentView.DealershipRef,
    customer: AdvisorAppointmentView.CustomerRef,
    message: String,
    /** Empty while the appointment is open. */
    advisor: Option[AdvisorAppointmentView.Ref],
    cancelReason: Option[String],
    createdAt: Instant
)

object AdvisorAppointmentView:
  final case class DealershipRef(id: String, name: String, address: String, timezone: String)
  final case class CustomerRef(id: String, fullName: String, phone: Option[String], email: String)
  final case class Ref(id: String, name: String)

final case class NewAdvisorAppointment(
    reference: String,
    dealershipId: String,
    customerId: String,
    start: Instant,
    message: String,
    createdBy: String
)

final case class LockedAdvisorAppointment(
    id: String,
    status: AdvisorAppointmentStatus,
    dealershipId: String,
    customerId: String,
    advisorId: Option[String],
    start: Instant
)

final case class AdvisorAppointmentFilter(
    dealershipId: Option[String] = None,
    customerId: Option[String] = None,
    from: Option[Instant] = None,
    to: Option[Instant] = None,
    /** Only scheduled appointments no advisor has taken yet. */
    openOnly: Boolean = false
)

/** Port: advisor appointment persistence. Every method takes the session so the caller owns the transaction. */
trait AdvisorAppointmentRepository:
  def insert(s: DbSession, a: NewAdvisorAppointment): String
  def get(s: DbSession, id: String): Option[AdvisorAppointmentView]
  def list(s: DbSession, filter: AdvisorAppointmentFilter): Seq[AdvisorAppointmentView]
  def lockForUpdate(s: DbSession, id: String): Option[LockedAdvisorAppointment]

  /** Throws AdvisorBusyError when the advisor already has a meeting starting at that time. */
  def setAdvisor(s: DbSession, id: String, advisorId: String): Unit
  def setStatus(s: DbSession, id: String, status: AdvisorAppointmentStatus, cancelReason: Option[String]): Unit

/** The advisor already holds another meeting that starts at the same moment. */
final class AdvisorBusyError extends RuntimeException("The advisor already has an appointment at this time")
