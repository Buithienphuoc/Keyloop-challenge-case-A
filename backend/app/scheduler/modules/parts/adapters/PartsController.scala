package scheduler.modules.parts.adapters

import play.api.mvc.*

import scheduler.http.{ApiController, Guard}
import scheduler.modules.identity.Role.*
import scheduler.modules.parts.PartsJson.given
import scheduler.modules.parts.application.PartsRequests.{*, given}
import scheduler.modules.parts.application.PartsService

/** HTTP adapter for replacement parts. */
final class PartsController(cc: ControllerComponents, guard: Guard, parts: PartsService) extends ApiController(cc):
  private val staff = guard(SERVICE_ADVISOR, TECHNICIAN, ADMIN, WORKSHOP_CONTROLLER, PARTS_STAFF)
  private val shelf = guard(PARTS_STAFF, ADMIN)

  def listParts: Action[AnyContent] = staff.async(req => json()(parts.listParts(req.auth)))

  def addPart: Action[AnyContent] = shelf.async(req => json(Created)(parts.addPart(req.auth, body[AddPart](req), req.log)))

  def adjustStock(id: String): Action[AnyContent] = shelf.async { req =>
    json()(parts.adjustStock(req.auth, uuid(id), body[StockAdjustment](req), req.log))
  }

  def listOrders: Action[AnyContent] = staff.async(req => json()(parts.listOrders(req.auth, query[OrderQuery](req))))

  def createOrder: Action[AnyContent] = staff.async(req => json(Created)(parts.createOrder(req.auth, body[NewOrder](req), req.log)))

  def changeOrderStatus(id: String): Action[AnyContent] = shelf.async { req =>
    json()(parts.changeStatus(req.auth, uuid(id), body[OrderStatusChange](req).status, req.log))
  }
