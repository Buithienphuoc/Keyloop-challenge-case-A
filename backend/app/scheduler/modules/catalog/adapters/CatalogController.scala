package scheduler.modules.catalog.adapters

import play.api.libs.json.*
import play.api.mvc.*

import scheduler.http.ApiController
import scheduler.modules.catalog.{CatalogReader, InspectionAreas}
import scheduler.modules.catalog.CatalogJson.given
import scheduler.modules.scheduling.domain.VehicleType
import scheduler.shared.Validate

/** Public, read-only catalogue: who you can book with and what you can book. */
final class CatalogController(cc: ControllerComponents, catalog: CatalogReader) extends ApiController(cc):

  private given Reads[Option[VehicleType]] = (__ \ "vehicleType").readNullable(Validate.oneOf(VehicleType.values))

  def dealerships: Action[AnyContent] = Action.async(json()(catalog.listDealerships()))

  def packages: Action[AnyContent] = Action.async(req => json()(catalog.listPackages(query[Option[VehicleType]](req))))

  def referenceData: Action[AnyContent] = Action.async {
    json()(Json.obj("inspectionAreas" -> InspectionAreas, "skills" -> catalog.listSkills(), "bayTypes" -> catalog.listBayTypes()))
  }
