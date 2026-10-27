package scheduler.modules.vehicles.adapters

import play.api.libs.json.*
import play.api.mvc.*

import scheduler.http.{ApiController, Guard}
import scheduler.modules.identity.Role.{CUSTOMER, SYSTEM_MANAGER}
import scheduler.modules.vehicles.{NewVehicle, OwnedVehicle, Vehicle}
import scheduler.modules.vehicles.application.VehicleService
import scheduler.shared.JsonFormats.given

/** HTTP adapter: parses requests, calls the service, shapes responses. No SQL here. */
final class VehicleController(cc: ControllerComponents, guard: Guard, vehicles: VehicleService) extends ApiController(cc):
  private given OWrites[Vehicle] = Json.writes[Vehicle]
  private given OWrites[OwnedVehicle] = OWrites(o => Json.toJsObject(o.vehicle) ++ Json.obj("ownerName" -> o.ownerName, "ownerEmail" -> o.ownerEmail))

  def listMine: Action[AnyContent] = guard(CUSTOMER).async(req => json()(vehicles.listMine(req.auth.userId)))

  def listAll: Action[AnyContent] = guard(SYSTEM_MANAGER).async(_ => json()(vehicles.listAll()))

  def add: Action[AnyContent] = guard(CUSTOMER).async { req =>
    json(Created)(vehicles.add(req.auth.userId, body[NewVehicle](req), req.log))
  }
