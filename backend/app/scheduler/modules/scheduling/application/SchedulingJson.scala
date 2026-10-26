package scheduler.modules.scheduling.application

import play.api.libs.json.*

import scheduler.modules.catalog.CatalogJson.given
import scheduler.modules.scheduling.domain.{ResourceLoad, Slot}
import scheduler.shared.JsonFormats.{*, given}

/** JSON shapes of the scheduling API (see conf/openapi.yaml). */
object SchedulingJson:
  /** `reason` is only present when the slot cannot be booked. */
  given OWrites[Slot] = OWrites { s =>
    Json.obj("start" -> offsetIso(s.start), "end" -> offsetIso(s.end), "available" -> s.available, "capacity" -> s.capacity) ++
      s.reason.fold(Json.obj())(r => Json.obj("reason" -> r.toString))
  }

  given OWrites[AvailabilityResponse] = Json.writes[AvailabilityResponse]

  given OWrites[AppointmentView.DealershipRef] = Json.writes[AppointmentView.DealershipRef]
  given OWrites[AppointmentView.VehicleRef] = Json.writes[AppointmentView.VehicleRef]
  given OWrites[AppointmentView.CustomerRef] = Json.writes[AppointmentView.CustomerRef]
  given OWrites[AppointmentView.ServiceInfo] = Json.writes[AppointmentView.ServiceInfo]
  given OWrites[AppointmentView.Ref] = Json.writes[AppointmentView.Ref]
  given OWrites[AppointmentView] = Json.writes[AppointmentView]

  private def load(r: ResourceLoad, tagsKey: String, tags: JsValue): JsObject =
    Json.obj("id" -> r.id, "name" -> r.name, tagsKey -> tags, "availableMinutes" -> r.availableMinutes,
      "bookedMinutes" -> r.bookedMinutes, "jobs" -> r.jobs, "utilisation" -> r.utilisation)

  given OWrites[WorkloadView] = OWrites { w =>
    Json.obj(
      "date" -> w.date,
      "timezone" -> w.timezone,
      "technicians" -> w.load.technicians.map(t => load(t, "skills", Json.toJson(t.tags))),
      "bays" -> w.load.bays.map(b => load(b, "bayType", JsString(b.tags.headOption.getOrElse(""))))
    )
  }
