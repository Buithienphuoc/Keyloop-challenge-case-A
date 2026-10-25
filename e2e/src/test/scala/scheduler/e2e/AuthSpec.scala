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

    it("fills the form from a demo account button and opens the customer's home") {
      ui.open("/login")
      ui.click(ui.containing("button", "customer@demo.vn"))
      ui.clickButton("Sign in")
      ui.visible(ui.exact("a", "Book a service"))
      ui.see("Lê Minh Tuấn")
      ui.path shouldBe "/"
    }

    it("sends a customer who opens a staff page back to their own home") {
      ui.open("/staff/board")
      ui.waitForPath("/")
      ui.notSee("Workshop day") shouldBe true
    }

    it("signs out, after which pages need a new sign-in") {
      ui.signOut()
      ui.open("/appointments")
      ui.waitForPath("/login")
    }
  }

  describe("Each role lands on its own start page") {
    val starts = Seq(
      "advisor.d1@demo.vn" -> "/staff/board",
      "tech.hung@demo.vn" -> "/jobs",
      "controller.d1@demo.vn" -> "/staff/board",
      "parts.d1@demo.vn" -> "/parts",
      "sysadmin@demo.vn" -> "/system"
    )
    for (email, start) <- starts do
      it(s"$email starts at $start") {
        ui.signIn(email)
        ui.waitForPath(start)
        ui.signOut()
      }
  }
