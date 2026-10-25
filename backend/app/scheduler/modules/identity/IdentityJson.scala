package scheduler.modules.identity

import play.api.libs.json.*

import scheduler.modules.identity.application.SessionInfo
import scheduler.shared.JsonFormats.given

/** JSON shapes of the identity API. */
object IdentityJson:
  given OWrites[PublicUser] = Json.writes[PublicUser]
  given OWrites[SessionInfo] = Json.writes[SessionInfo]
