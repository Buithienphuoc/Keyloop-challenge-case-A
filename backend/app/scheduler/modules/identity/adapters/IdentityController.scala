package scheduler.modules.identity.adapters

import play.api.mvc.*

import scheduler.http.{ApiController, Guard}
import scheduler.modules.identity.IdentityJson.given
import scheduler.modules.identity.application.{IdentityRequests, IdentityService}
import scheduler.modules.identity.application.IdentityRequests.given

final class IdentityController(cc: ControllerComponents, guard: Guard, identity: IdentityService) extends ApiController(cc):

  def register: Action[AnyContent] = Action.async { req =>
    json(Created)(identity.registerCustomer(body[IdentityRequests.Register](req), requestLog(req)))
  }

  def login: Action[AnyContent] = Action.async { req =>
    json()(identity.login(body[IdentityRequests.Login](req), requestLog(req)))
  }

  def logout: Action[AnyContent] = guard().async { req =>
    noContent(identity.logout(Guard.bearerToken(req).get, req.log))
  }

  def me: Action[AnyContent] = guard().async(req => json()(identity.getUser(req.auth.userId)))
