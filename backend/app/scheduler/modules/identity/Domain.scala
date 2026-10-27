package scheduler.modules.identity

import scheduler.shared.AppError

enum Role:
  case CUSTOMER, SERVICE_ADVISOR, TECHNICIAN, ADMIN, SYSTEM_MANAGER, WORKSHOP_CONTROLLER, PARTS_STAFF

object Role:
  /** Staff roles work inside one dealership and only ever see that dealership's data. */
  val DealershipRoles: Set[Role] = Set(SERVICE_ADVISOR, TECHNICIAN, ADMIN, WORKSHOP_CONTROLLER, PARTS_STAFF)

  /**
   * Roles that read every appointment and consultation request of every dealership and may
   * move their status (within the lifecycle rules). Nobody can delete either.
   */
  val AllWorkshopRoles: Set[Role] = Set(SERVICE_ADVISOR, TECHNICIAN, ADMIN)

  /** The people a system manager can list. */
  val ListableBySystem: Set[Role] = Set(CUSTOMER, SERVICE_ADVISOR, TECHNICIAN, ADMIN)

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
 * Who may see (and, role permitting, move the status of) a record owned by a customer at a
 * dealership: the customer themselves; advisors, technicians and admins of any dealership; the
 * system manager; other staff of that dealership only. A customer asking for someone else's
 * record gets 404, so ids do not leak.
 */
def assertCanSee(auth: AuthContext, ownerId: String, dealershipId: String, what: String): Unit =
  if auth.role == Role.CUSTOMER then
    if ownerId != auth.userId then throw AppError.notFound(what)
  else if !Role.AllWorkshopRoles.contains(auth.role) then assertSameDealership(auth, dealershipId)

/**
 * Which dealership a staff list covers: `None` = every dealership. Without a choice it is the
 * caller's own; "all" or another dealership's id is allowed for the roles that see every workshop.
 */
def listScope(auth: AuthContext, requested: Option[String]): Option[String] =
  requested match
    case None => Some(auth.requireDealership)
    case Some(d) if auth.dealershipId.contains(d) => Some(d)
    case Some(_) if !Role.AllWorkshopRoles.contains(auth.role) => throw AppError.forbidden("This belongs to another dealership")
    case Some(DealershipScope.All) => None
    case Some(d) => Some(d)

object DealershipScope:
  /** The `dealershipId` query value that means every dealership. */
  val All = "all"

extension (auth: AuthContext)
  /** The caller's dealership; 403 for an account that is not attached to one. */
  def requireDealership: String =
    auth.dealershipId.getOrElse(throw AppError.forbidden("Your account is not attached to a dealership"))
