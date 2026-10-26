package scheduler.modules.consultation.application

import play.api.libs.functional.syntax.*
import play.api.libs.json.*

import scheduler.modules.catalog.{CatalogReader, InspectionAreas}
import scheduler.modules.consultation.*
import scheduler.modules.identity.{AuthContext, assertCanSee, assertSameDealership, requireDealership}
import scheduler.modules.scheduling.application.ConsultationBookingPort
import scheduler.modules.scheduling.domain.Compatibility
import scheduler.modules.vehicles.OwnedVehicles
import scheduler.shared.{AppError, Db, DbSession, Ids, Log, Metrics, Validate}

final case class SubmitRequest(kind: ConsultationKind, vehicleId: String, dealershipId: String, areas: List[String], description: String)

object SubmitRequest:
  private val area = Validate.oneOf(InspectionAreas.map(_.code).toArray)
  given Reads[SubmitRequest] = (
    (__ \ "kind").read(Validate.oneOf(ConsultationKind.values)) and
      (__ \ "vehicleId").read(Validate.uuid) and
      (__ \ "dealershipId").read(Validate.uuid) and
      (__ \ "areas").read(Validate.listOf(area, min = 1, max = 11, tooFew = "Pick at least one area")) and
      (__ \ "description").read(Validate.text(10, 2000, tooShort = "Tell us a bit more (at least 10 characters)"))
  )(SubmitRequest.apply)

/** The advisor's decision: approve with what the scheduler needs, or reject with a reason. */
enum Review:
  case Approve(durationMinutes: Int, requiredSkill: String, bayType: String, estimatedPrice: Option[Int], note: Option[String])
  case Reject(note: String)

object Review:
  private val approve: Reads[Review] = (
    (__ \ "durationMinutes").read(Validate.int(15, 480).filter(Validate.error("Use 15-minute steps"))(_ % 15 == 0)) and
      (__ \ "requiredSkill").read(Validate.text(1, 100)) and
      (__ \ "bayType").read(Validate.text(1, 100)) and
      (__ \ "estimatedPrice").readNullable(Validate.int(0, 1_000_000_000)) and
      (__ \ "note").readNullable(Validate.text(0, 2000)).map(_.filter(_.nonEmpty))
  )(Review.Approve.apply)

  private val reject: Reads[Review] =
    (__ \ "note").read(Validate.text(5, 2000, tooShort = "Explain to the customer why")).map(Review.Reject.apply)

  given Reads[Review] = (__ \ "decision").read[String].flatMap {
    case "APPROVE" => approve
    case "REJECT"  => reject
    case _         => Reads(_ => JsError(__ \ "decision", Validate.error("Must be APPROVE or REJECT")))
  }

/**
 * Use cases for inspection and customisation requests (see ../Domain.scala for the flow).
 * It also implements ConsultationBookingPort, the narrow interface the booking flow uses.
 */
final class ConsultationService(db: Db, repo: ConsultationRepository, vehicles: OwnedVehicles, catalog: CatalogReader)
    extends ConsultationBookingPort:

  def submit(auth: AuthContext, input: SubmitRequest, log: Log): ConsultationView =
    vehicles.getOwned(auth.userId, input.vehicleId)
    if catalog.getDealership(input.dealershipId).isEmpty then throw AppError.notFound("Dealership")
    val id = repo.insert(
      NewConsultation(
        reference = Ids.humanReference("CR"),
        customerId = auth.userId,
        vehicleId = input.vehicleId,
        dealershipId = input.dealershipId,
        kind = input.kind,
        areas = input.areas.distinct,
        description = input.description
      )
    )
    Metrics.consultations.labelValues(input.kind.toString, "submitted").inc()
    log.info("consultation.submitted", "consultationId" -> id, "kind" -> input.kind, "dealershipId" -> input.dealershipId, "areas" -> input.areas)
    reload(id)

  def getVisible(auth: AuthContext, id: String): ConsultationView =
    val view = repo.get(id).getOrElse(throw AppError.notFound("Consultation request"))
    assertCanSee(auth, view.customer.id, view.dealership.id, "Consultation request")
    view

  def listMine(auth: AuthContext): Seq[ConsultationView] = repo.listByCustomer(auth.userId)

  def listForDealership(auth: AuthContext, status: Option[ConsultationStatus]): Seq[ConsultationView] =
    repo.listByDealership(auth.requireDealership, status)

  def review(auth: AuthContext, id: String, input: Review, log: Log): ConsultationView =
    db.tx { s =>
      val c = repo.lockForReview(s, id).getOrElse(throw AppError.notFound("Consultation request"))
      assertSameDealership(auth, c.dealershipId)
      if c.status != ConsultationStatus.SUBMITTED then
        throw AppError.conflict("ALREADY_REVIEWED", s"This request is already ${c.status.toString.toLowerCase}")

      val decision = input match
        case Review.Approve(duration, skill, bayType, price, note) =>
          if !catalog.hasSkill(skill) then throw AppError.unprocessable("UNKNOWN_SKILL", s"Unknown skill $skill")
          if !Compatibility.bayFitsVehicle(bayType, c.vehicleType) then
            throw AppError.unprocessable("BAY_TYPE_NOT_FOR_VEHICLE", s"A $bayType bay cannot take a ${c.vehicleType.toString.toLowerCase}")
          repo.approve(s, id, Approval(auth.userId, note, duration, skill, bayType, price))
          "APPROVE"
        case Review.Reject(note) =>
          repo.reject(s, id, auth.userId, note)
          "REJECT"
      Metrics.consultations.labelValues(c.kind.toString, if decision == "APPROVE" then "approved" else "rejected").inc()
      log.info("consultation.reviewed", "consultationId" -> id, "decision" -> decision)
    }
    reload(id)

  def cancel(auth: AuthContext, id: String, log: Log): ConsultationView =
    if !repo.cancelByCustomer(id, auth.userId) then
      val existing = getVisible(auth, id) // 404 if not theirs
      throw AppError.conflict("CANNOT_CANCEL", s"A ${existing.status.toString.toLowerCase} request cannot be cancelled here")
    log.info("consultation.cancelled", "consultationId" -> id)
    reload(id)

  /** The request as it is after a committed change. */
  private def reload(id: String): ConsultationView =
    repo.get(id).getOrElse(throw IllegalStateException(s"Consultation request $id vanished after commit"))

  // ---- ConsultationBookingPort: called by scheduling inside its booking transaction ----------

  def forBooking(s: DbSession, id: String): Option[BookableConsultation] = repo.forBooking(s, id)
  def markBooked(s: DbSession, id: String): Boolean = repo.markBooked(s, id)
  def reopen(s: DbSession, id: String): Unit = repo.reopen(s, id)
