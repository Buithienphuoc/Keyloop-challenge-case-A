package scheduler.modules.system.application

import java.time.{LocalTime, ZoneId}

import play.api.libs.functional.syntax.*
import play.api.libs.json.*

import scheduler.modules.identity.Role
import scheduler.modules.identity.application.{CreateUser, IdentityRequests, UserAccounts}
import scheduler.modules.system.*
import scheduler.shared.{AppError, Db, Log, Validate}

final case class NewAdmin(fullName: String, email: String, password: String)

final case class NewDealership(
    code: String,
    name: String,
    address: String,
    phone: Option[String],
    timezone: ZoneId,
    openingTime: LocalTime,
    closingTime: LocalTime,
    openDays: List[Int],
    slotMinutes: Int,
    minLeadMinutes: Int,
    maxDaysAhead: Int,
    admin: NewAdmin
)

object NewDealership:
  private given Reads[NewAdmin] = (
    (__ \ "fullName").read(Validate.text(2, 120)) and
      (__ \ "email").read(Validate.email) and
      (__ \ "password").read(IdentityRequests.password)
  )(NewAdmin.apply)

  given Reads[NewDealership] = (
    (__ \ "code").read(Validate.matching("[A-Za-z0-9-]{2,12}".r, "2-12 letters, digits or dashes").map(_.toUpperCase)) and
      (__ \ "name").read(Validate.text(3, 120)) and
      (__ \ "address").read(Validate.text(5, 300)) and
      (__ \ "phone").readNullable(Validate.text(0, 30)).map(_.filter(_.nonEmpty)) and
      (__ \ "timezone").read(Validate.timeZone) and
      (__ \ "openingTime").read(Validate.hhmm) and
      (__ \ "closingTime").read(Validate.hhmm) and
      (__ \ "openDays").read(Validate.listOf(Validate.int(1, 7), min = 1, max = 7)) and
      (__ \ "slotMinutes").readWithDefault(30)(Reads.IntReads.filter(Validate.error("Must be 15, 30 or 60"))(Set(15, 30, 60))) and
      (__ \ "minLeadMinutes").readWithDefault(60)(Validate.int(0, 7 * 24 * 60)) and
      (__ \ "maxDaysAhead").readWithDefault(60)(Validate.int(1, 365)) and
      (__ \ "admin").read[NewAdmin]
  )(NewDealership.apply)

final case class Onboarded(dealershipId: String, adminUserId: String)

/** Platform-level administration: onboarding dealerships (tenants) and their first admin. */
final class TenantService(db: Db, tenants: TenantRepository, identity: UserAccounts):

  def list(): Seq[DealershipSummary] = tenants.listWithCapacity()

  /** The dealership and its first admin are created together or not at all. */
  def onboard(input: NewDealership, log: Log): Onboarded =
    if !input.openingTime.isBefore(input.closingTime) then
      throw AppError.unprocessable("INVALID_HOURS", "Closing time must be after opening time")
    val result = db.tx { s =>
      val dealershipId = tenants.insert(
        s,
        NewDealershipRecord(input.code, input.name, input.address, input.phone, input.timezone, input.openingTime, input.closingTime,
          input.openDays.distinct, input.slotMinutes, input.minLeadMinutes, input.maxDaysAhead)
      )
      val admin = input.admin
      val user = identity.createUser(CreateUser(admin.email, admin.password, admin.fullName, None, Role.ADMIN, Some(dealershipId)), Some(s))
      Onboarded(dealershipId, user.id)
    }
    log.info("system.dealership_created", "dealershipId" -> result.dealershipId, "adminUserId" -> result.adminUserId, "code" -> input.code)
    result
