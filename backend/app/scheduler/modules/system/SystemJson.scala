package scheduler.modules.system

import play.api.libs.json.*

import scheduler.modules.system.application.Onboarded
import scheduler.shared.JsonFormats.given

/** JSON shapes of the platform administration API. */
object SystemJson:
  given OWrites[DealershipSummary] = Json.writes[DealershipSummary]
  given OWrites[Onboarded] = Json.writes[Onboarded]
