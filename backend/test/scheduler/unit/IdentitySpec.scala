package scheduler.unit

import java.time.Instant

import scala.collection.mutable

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scheduler.modules.identity.*
import scheduler.modules.identity.application.{CreateUser, IdentityRequests, IdentityService}
import scheduler.shared.{AppError, DbSession, Log}
import scheduler.support.Fakes.*

/** Password hashing, session tokens and the account use cases, against in-memory repositories. */
class IdentitySpec extends AnyFunSpec with Matchers:

  describe("Passwords") {
    lazy val stored = Passwords.hash("correct horse 1")

    it("verifies the right password and rejects a wrong one") {
      Passwords.verify("correct horse 1", stored) shouldBe true
      Passwords.verify("correct horse 2", stored) shouldBe false
    }

    it("salts every hash, and records scheme and cost in it") {
      val again = Passwords.hash("correct horse 1")
      again should not be stored
      stored.split('$').take(2).toSeq shouldBe Seq("pbkdf2-sha512", "210000")
    }

    it("rejects hashes it does not understand instead of failing") {
      Passwords.verify("x", "") shouldBe false
      Passwords.verify("x", "x") shouldBe false
      Passwords.verify("x", "md5$1$abc$def") shouldBe false
      Passwords.verify("x", "pbkdf2-sha512$many$abc$def") shouldBe false
    }

    it("rejects a corrupted stored hash instead of throwing (a 500 on login)") {
      Passwords.verify("x", "pbkdf2-sha512$210000$not base64!$abc") shouldBe false
      Passwords.verify("x", "pbkdf2-sha512$0$abc$def") shouldBe false
    }

    it("issues random session tokens and stores only their SHA-256") {
      val (token, hash) = Passwords.newSessionToken()
      val (other, _) = Passwords.newSessionToken()
      token should not be other
      hash shouldBe Passwords.hashToken(token)
      hash should fullyMatch regex "[0-9a-f]{64}"
      Passwords.hashToken("abc") shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
  }

  describe("Log.maskEmail") {
    it("keeps the first letter and the domain only") {
      Log.maskEmail("phuoc@example.com") shouldBe "p***@example.com"
      Log.maskEmail("no-at-sign") shouldBe "***"
      Log.maskEmail("trailing@") shouldBe "***"
    }
  }

  describe("assertSameDealership") {
    it("lets staff act in their own dealership and a system manager anywhere") {
      noException should be thrownBy assertSameDealership(auth(Role.ADMIN), "d1")
      noException should be thrownBy assertSameDealership(auth(Role.SYSTEM_MANAGER, dealershipId = None), "d9")
      (the[AppError] thrownBy assertSameDealership(auth(Role.ADMIN), "d2")).status shouldBe 403
      (the[AppError] thrownBy assertSameDealership(customer, "d1")).status shouldBe 403
    }
  }

  private final class FakeUsers extends UserRepository:
    val byEmail = mutable.Map.empty[String, StoredUser]
    def insert(s: DbSession, u: NewUserRecord): PublicUser =
      if byEmail.contains(u.email.toLowerCase) then throw AppError.conflict("EMAIL_TAKEN", "taken")
      val user = PublicUser(s"u${byEmail.size + 1}", u.email, u.fullName, u.phone, u.role, u.dealershipId, None)
      byEmail(u.email.toLowerCase) = StoredUser(user, u.passwordHash, isActive = true)
      user
    def findByEmail(email: String): Option[StoredUser] = byEmail.get(email.toLowerCase)
    def findById(id: String): Option[PublicUser] = byEmail.values.map(_.user).find(_.id == id)
    def listByDealership(dealershipId: String): Seq[StaffMember] = Nil

  private final class FakeSessions extends SessionRepository:
    val live = mutable.Map.empty[String, (String, Instant)]
    def create(userId: String, tokenHash: String, expiresAt: Instant): Unit = live(tokenHash) = (userId, expiresAt)
    def revoke(tokenHash: String): Boolean = live.remove(tokenHash).isDefined
    def findCaller(tokenHash: String): Option[AuthContext] =
      live.get(tokenHash).map((userId, _) => AuthContext(userId, Role.CUSTOMER, None, "N", "e"))

  private final class Fixture:
    val users = FakeUsers()
    val sessions = FakeSessions()
    val service = IdentityService(FakeDb(), users, sessions, sessionTtlHours = 2)

  private val register = IdentityRequests.Register(" Phuoc@Example.com ", "secret123", "Phuoc", None)

  describe("IdentityService") {
    it("registers a customer, hashes the password and signs them in") {
      val f = Fixture()
      val before = Instant.now()
      val session = f.service.registerCustomer(register, Log.silent)
      session.user.role shouldBe Role.CUSTOMER
      session.user.email shouldBe "Phuoc@Example.com" // trimmed
      val stored = f.users.findByEmail("phuoc@example.com").get
      stored.passwordHash should not include "secret123"
      Passwords.verify("secret123", stored.passwordHash) shouldBe true
      f.sessions.live.keySet shouldBe Set(Passwords.hashToken(session.token)) // the token itself is never stored
      session.expiresAt.isAfter(before.plusSeconds(2 * 3600 - 5)) shouldBe true
      session.expiresAt.isBefore(Instant.now().plusSeconds(2 * 3600 + 5)) shouldBe true
    }

    it("logs in with the right password, whatever the email's case") {
      val f = Fixture()
      f.service.registerCustomer(register, Log.silent)
      f.service.login(IdentityRequests.Login("PHUOC@example.com", "secret123"), Log.silent).user.fullName shouldBe "Phuoc"
    }

    it("answers the same 401 for a wrong password, an unknown email and an inactive account") {
      val f = Fixture()
      f.service.registerCustomer(register, Log.silent)
      val wrong = the[AppError] thrownBy f.service.login(IdentityRequests.Login("phuoc@example.com", "nope"), Log.silent)
      val unknown = the[AppError] thrownBy f.service.login(IdentityRequests.Login("who@example.com", "secret123"), Log.silent)
      val key = "phuoc@example.com"
      f.users.byEmail(key) = f.users.byEmail(key).copy(isActive = false)
      val inactive = the[AppError] thrownBy f.service.login(IdentityRequests.Login(key, "secret123"), Log.silent)
      Seq(wrong, unknown, inactive).map(e => (e.status, e.message)).distinct shouldBe Seq((401, "Email or password is incorrect"))
    }

    it("authenticates by token and logs out server-side") {
      val f = Fixture()
      val session = f.service.registerCustomer(register, Log.silent)
      f.service.authenticate(session.token).map(_.userId) shouldBe Some(session.user.id)
      f.service.logout(session.token, Log.silent)
      f.service.authenticate(session.token) shouldBe None
    }

    it("creates staff accounts inside the caller's transaction when given one") {
      val f = Fixture()
      val user = f.service.createUser(CreateUser("t@x.io", "secret123", "Tech", None, Role.TECHNICIAN, Some("d1")), Some(Session))
      user.dealershipId shouldBe Some("d1")
      (the[AppError] thrownBy f.service.createUser(CreateUser("T@X.io", "secret123", "Tech", None, Role.TECHNICIAN, None))).code shouldBe
        "EMAIL_TAKEN"
    }

    it("answers USER_NOT_FOUND for a user that no longer exists") {
      (the[AppError] thrownBy Fixture().service.getUser("ghost")).code shouldBe "USER_NOT_FOUND"
    }
  }
