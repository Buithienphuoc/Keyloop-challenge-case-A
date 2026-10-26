package scheduler.modules.identity.application

import scheduler.modules.identity.{PublicUser, StaffMember}
import scheduler.shared.DbSession

/**
 * What other modules need from identity to manage staff accounts (ISP): create a login,
 * possibly inside their own transaction, and list a dealership's staff. Admin and system
 * depend on this trait, not on IdentityService.
 */
trait UserAccounts:
  /** Creates an account; pass `s` to include it in your own transaction. 409 on a duplicate email. */
  def createUser(input: CreateUser, s: Option[DbSession] = None): PublicUser
  def listStaff(dealershipId: String): Seq[StaffMember]
