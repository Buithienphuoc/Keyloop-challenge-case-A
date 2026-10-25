package scheduler.modules.scheduling.application

import scheduler.modules.catalog.{CatalogReader, Dealership}
import scheduler.modules.consultation.ConsultationStatus
import scheduler.modules.identity.{AuthContext, Role, assertSameDealership}
import scheduler.modules.scheduling.domain.{Bay, Requirement, Technician, WorkshopRules}
import scheduler.modules.vehicles.Vehicle
import scheduler.shared.{AppError, DbSession}

object Workshop:
  def rulesOf(d: Dealership): WorkshopRules = WorkshopRules(
    timezone = d.timezone,
    openingTime = d.openingTime,
    closingTime = d.closingTime,
    openDays = d.openDays.toSet,
    slotMinutes = d.slotMinutes,
    minLeadMinutes = d.minLeadMinutes,
    maxDaysAhead = d.maxDaysAhead
  )

  /** Can this dealership do the job at all (a bay of the type and a technician with the skill)? */
  def isOfferedHere(requirement: Requirement, bays: Seq[Bay], technicians: Seq[Technician]): Boolean =
    bays.exists(_.bayType == requirement.bayType) && technicians.exists(_.skills.contains(requirement.requiredSkill))

/**
 * Turns "what the customer wants" (a package or an approved consultation) into what the
 * scheduler needs (duration, skill, bay type), with the checks that go with it.
 * Shared by availability and booking, so both apply exactly the same rules.
 */
final class WorkshopContext(catalog: CatalogReader, consultations: ConsultationBookingPort):

  def dealership(id: String): Dealership = catalog.getDealership(id).getOrElse(throw AppError.notFound("Dealership"))

  def requirementFor(
      s: DbSession,
      auth: AuthContext,
      dealershipId: String,
      source: ServiceSource,
      vehicle: Option[Vehicle] = None
  ): Requirement =
    (source.packageId, source.consultationRequestId) match
      case (Some(packageId), None) =>
        val pkg = catalog.getPackage(packageId).getOrElse(throw AppError.notFound("Service package"))
        vehicle.foreach { v =>
          if pkg.vehicleType != v.vehicleType then
            throw AppError.unprocessable("PACKAGE_NOT_FOR_VEHICLE", s""""${pkg.name}" is for a ${pkg.vehicleType.toString.toLowerCase}""")
        }
        Requirement(pkg.durationMinutes, pkg.requiredSkill, pkg.bayType)

      case (None, Some(requestId)) =>
        val c = consultations.forBooking(s, requestId).getOrElse(throw AppError.notFound("Consultation request"))
        if auth.role == Role.CUSTOMER && c.customerId != auth.userId then throw AppError.notFound("Consultation request")
        if auth.role != Role.CUSTOMER then assertSameDealership(auth, c.dealershipId)
        if c.dealershipId != dealershipId then
          throw AppError.unprocessable("WRONG_DEALERSHIP", "This request was reviewed by another dealership")
        vehicle.foreach { v =>
          if c.vehicleId != v.id then throw AppError.unprocessable("WRONG_VEHICLE", "This request is for another vehicle")
        }
        c.requirement match
          case Some(r) if c.status == ConsultationStatus.APPROVED => r
          case _ => throw AppError.conflict("CONSULTATION_NOT_BOOKABLE", "This request is not approved for booking")

      case _ => throw AppError.badRequest("INVALID_SERVICE", "Give exactly one of packageId or consultationRequestId")
