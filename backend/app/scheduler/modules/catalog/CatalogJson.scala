package scheduler.modules.catalog

import play.api.libs.json.*

import scheduler.shared.JsonFormats.{*, given}

/** JSON shapes of the catalogue API. */
object CatalogJson:
  given OWrites[Money] = Json.writes[Money]
  given OWrites[InspectionArea] = Json.writes[InspectionArea]
  given OWrites[SkillInfo] = Json.writes[SkillInfo]
  given OWrites[BayTypeInfo] = Json.writes[BayTypeInfo]
  given OWrites[ServicePackage] = Json.writes[ServicePackage]
  given OWrites[Dealership] = OWrites { d =>
    Json.obj(
      "id" -> d.id,
      "code" -> d.code,
      "name" -> d.name,
      "address" -> d.address,
      "phone" -> d.phone,
      "timezone" -> d.timezone.getId,
      "openingTime" -> hhmm(d.openingTime),
      "closingTime" -> hhmm(d.closingTime),
      "openDays" -> d.openDays,
      "slotMinutes" -> d.slotMinutes,
      "minLeadMinutes" -> d.minLeadMinutes,
      "maxDaysAhead" -> d.maxDaysAhead
    )
  }
