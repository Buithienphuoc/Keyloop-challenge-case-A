package scheduler.modules.advisory.adapters

import play.api.mvc.*

import scheduler.http.{ApiController, Guard}
import scheduler.modules.advisory.AdvisoryJson.given
import scheduler.modules.advisory.application.*
import scheduler.modules.identity.Role.*

final class AdvisorAppointmentController(cc: ControllerComponents, guard: Guard, appointments: AdvisorAppointmentService)
    extends ApiController(cc):

  def book: Action[AnyContent] = guard(CUSTOMER).async { req =>
    json(Created)(appointments.book(req.auth, body[BookAdvisorAppointment](req), req.log))
  }

  def listMine: Action[AnyContent] = guard(CUSTOMER).async(req => json()(appointments.listMine(req.auth)))

  def get(id: String): Action[AnyContent] = guard(CUSTOMER, SERVICE_ADVISOR, ADMIN).async { req =>
    json()(appointments.get(req.auth, uuid(id)))
  }

  def listForDealership: Action[AnyContent] = guard(SERVICE_ADVISOR, ADMIN).async { req =>
    json()(appointments.listForDealership(req.auth, query[AdvisorAppointmentQuery](req)))
  }

  def take(id: String): Action[AnyContent] = guard(SERVICE_ADVISOR).async { req =>
    json()(appointments.take(req.auth, uuid(id), req.log))
  }

  def complete(id: String): Action[AnyContent] = guard(SERVICE_ADVISOR).async { req =>
    json()(appointments.complete(req.auth, uuid(id), req.log))
  }

  def cancel(id: String): Action[AnyContent] = guard(CUSTOMER, SERVICE_ADVISOR, ADMIN).async { req =>
    json()(appointments.cancel(req.auth, uuid(id), body[CancelAdvisorAppointment](req), req.log))
  }
