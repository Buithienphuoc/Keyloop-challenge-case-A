package scheduler.modules.identity

import java.time.Instant

import scheduler.shared.DbSession

final case class StoredUser(user: PublicUser, passwordHash: String, isActive: Boolean)

final case class StaffMember(user: PublicUser, isActive: Boolean)

final case class NewUserRecord(
    email: String,
    passwordHash: String,
    fullName: String,
    phone: Option[String],
    role: Role,
    dealershipId: Option[String]
)

/**
 * Port: user accounts. `insert` takes the session so another module can include it in
 * its own transaction (a technician with a login, a dealership with its first admin).
 */
trait UserRepository:
  /** Throws AppError 409 EMAIL_TAKEN on a duplicate email. */
  def insert(s: DbSession, user: NewUserRecord): PublicUser
  def findByEmail(email: String): Option[StoredUser]
  def findById(id: String): Option[PublicUser]
  def listByDealership(dealershipId: String): Seq[StaffMember]

/** Port: login sessions, stored as token hashes. */
trait SessionRepository:
  def create(userId: String, tokenHash: String, expiresAt: Instant): Unit

  /** Returns true if a live session was revoked. */
  def revoke(tokenHash: String): Boolean

  /** The caller behind a live, unexpired session of an active user. */
  def findCaller(tokenHash: String): Option[AuthContext]

/** The one thing the HTTP guard needs from identity (ISP): resolve a token to a caller. */
trait Authenticator:
  def authenticate(token: String): Option[AuthContext]
