package scheduler.integration

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.*

import scheduler.Seed
import scheduler.modules.identity.Passwords
import scheduler.support.Harness

/** The prod profile seeds the demo accounts with the configured passwords, never the public one. */
class ProfilesSpec extends AnyFunSpec with Matchers with BeforeAndAfterAll:
  private val CustomerPassword = "Prod-Customer-7f3a9c"
  private val AdminPassword = "Prod-Admin-2b8e41"

  private lazy val h = Harness(
    "2026-11-02T07:00:00+07:00",
    settings = Map(
      "scheduler.profile" -> "prod",
      "play.http.secret.key" -> "a-real-secret-for-the-prod-profile-test-0123456789",
      // One plain password and one given as its stored hash, as the deployment does.
      "scheduler.seed-account-passwords" ->
        s"customer@demo.vn=$CustomerPassword\nSysAdmin@demo.vn=${Passwords.hash(AdminPassword)}"
    )
  )
  override def afterAll(): Unit = h.close()

  private def login(email: String, password: String) =
    h.api("POST", "/api/auth/login", body = Json.obj("email" -> email, "password" -> password)).status

  describe("the prod profile") {
    it("signs in with the configured passwords, given plain or hashed") {
      login("customer@demo.vn", CustomerPassword) shouldBe 200
      login("sysadmin@demo.vn", AdminPassword) shouldBe 200
    }

    it("never accepts the public demo password") {
      login("customer@demo.vn", Seed.DemoPassword) shouldBe 401
      login("sysadmin@demo.vn", Seed.DemoPassword) shouldBe 401
    }

    it("locks the demo accounts that were given no password") {
      login("advisor.d1@demo.vn", Seed.DemoPassword) shouldBe 401
      login("parts.d1@demo.vn", Seed.DemoPassword) shouldBe 401
    }

    it("still loads the rest of the demo data") {
      (h.api("GET", "/api/dealerships").json \\ "code").map(_.as[String]) should contain allOf ("HCM-D1", "HCM-TD")
    }
  }
