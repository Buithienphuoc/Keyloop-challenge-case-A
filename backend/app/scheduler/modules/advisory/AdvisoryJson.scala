package scheduler.modules.advisory

import play.api.libs.json.*

import scheduler.shared.JsonFormats.given

/** JSON shapes of the advisor appointment API. */
object AdvisoryJson:
  given OWrites[AdvisorAppointmentView.DealershipRef] = Json.writes[AdvisorAppointmentView.DealershipRef]
  given OWrites[AdvisorAppointmentView.CustomerRef] = Json.writes[AdvisorAppointmentView.CustomerRef]
  given OWrites[AdvisorAppointmentView.Ref] = Json.writes[AdvisorAppointmentView.Ref]
  given OWrites[AdvisorAppointmentView] = Json.writes[AdvisorAppointmentView]
