package scheduler.modules.consultation

import play.api.libs.json.*

import scheduler.shared.JsonFormats.given

/** JSON shapes of the consultation API. */
object ConsultationJson:
  given OWrites[ConsultationView.VehicleRef] = Json.writes[ConsultationView.VehicleRef]
  given OWrites[ConsultationView.DealershipRef] = Json.writes[ConsultationView.DealershipRef]
  given OWrites[ConsultationView.CustomerRef] = Json.writes[ConsultationView.CustomerRef]
  given OWrites[ConsultationView.AppointmentRef] = Json.writes[ConsultationView.AppointmentRef]
  given OWrites[ConsultationView] = Json.writes[ConsultationView]
