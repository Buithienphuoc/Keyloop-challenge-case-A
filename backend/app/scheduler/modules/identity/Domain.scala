package scheduler.modules.identity

import scheduler.shared.AppError

enum Role:
  case CUSTOMER, SERVICE_ADVISOR, TECHNICIAN, ADMIN, SYSTEM_MANAGER, WORKSHOP_CONTROLLER, PARTS_STAFF

object Role:
  /** Staff roles work inside one dealership and only ever see that dealership's data. */
  val DealershipRoles: Set[Role] = Set(SERVICE_ADVISOR, TECHNICIAN, ADMIN, WORKSHOP_CONTROLLER, PARTS_STAFF)

/** The authenticated caller, attached to every request that passed authentication. */
final case class AuthContext(userId: String, role: Role, dealershipId: Option[String], fullName: String, email: String)

final case class PublicUser(
    id: String,
    email: String,
    fullName: String,
    phone: Option[String],
    role: Role,
    dealershipId: Option[String],
    dealershipName: Option[String]
)

/** Staff may only act inside their own dealership. */
def assertSameDealership(auth: AuthContext, dealershipId: String): Unit =
  if auth.role != Role.SYSTEM_MANAGER && !auth.dealershipId.contains(dealershipId) then
    throw AppError.forbidden("This belongs to another dealership")
