package scheduler.modules.scheduling.adapters

import scala.concurrent.Future

import play.api.mvc.*

import scheduler.http.{ApiController, Guard}
import scheduler.http.Blocking.given
import scheduler.modules.identity.Role.*
import scheduler.modules.scheduling.application.*
import scheduler.modules.scheduling.application.SchedulingJson.given
import scheduler.modules.scheduling.domain.AppointmentStatus
import scheduler.shared.AppError

/** HTTP adapter for scheduling: validation and status codes only; the services hold the rules. */
final class SchedulingController(
    cc: ControllerComponents,
    guard: Guard,
    availability: AvailabilityService,
    booking: BookingService,
    appointments: AppointmentService,
    dispatch: DispatchService
) extends ApiController(cc):

  def availabilityForDay: Action[AnyContent] = guard(CUSTOMER, SERVICE_ADVISOR, ADMIN).async { req =>
    json()(availability.forDay(req.auth, query[AvailabilityQuery](req)))
  }

  def book: Action[AnyContent] = place(hold = false)

  /** Reserves the slot for a few minutes; the customer then confirms it (HELD → CONFIRMED). */
  def hold: Action[AnyContent] = place(hold = true)

  private def place(hold: Boolean): Action[AnyContent] = guard(CUSTOMER).async { req =>
    Future {
      val key = req.headers.get("Idempotency-Key").map { k =>
        if !IdempotencyKey.isValid(k) then throw AppError.badRequest("INVALID_IDEMPOTENCY_KEY", IdempotencyKey.Message)
        k
      }
      booking.book(req.auth, body[BookRequest](req), key, req.log, hold)
    }.map { r =>
      if r.replayed then Ok(play.api.libs.json.Json.toJson(r.appointment)).withHeaders("Idempotent-Replayed" -> "true")
      else Created(play.api.libs.json.Json.toJson(r.appointment))
    }
  }

  def confirmHold(id: String): Action[AnyContent] = guard(CUSTOMER).async { req =>
    json()(appointments.confirmHold(req.auth, uuid(id), req.log))
  }

  def listMine: Action[AnyContent] = guard(CUSTOMER).async(req => json()(appointments.listMine(req.auth)))

  def get(id: String): Action[AnyContent] = guard().async(req => json()(appointments.get(req.auth, uuid(id))))

  def cancel(id: String): Action[AnyContent] = guard(CUSTOMER, SERVICE_ADVISOR, ADMIN).async { req =>
    json()(appointments.changeStatus(req.auth, uuid(id), AppointmentStatus.CANCELLED, body[CancelRequest](req).reason, req.log))
  }

  def changeStatus(id: String): Action[AnyContent] = guard(SERVICE_ADVISOR, ADMIN, TECHNICIAN).async { req =>
    json() {
      val change = body[StatusChange](req)
      appointments.changeStatus(req.auth, uuid(id), change.status, change.reason, req.log)
    }
  }

  def dealershipDay: Action[AnyContent] = guard(SERVICE_ADVISOR, ADMIN, WORKSHOP_CONTROLLER).async { req =>
    json()(appointments.listForDealershipDay(req.auth, query[DayQuery](req).date))
  }

  def technicianDay: Action[AnyContent] = guard(TECHNICIAN).async { req =>
    json()(appointments.listTechnicianDay(req.auth, query[DayQuery](req).date))
  }

  def workload: Action[AnyContent] = guard(WORKSHOP_CONTROLLER, ADMIN, SERVICE_ADVISOR).async { req =>
    json()(dispatch.workload(req.auth, query[DayQuery](req).date))
  }

  def reassign(id: String): Action[AnyContent] = guard(WORKSHOP_CONTROLLER, ADMIN).async { req =>
    json()(dispatch.reassign(req.auth, uuid(id), body[Reassignment](req), req.log))
  }
