package scheduler.modules.admin.adapters

import play.api.libs.json.*
import play.api.mvc.*

import scheduler.http.{ApiController, Guard}
import scheduler.modules.admin.AdminJson.given
import scheduler.modules.admin.application.AdminRequests.{*, given}
import scheduler.modules.admin.application.WorkshopAdminService
import scheduler.modules.identity.IdentityJson.given
import scheduler.modules.identity.Role.*
import scheduler.shared.Validate

/** HTTP adapter for dealership administration. No SQL here. */
final class AdminController(cc: ControllerComponents, guard: Guard, admin: WorkshopAdminService) extends ApiController(cc):
  private val onlyAdmin = guard(ADMIN)
  private val ok = Json.obj("ok" -> true)
  private val readers = guard(ADMIN, SYSTEM_MANAGER)
  private val dealership: Reads[Option[String]] = (__ \ "dealershipId").readNullable(Validate.uuid)

  def listBays: Action[AnyContent] = readers.async(req => json()(admin.listBays(req.auth, query[Option[String]](req)(using dealership))))

  def addBay: Action[AnyContent] = onlyAdmin.async(req => json(Created)(admin.addBay(req.auth, body[NewBay](req), req.log)))

  def setBayActive(id: String): Action[AnyContent] = onlyAdmin.async { req =>
    json() {
      admin.setBayActive(req.auth, uuid(id), body[SetActive](req).isActive, req.log)
      ok
    }
  }

  def listTechnicians: Action[AnyContent] = readers.async(req => json()(admin.listTechnicians(req.auth, query[Option[String]](req)(using dealership))))

  def addTechnician: Action[AnyContent] = onlyAdmin.async { req =>
    json(Created)(Json.obj("id" -> admin.addTechnician(req.auth, body[NewTechnician](req), req.log)))
  }

  def setTechnicianActive(id: String): Action[AnyContent] = onlyAdmin.async { req =>
    json() {
      admin.setTechnicianActive(req.auth, uuid(id), body[SetActive](req).isActive, req.log)
      ok
    }
  }

  def activeResources: Action[AnyContent] = guard(SERVICE_ADVISOR, ADMIN, WORKSHOP_CONTROLLER).async(req => json()(admin.activeResources(req.auth)))

  def listStaff: Action[AnyContent] = onlyAdmin.async(req => json()(admin.listStaff(req.auth)))

  def addStaff: Action[AnyContent] = onlyAdmin.async(req => json(Created)(admin.addStaff(req.auth, body[NewStaff](req), req.log)))

  def updateHours: Action[AnyContent] = onlyAdmin.async(req => json()(admin.updateHours(req.auth, body[Hours](req), req.log)))
