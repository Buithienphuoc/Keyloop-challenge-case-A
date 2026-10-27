package scheduler.e2e

import org.openqa.selenium.By

/**
 * UC60 onboarding a dealership and UC61 its capacity figures: the system manager adds a
 * dealership, and customers can choose it but are told it cannot do the job yet.
 *
 * It adds a dealership and its first admin account, which the demo has no way to delete; the
 * names carry the run's tag. Reset the demo database to get rid of them (README, "start again
 * from an empty database").
 */
class OnboardingSpec extends E2ESuite with CustomerSteps:
  private val password = "E2eTest2026"
  private val code = s"E2E${run.takeRight(6)}".toUpperCase
  private val dealership = s"E2E Dealership $run"
  private val dealerAdminEmail = s"e2e-admin-$run@demo.vn"

  private def row(name: String): By = By.xpath(s"//tr[.//strong[normalize-space(.)=${Ui.literal(name)}]]")

  describe("System manager") {
    it("onboards a dealership together with its first admin (UC60)") {
      ui.signIn("sysadmin@demo.vn")
      ui.waitForPath("/system")
      ui.fillId("dc", code)
      ui.fillId("dn", dealership)
      ui.fillId("da", "1 E2E Street, Ho Chi Minh City")
      ui.fillId("an", s"E2E Admin $run")
      ui.fillId("ae", dealerAdminEmail)
      ui.fillId("ap", password)
      ui.clickButton("Create dealership")
      ui.eventually(s"$dealership is listed") { ui.all(row(dealership)).nonEmpty }
    }

    it("sees its capacity: no bays, no technicians, nothing booked yet (UC61)") {
      val cells = ui.visible(row(dealership)).findElements(By.tagName("td"))
      cells.get(0).getText should include(code)
      cells.get(2).getText shouldBe "0"
      cells.get(3).getText shouldBe "0"
      cells.get(4).getText shouldBe "0"
      ui.signOut()
    }

    it("its first admin account exists but has no pages in this version") {
      ui.signIn(dealerAdminEmail, password)
      ui.waitForPath("/not-used")
      ui.see("not used in this version")
      ui.signOut()
    }

    it("customers can choose it, and are told it cannot do the job yet") {
      ui.signIn("customer@demo.vn")
      ui.open("/book")
      ui.see("Which vehicle?")
      ui.click(By.xpath("//button[contains(@class,'choice') and .//*[normalize-space(.)='59-X1 234.56']]"))
      ui.click(ui.containing("button", "Essential maintenance"))
      ui.clickButton("Choose a time")
      ui.click(ui.containing("button", dealership))
      ui.see(s"$dealership cannot do this job")
      ui.all(By.cssSelector("button.slot")) shouldBe empty
      ui.signOut()
    }
  }
