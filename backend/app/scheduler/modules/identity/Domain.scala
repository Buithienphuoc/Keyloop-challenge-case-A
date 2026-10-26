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

/**
 * Who may see a record owned by a customer at a dealership: the customer themselves, or staff
 * of that dealership. A customer asking for someone else's record gets 404, so ids do not leak.
 */
def assertCanSee(auth: AuthContext, ownerId: String, dealershipId: String, what: String): Unit =
  if auth.role == Role.CUSTOMER then
    if ownerId != auth.userId then throw AppError.notFound(what)
  else assertSameDealership(auth, dealershipId)

extension (auth: AuthContext)
  /** The caller's dealership; 403 for an account that is not attached to one. */
  def requireDealership: String =
    auth.dealershipId.getOrElse(throw AppError.forbidden("Your account is not attached to a dealership"))
