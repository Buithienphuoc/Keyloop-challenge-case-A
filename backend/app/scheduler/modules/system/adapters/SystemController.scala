package scheduler.modules.system.adapters

import play.api.mvc.*

import scheduler.http.{ApiController, Guard}
import scheduler.modules.identity.Role.SYSTEM_MANAGER
import scheduler.modules.system.SystemJson.given
import scheduler.modules.system.PackageChanges
import scheduler.modules.system.application.{NewDealership, PackageAdminService, TenantService}
import scheduler.modules.system.application.PackageRequests.{NewPackage, given}

/** HTTP adapter for platform administration. */
final class SystemController(cc: ControllerComponents, guard: Guard, tenants: TenantService, packages: PackageAdminService)
    extends ApiController(cc):
  private val sys = guard(SYSTEM_MANAGER)

  def list: Action[AnyContent] = sys.async(_ => json()(tenants.list()))

  def onboard: Action[AnyContent] = sys.async(req => json(Created)(tenants.onboard(body[NewDealership](req), req.log)))

  def listPackages: Action[AnyContent] = sys.async(_ => json()(packages.list()))

  def createPackage: Action[AnyContent] = sys.async(req => json(Created)(packages.create(body[NewPackage](req), req.log)))

  def updatePackage(id: String): Action[AnyContent] = sys.async { req =>
    json()(packages.update(uuid(id), body[PackageChanges](req), req.log))
  }
