package scheduler.modules.scheduling.application

import java.time.LocalDate

import play.api.libs.functional.syntax.*
import play.api.libs.json.*

import scheduler.modules.scheduling.domain.AppointmentStatus
import scheduler.shared.Validate
import scheduler.shared.Validate.error

/** What the customer wants done: a catalogue package or an approved consultation. */
trait ServiceSource:
  def packageId: Option[String]
  def consultationRequestId: Option[String]

private val ExactlyOne = "Give exactly one of packageId or consultationRequestId"

private def exactlyOne[T <: ServiceSource](r: Reads[T]): Reads[T] =
  r.filter(error(ExactlyOne))(s => s.packageId.isDefined != s.consultationRequestId.isDefined)

/** Request shapes for the scheduling API (validated at the HTTP edge, typed in the services). */
final case class AvailabilityQuery(
    dealershipId: String,
    date: LocalDate,
    packageId: Option[String],
    consultationRequestId: Option[String]
) extends ServiceSource

object AvailabilityQuery:
  given Reads[AvailabilityQuery] = exactlyOne((
    (__ \ "dealershipId").read(Validate.uuid) and
      (__ \ "date").read(Validate.isoDate) and
      (__ \ "packageId").readNullable(Validate.uuid) and
      (__ \ "consultationRequestId").readNullable(Validate.uuid)
  )(AvailabilityQuery.apply))

final case class BookRequest(
    dealershipId: String,
    vehicleId: String,
    start: String,
    packageId: Option[String],
    consultationRequestId: Option[String],
    customerNote: Option[String]
) extends ServiceSource

object BookRequest:
  given Reads[BookRequest] = exactlyOne((
    (__ \ "dealershipId").read(Validate.uuid) and
      (__ \ "vehicleId").read(Validate.uuid) and
      (__ \ "start").read(Validate.isoDateTimeWithOffset) and
      (__ \ "packageId").readNullable(Validate.uuid) and
      (__ \ "consultationRequestId").readNullable(Validate.uuid) and
      (__ \ "customerNote").readNullable(Validate.text(0, 1000)).map(_.filter(_.nonEmpty))
  )(BookRequest.apply))

final case class DayQuery(date: LocalDate)

object DayQuery:
  given Reads[DayQuery] = (__ \ "date").read(Validate.isoDate).map(DayQuery.apply)

/** The dealership's job list, optionally narrowed to one status. */
final case class JobsQuery(status: Option[AppointmentStatus])

object JobsQuery:
  private val listable = AppointmentStatus.values.filterNot(s => s == AppointmentStatus.HELD || s == AppointmentStatus.EXPIRED)
  val listableStatus: Reads[AppointmentStatus] = Validate.oneOf(listable)
  given Reads[JobsQuery] = (__ \ "status").readNullable(listableStatus).map(JobsQuery.apply)

/** Every appointment, optionally of one dealership ("all" = every one) and in one status. */
final case class AllAppointmentsQuery(dealershipId: Option[String], status: Option[AppointmentStatus])

object AllAppointmentsQuery:
  given Reads[AllAppointmentsQuery] = (
    (__ \ "dealershipId").readNullable(Validate.dealershipScope) and
      (__ \ "status").readNullable(JobsQuery.listableStatus)
  )(AllAppointmentsQuery.apply)

final case class StatusChange(status: AppointmentStatus, reason: Option[String])

object StatusChange:
  private val settable = Array(
    AppointmentStatus.IN_PROGRESS,
    AppointmentStatus.AWAITING_PAYMENT,
    AppointmentStatus.COMPLETED,
    AppointmentStatus.CANCELLED,
    AppointmentStatus.NO_SHOW
  )
  given Reads[StatusChange] = (
    (__ \ "status").read(Validate.oneOf(settable)) and
      (__ \ "reason").readNullable(Validate.text(0, 500)).map(_.filter(_.nonEmpty))
  )(StatusChange.apply)

final case class CancelRequest(reason: Option[String])

object CancelRequest:
  given Reads[CancelRequest] = (__ \ "reason").readNullable(Validate.text(0, 500)).map(r => CancelRequest(r.filter(_.nonEmpty)))

object IdempotencyKey:
  private val Pattern = "[A-Za-z0-9_-]{8,100}".r
  val Message = """Idempotency-Key must be 8-100 letters, digits, "-" or "_""""
  def isValid(key: String): Boolean = Pattern.matches(key)
