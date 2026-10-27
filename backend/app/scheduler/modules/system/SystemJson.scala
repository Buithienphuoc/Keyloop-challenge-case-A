package scheduler.modules.system

import play.api.libs.json.*

import scheduler.modules.identity.StaffMember
import scheduler.modules.system.application.Onboarded
import scheduler.shared.JsonFormats.given

/** JSON shapes of the platform administration API. */
object SystemJson:
  given OWrites[DealershipSummary] = Json.writes[DealershipSummary]
  given OWrites[Onboarded] = Json.writes[Onboarded]
  given OWrites[PackageRecord] = Json.writes[PackageRecord]

  /** A person as the system manager lists them: who, which role, which dealership, active or not. */
  given OWrites[StaffMember] = OWrites { m =>
    Json.obj("id" -> m.user.id, "fullName" -> m.user.fullName, "email" -> m.user.email, "phone" -> m.user.phone,
      "role" -> m.user.role.toString, "dealershipId" -> m.user.dealershipId, "dealershipName" -> m.user.dealershipName,
      "isActive" -> m.isActive)
  }
