package scheduler.modules.admin

import play.api.libs.json.*

import scheduler.modules.admin.application.{BayCreated, HoursUpdated, WorkshopView}
import scheduler.modules.identity.StaffMember
import scheduler.shared.JsonFormats.{hhmm, given}

/** JSON shapes of the administration API. */
object AdminJson:
  given OWrites[BayRecord] = Json.writes[BayRecord]
  given OWrites[TechnicianRecord] = Json.writes[TechnicianRecord]
  given OWrites[BayCreated] = Json.writes[BayCreated]

  given OWrites[HoursUpdated] = OWrites { u =>
    val h = u.hours
    Json.obj("openingTime" -> hhmm(h.openingTime), "closingTime" -> hhmm(h.closingTime), "openDays" -> h.openDays,
      "slotMinutes" -> h.slotMinutes, "minLeadMinutes" -> h.minLeadMinutes, "maxDaysAhead" -> h.maxDaysAhead,
      "upcomingOutsideHours" -> u.upcomingOutsideHours)
  }

  given OWrites[StaffMember] = OWrites { m =>
    Json.obj("id" -> m.user.id, "fullName" -> m.user.fullName, "email" -> m.user.email, "phone" -> m.user.phone,
      "role" -> m.user.role.toString, "isActive" -> m.isActive)
  }

  /** The day board's read-only view: no activity flags or login emails. */
  given OWrites[WorkshopView] = OWrites { w =>
    Json.obj(
      "bays" -> w.bays.map(b => Json.toJsObject(b) - "isActive"),
      "technicians" -> w.technicians.map(t => Json.toJsObject(t) - "isActive" - "loginEmail")
    )
  }
