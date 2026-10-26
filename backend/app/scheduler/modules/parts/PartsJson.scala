package scheduler.modules.parts

import play.api.libs.json.*

import scheduler.shared.JsonFormats.given

/** JSON shapes of the parts API. */
object PartsJson:
  given OWrites[Part] = Json.writes[Part]
  given OWrites[PartOrderLine] = Json.writes[PartOrderLine]
  given OWrites[PartOrderAppointment] = Json.writes[PartOrderAppointment]
  given OWrites[PartOrderView] = OWrites { o =>
    Json.writes[PartOrderView].writes(o) ++ Json.obj("total" -> o.total, "currency" -> "VND")
  }
