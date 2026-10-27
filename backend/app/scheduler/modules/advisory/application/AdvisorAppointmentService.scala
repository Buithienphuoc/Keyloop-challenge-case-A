package scheduler.modules.advisory.application

import java.time.{Instant, LocalDate, OffsetDateTime}

import play.api.libs.functional.syntax.*
import play.api.libs.json.*

import scheduler.modules.advisory.*
import scheduler.modules.catalog.{CatalogReader, Dealership}
import scheduler.modules.identity.{AuthContext, Role, assertCanSee, assertSameDealership, requireDealership}
import scheduler.modules.scheduling.domain.LocalDay
import scheduler.shared.{AppError, Db, Ids, Log, Validate}

final case class BookAdvisorAppointment(dealershipId: String, start: String, message: String)

object BookAdvisorAppointment:
  given Reads[BookAdvisorAppointment] = (
    (__ \ "dealershipId").read(Validate.uuid) and
      (__ \ "start").read(Validate.isoDateTimeWithOffset) and
      (__ \ "message").read(Validate.text(5, 2000, tooShort = "Tell the advisor a bit more (at least 5 characters)"))
  )(BookAdvisorAppointment.apply)

final case class AdvisorAppointmentQuery(date: Option[LocalDate], open: Boolean)

object AdvisorAppointmentQuery:
  given Reads[AdvisorAppointmentQuery] = (
    (__ \ "date").readNullable(Validate.isoDate) and
      (__ \ "open").readNullable[String].map(_.contains("true"))
  )(AdvisorAppointmentQuery.apply)

final case class CancelAdvisorAppointment(reason: Option[String])

object CancelAdvisorAppointment:
  given Reads[CancelAdvisorAppointment] =
    (__ \ "reason").readNullable(Validate.text(0, 500)).map(r => CancelAdvisorAppointment(r.filter(_.nonEmpty)))

/** Booking, taking and closing meetings with a service advisor (see ../Domain.scala for the flow). */
final class AdvisorAppointmentService(db: Db, repo: AdvisorAppointmentRepository, catalog: CatalogReader, clock: () => Instant):

  def book(auth: AuthContext, input: BookAdvisorAppointment, log: Log): AdvisorAppointmentView =
    val dealership = catalog.getDealership(input.dealershipId).getOrElse(throw AppError.notFound("Dealership"))
    val start = OffsetDateTime.parse(input.start).toInstant
    checkBookable(dealership, start)
    val id = db.tx { s =>
      repo.insert(s, NewAdvisorAppointment(Ids.humanReference("AP"), dealership.id, auth.userId, start, input.message.trim, auth.userId))
    }
    log.info("advisor_appointment.booked", "advisorAppointmentId" -> id, "dealershipId" -> dealership.id)
    reload(id)

  def get(auth: AuthContext, id: String): AdvisorAppointmentView = db.run { s =>
    val view = repo.get(s, id).getOrElse(throw AppError.notFound("Appointment"))
    assertCanSee(auth, view.customer.id, view.dealership.id, "Appointment")
    view
  }

  def listMine(auth: AuthContext): Seq[AdvisorAppointmentView] =
    db.run(repo.list(_, AdvisorAppointmentFilter(customerId = Some(auth.userId))))

  /**
   * The dealership's appointments: the open ones still to be taken (soonest first, from now
   * on), or all of one local day.
   */
  def listForDealership(auth: AuthContext, q: AdvisorAppointmentQuery): Seq[AdvisorAppointmentView] =
    val dealershipId = auth.requireDealership
    val filter =
      if q.open then AdvisorAppointmentFilter(dealershipId = Some(dealershipId), from = Some(clock()), openOnly = true)
      else
        val date = q.date.getOrElse(throw AppError.badRequest("DATE_REQUIRED", "Give a date, or open=true"))
        val (from, to) = LocalDay.bounds(date, dealershipZone(dealershipId))
        AdvisorAppointmentFilter(dealershipId = Some(dealershipId), from = Some(from), to = Some(to))
    db.run(repo.list(_, filter))

  /** An advisor takes an open appointment for themselves. Taking one you already hold changes nothing. */
  def take(auth: AuthContext, id: String, log: Log): AdvisorAppointmentView =
    val changed = db.tx { s =>
      val a = repo.lockForUpdate(s, id).getOrElse(throw AppError.notFound("Appointment"))
      assertSameDealership(auth, a.dealershipId)
      if a.status != AdvisorAppointmentStatus.SCHEDULED then
        throw AppError.conflict("NOT_OPEN", s"This appointment is ${a.status.toString.toLowerCase}")
      a.advisorId match
        case Some(advisor) if advisor == auth.userId => false
        case Some(_) => throw AppError.conflict("ALREADY_TAKEN", "Another advisor has already taken this appointment")
        case None =>
          try repo.setAdvisor(s, id, auth.userId)
          catch case _: AdvisorBusyError => throw AppError.conflict("ADVISOR_BUSY", "You already have an appointment at this time")
          true
    }
    if changed then log.info("advisor_appointment.taken", "advisorAppointmentId" -> id)
    reload(id)

  /** The advisor who held the meeting closes it. */
  def complete(auth: AuthContext, id: String, log: Log): AdvisorAppointmentView =
    db.tx { s =>
      val a = repo.lockForUpdate(s, id).getOrElse(throw AppError.notFound("Appointment"))
      assertSameDealership(auth, a.dealershipId)
      if !a.advisorId.contains(auth.userId) then throw AppError.forbidden("Only the advisor holding this appointment can close it")
      if a.status != AdvisorAppointmentStatus.SCHEDULED then
        throw AppError.conflict("NOT_OPEN", s"This appointment is ${a.status.toString.toLowerCase}")
      repo.setStatus(s, id, AdvisorAppointmentStatus.DONE, None)
    }
    log.info("advisor_appointment.done", "advisorAppointmentId" -> id)
    reload(id)

  /** The customer cancels their own appointment, or staff of the dealership cancel it. */
  def cancel(auth: AuthContext, id: String, input: CancelAdvisorAppointment, log: Log): AdvisorAppointmentView =
    db.tx { s =>
      val a = repo.lockForUpdate(s, id).getOrElse(throw AppError.notFound("Appointment"))
      assertCanSee(auth, a.customerId, a.dealershipId, "Appointment")
      if a.status != AdvisorAppointmentStatus.SCHEDULED then
        throw AppError.conflict("NOT_OPEN", s"This appointment is ${a.status.toString.toLowerCase}")
      if auth.role == Role.CUSTOMER && !a.start.isAfter(clock()) then
        throw AppError.conflict("TOO_LATE_TO_CANCEL", "This appointment has already started. Please call the dealership.")
      repo.setStatus(s, id, AdvisorAppointmentStatus.CANCELLED, input.reason)
    }
    log.info("advisor_appointment.cancelled", "advisorAppointmentId" -> id)
    reload(id)

  /** In the future, inside the booking window, on an open day, within opening hours and on the slot grid. */
  private def checkBookable(d: Dealership, start: Instant): Unit =
    val now = clock()
    if start.isBefore(now.plusSeconds(d.minLeadMinutes * 60L)) then
      throw AppError.unprocessable("PAST_OR_TOO_SOON", "Choose a later time")
    val local = start.atZone(d.timezone)
    if local.toLocalDate.isAfter(now.atZone(d.timezone).toLocalDate.plusDays(d.maxDaysAhead.toLong)) then
      throw AppError.unprocessable("TOO_FAR_AHEAD", s"Appointments can be booked up to ${d.maxDaysAhead} days ahead")
    val time = local.toLocalTime
    val minutesFromOpening = java.time.Duration.between(d.openingTime, time).toMinutes
    if !d.openDays.contains(local.getDayOfWeek.getValue) || time.isBefore(d.openingTime) || !time.isBefore(d.closingTime) then
      throw AppError.unprocessable("OUTSIDE_OPENING_HOURS", "The dealership is closed at that time")
    if time.getSecond != 0 || time.getNano != 0 || minutesFromOpening % d.slotMinutes != 0 then
      throw AppError.unprocessable("NOT_ON_SLOT_GRID", s"Choose a start time in ${d.slotMinutes}-minute steps")

  private def dealershipZone(id: String) = catalog.getDealership(id).getOrElse(throw AppError.notFound("Dealership")).timezone

  private def reload(id: String): AdvisorAppointmentView =
    db.run(repo.get(_, id)).getOrElse(throw IllegalStateException(s"Advisor appointment $id vanished after commit"))
