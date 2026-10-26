package scheduler.unit

import java.nio.file.Path

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scheduler.Seed
import scheduler.modules.identity.Passwords
import scheduler.shared.{Config, Profile}

/** The dev / prod profiles: parsing, start-up checks and which password each demo account gets. */
class ProfileSpec extends AnyFunSpec with Matchers:
  private val base = Config(
    profile = Profile.Dev, databaseUrl = None, embeddedPgPort = 54330, dataDir = Path.of("."), slowQueryMs = 200,
    sessionTtlHours = 12, seedDemoData = true, corsOrigins = Nil, dbPoolSize = 4, holdMinutes = 10,
    holdSweepSeconds = 0, seedAccountPasswords = Map.empty
  )
  private val prod = base.copy(profile = Profile.Prod, databaseUrl = Some("jdbc:postgresql://db/scheduler_prod"))
  private val RealSecret = "f3c1d2e4a5b6"

  describe("Profile.parse") {
    it("accepts dev and prod in any case, and nothing else") {
      Profile.parse("dev") shouldBe Profile.Dev
      Profile.parse(" PROD ") shouldBe Profile.Prod
      Profile.parse("production") shouldBe Profile.Prod
      an[IllegalArgumentException] should be thrownBy Profile.parse("staging")
    }
  }

  describe("Config.parsePasswords") {
    it("reads email=password entries split by new lines or semicolons, emails in lower case") {
      Config.parsePasswords("A@demo.vn=one=1\r\n b@demo.vn = two ;; ") shouldBe
        Map("a@demo.vn" -> "one=1", "b@demo.vn" -> "two")
      Config.parsePasswords("") shouldBe Map.empty
    }

    it("rejects an entry without a password") {
      an[IllegalArgumentException] should be thrownBy Config.parsePasswords("a@demo.vn")
      an[IllegalArgumentException] should be thrownBy Config.parsePasswords("a@demo.vn=")
    }
  }

  describe("Config.checkProfile") {
    it("keeps the dev profile off hosted platforms") {
      noException should be thrownBy Config.checkProfile(base, "dev-only-secret", Map.empty)
      an[IllegalArgumentException] should be thrownBy
        Config.checkProfile(base, RealSecret, Map("CONTAINER_APP_NAME" -> "scheduler-api"))
    }

    it("needs a real database and secret key in prod") {
      noException should be thrownBy Config.checkProfile(prod, RealSecret, Map("CONTAINER_APP_NAME" -> "scheduler-api"))
      an[IllegalArgumentException] should be thrownBy Config.checkProfile(prod.copy(databaseUrl = None), RealSecret, Map.empty)
      an[IllegalArgumentException] should be thrownBy Config.checkProfile(prod, "dev-only-secret", Map.empty)
    }
  }

  describe("Seed.passwordsFor") {
    it("gives every account the documented password in dev") {
      val pw = Seed.passwordsFor(base)
      Passwords.verify(Seed.DemoPassword, pw("customer@demo.vn")) shouldBe true
      Passwords.verify(Seed.DemoPassword, pw("anyone@demo.vn")) shouldBe true
    }

    it("uses the configured passwords in prod and locks the accounts left out") {
      val hashed = Passwords.hash("Hashed-Pass-9")
      val pw = Seed.passwordsFor(prod.copy(seedAccountPasswords = Map("a@demo.vn" -> "Plain-Pass-9", "b@demo.vn" -> hashed)))
      Passwords.verify("Plain-Pass-9", pw("A@demo.vn")) shouldBe true
      pw("b@demo.vn") shouldBe hashed
      pw("c@demo.vn") shouldBe Seed.Locked
      Passwords.verify(Seed.DemoPassword, pw("c@demo.vn")) shouldBe false
    }

    it("refuses the public demo password in prod") {
      an[IllegalArgumentException] should be thrownBy
        Seed.passwordsFor(prod.copy(seedAccountPasswords = Map("a@demo.vn" -> Seed.DemoPassword)))
    }
  }
