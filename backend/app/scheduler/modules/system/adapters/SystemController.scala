package scheduler.modules.system.adapters

import play.api.mvc.*

import scheduler.http.{ApiController, Guard}
import scheduler.modules.identity.Role.SYSTEM_MANAGER
import scheduler.modules.system.SystemJson.given
import scheduler.modules.system.application.{NewDealership, TenantService}

/** HTTP adapter for platform administration. */
final class SystemController(cc: ControllerComponents, guard: Guard, tenants: TenantService) extends ApiController(cc):
  private val sys = guard(SYSTEM_MANAGER)

  def list: Action[AnyContent] = sys.async(_ => json()(tenants.list()))

  def onboard: Action[AnyContent] = sys.async(req => json(Created)(tenants.onboard(body[NewDealership](req), req.log)))
