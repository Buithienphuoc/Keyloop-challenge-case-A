package scheduler.e2e

import org.openqa.selenium.By

/** Signing in and out, and that each role only reaches its own pages. */
class AuthSpec extends E2ESuite:
  describe("Sign in") {
    it("shows a clear message for a wrong password and stays on the sign-in page") {
      ui.open("/login")
      ui.fill("Email", "customer@demo.vn")
      ui.fill("Password", "not-the-password1")
      ui.clickButton("Sign in")
      ui.visible(By.cssSelector("[role='alert']"))
      ui.path shouldBe "/login"
    }

    it("shows no demo account on a production build and signs in with typed credentials") {
      ui.open("/login")
      ui.fill("Email", "customer@demo.vn")
      // The one-tap demo accounts are for the dev server (npm run dev) on localhost only.
      if !Settings.baseUrl.endsWith(":5173") then
        ui.notSee(Settings.DemoPassword) shouldBe true
        ui.notSee("Demo accounts") shouldBe true
      ui.fill("Password", Settings.DemoPassword)
      ui.clickButton("Sign in")
      ui.visible(ui.exact("a", "Book a service"))
      ui.see("Lê Minh Tuấn")
      ui.path shouldBe "/"
    }

    it("sends a customer who opens a system page back to their own home") {
      ui.open("/system/data")
      ui.waitForPath("/")
      ui.notSee("All data") shouldBe true
    }

    it("signs out, after which pages need a new sign-in") {
      ui.signOut()
      ui.open("/appointments")
      ui.waitForPath("/login")
    }
  }

  describe("Each role lands on its own start page") {
    // Advisors, admins and other staff accounts stay in the database but have no pages.
    val starts = Seq(
      "tech.hung@demo.vn" -> "/jobs",
      "sysadmin@demo.vn" -> "/system",
      "advisor.d1@demo.vn" -> "/not-used"
    )
    for (email, start) <- starts do
      it(s"$email starts at $start") {
        ui.signIn(email)
        ui.waitForPath(start)
        ui.signOut()
      }
  }
