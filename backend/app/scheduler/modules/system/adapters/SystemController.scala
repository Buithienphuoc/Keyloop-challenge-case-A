package scheduler.modules.system.adapters

import play.api.mvc.*

import play.api.libs.json.*

import scheduler.http.{ApiController, Guard}
import scheduler.modules.identity.Role
import scheduler.modules.identity.Role.SYSTEM_MANAGER
import scheduler.modules.system.SystemJson.given
import scheduler.modules.system.PackageChanges
import scheduler.modules.system.application.{NewDealership, PackageAdminService, TenantService}
import scheduler.modules.system.application.PackageRequests.{NewPackage, given}
import scheduler.shared.Validate

/** HTTP adapter for platform administration. */
final class SystemController(cc: ControllerComponents, guard: Guard, tenants: TenantService, packages: PackageAdminService)
    extends ApiController(cc):
  private val sys = guard(SYSTEM_MANAGER)

  def list: Action[AnyContent] = sys.async(_ => json()(tenants.list()))

  private given Reads[Option[Role]] =
    (__ \ "role").readNullable(Validate.oneOf(Role.values.filter(Role.ListableBySystem.contains)))

  def listPeople: Action[AnyContent] = sys.async(req => json()(tenants.listPeople(query[Option[Role]](req))))

  def onboard: Action[AnyContent] = sys.async(req => json(Created)(tenants.onboard(body[NewDealership](req), req.log)))

  def listPackages: Action[AnyContent] = sys.async(_ => json()(packages.list()))

  def createPackage: Action[AnyContent] = sys.async(req => json(Created)(packages.create(body[NewPackage](req), req.log)))

  def updatePackage(id: String): Action[AnyContent] = sys.async { req =>
    json()(packages.update(uuid(id), body[PackageChanges](req), req.log))
  }
