package scheduler.modules.identity.application

import java.time.{Duration, Instant}

import play.api.libs.functional.syntax.*
import play.api.libs.json.*

import scheduler.modules.identity.*
import scheduler.shared.{AppError, Db, DbSession, Log, Metrics, Validate}

object IdentityRequests:
  val password: Reads[String] = Reads.StringReads
    .filter(Validate.error("Password must be at least 8 characters"))(_.length >= 8)
    .filter(Validate.error("Password must be at most 200 characters"))(_.length <= 200)
    .filter(Validate.error("Password must contain a letter"))(_.exists(_.isLetter))
    .filter(Validate.error("Password must contain a digit"))(_.exists(_.isDigit))

  private val phone: Reads[String] = Validate.matching("""\+?[0-9 ]{8,16}""".r, "Phone number looks invalid")

  final case class Register(email: String, password: String, fullName: String, phone: Option[String])
  given Reads[Register] = (
    (__ \ "email").read(Validate.email) and
      (__ \ "password").read(password) and
      (__ \ "fullName").read(Validate.text(2, 120)) and
      (__ \ "phone").readNullable(phone)
  )(Register.apply)

  final case class Login(email: String, password: String)
  given Reads[Login] = (
    (__ \ "email").read(Validate.text(1, 200)) and
      (__ \ "password").read(Reads.StringReads.filter(Validate.error("Required"))(_.nonEmpty))
  )(Login.apply)

final case class SessionInfo(token: String, expiresAt: Instant, user: PublicUser)

final case class CreateUser(email: String, password: String, fullName: String, phone: Option[String], role: Role, dealershipId: Option[String])

/** Accounts and sessions. Persistence is behind UserRepository and SessionRepository. */
final class IdentityService(db: Db, users: UserRepository, sessions: SessionRepository, sessionTtlHours: Double) extends Authenticator:
  import IdentityRequests.*

  /** Creates an account; pass `s` to include it in your own transaction. 409 on a duplicate email. */
  def createUser(input: CreateUser, s: Option[DbSession] = None): PublicUser =
    val record = NewUserRecord(input.email.trim, Passwords.hash(input.password), input.fullName, input.phone, input.role, input.dealershipId)
    s match
      case Some(session) => users.insert(session, record)
      case None          => db.run(users.insert(_, record))

  def registerCustomer(input: Register, log: Log): SessionInfo =
    val user = createUser(CreateUser(input.email, input.password, input.fullName, input.phone, Role.CUSTOMER, None))
    log.info("auth.registered", "userId" -> user.id, "role" -> user.role)
    startSession(user, log)

  def login(input: Login, log: Log): SessionInfo =
    val stored = users.findByEmail(input.email)
    // Always run a password check so response time does not reveal whether the email exists.
    val passwordOk = Passwords.verify(input.password, stored.map(_.passwordHash).getOrElse(Passwords.dummyHash))
    stored match
      case Some(u) if passwordOk && u.isActive => startSession(u.user, log)
      case _ =>
        Metrics.authFailures.inc()
        val reason = if stored.isEmpty then "unknown_email" else if !passwordOk then "bad_password" else "inactive"
        log.warn("auth.login_failed", "email" -> Log.maskEmail(input.email), "reason" -> reason)
        throw AppError.unauthorized("Email or password is incorrect")

  // Sessions use the real clock on purpose: PostgreSQL checks expiry against its own now(),
  // so a test clock pinned in the past or future must not shift session lifetimes.
  private def startSession(user: PublicUser, log: Log): SessionInfo =
    val (token, tokenHash) = Passwords.newSessionToken()
    val expiresAt = Instant.now().plus(Duration.ofMinutes(math.round(sessionTtlHours * 60)))
    sessions.create(user.id, tokenHash, expiresAt)
    log.info("auth.login_succeeded", "userId" -> user.id, "role" -> user.role)
    SessionInfo(token, expiresAt, user)

  /** Revokes the session server-side, so the token is useless even if it was copied. */
  def logout(token: String, log: Log): Unit =
    val revoked = sessions.revoke(Passwords.hashToken(token))
    log.info("auth.logout", "revoked" -> revoked)

  def authenticate(token: String): Option[AuthContext] = sessions.findCaller(Passwords.hashToken(token))

  def getUser(userId: String): PublicUser =
    users.findById(userId).getOrElse(throw AppError.badRequest("USER_NOT_FOUND", "User no longer exists"))

  def listStaff(dealershipId: String): Seq[StaffMember] = users.listByDealership(dealershipId)
