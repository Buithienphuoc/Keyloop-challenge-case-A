package scheduler.e2e

import org.openqa.selenium.By

/**
 * The staff use cases that change the set-up, end to end: what an admin or a system manager
 * creates, the person it was created for can then use. UC50 bays, UC51 technicians, UC52
 * staff accounts, UC60 onboarding a dealership and UC61 its capacity figures.
 *
 * It adds rows the demo has no way to delete (a bay and a technician, both left inactive, a
 * staff account and a dealership); every name carries the run's tag. Reset the demo database
 * to get rid of them (README, "start again from an empty database").
 */
class AdminSpec extends E2ESuite with CustomerSteps:
  private val password = "E2eTest2026"
  private val bay = s"E2E Stand $run"
  private val tech = s"E2E Tech $run"
  private val techEmail = s"e2e-tech-$run@demo.vn"
  private val advisor = s"E2E Advisor $run"
  private val advisorEmail = s"e2e-advisor-$run@demo.vn"
  private val code = s"E2E${run.takeRight(6)}".toUpperCase
  private val dealership = s"E2E Dealership $run"
  private val dealerAdminEmail = s"e2e-admin-$run@demo.vn"

  private def tab(label: String): Unit = ui.click(ui.exact("button", label))
  private def row(name: String): By = By.xpath(s"//tr[.//strong[normalize-space(.)=${Ui.literal(name)}]]")

  describe("Dealership admin (District 1)") {
    it("adds a service bay and takes it out of service (UC50)") {
      ui.signIn("admin.d1@demo.vn")
      ui.open("/admin")
      tab("Service bays")
      ui.fill("Name", bay)
      ui.select("Type").selectByVisibleText("Motorbike stand")
      ui.clickButton("Add bay")
      ui.eventually(s"$bay is in use") { ui.visible(row(bay)).getText.contains("In use") }
      ui.click(ui.visible(row(bay)).findElement(By.xpath(".//button[normalize-space(.)='Take out of service']")))
      ui.eventually(s"$bay is out of service") { ui.visible(row(bay)).getText.contains("Out of service") }
    }

    it("adds a technician with skills and a login (UC51)") {
      tab("Technicians")
      ui.fill("Name", tech)
      ui.click(By.xpath("//label[contains(@class,'check') and normalize-space(.)='Brakes & suspension']"))
      ui.fill("Login email (optional)", techEmail)
      ui.fill("Temporary password", password)
      ui.clickButton("Add technician")
      ui.eventually(s"$tech is listed") { ui.all(row(tech)).nonEmpty }
      val text = ui.visible(row(tech)).getText
      text should include(techEmail)
      text should include("General servicing")
      text should include("Brakes & suspension")
    }

    it("creates a service advisor account (UC52)") {
      tab("Staff accounts")
      ui.fill("Name", advisor)
      ui.fill("Email", advisorEmail)
      ui.fill("Temporary password", password)
      ui.select("Role").selectByVisibleText("Service advisor")
      ui.clickButton("Create account")
      ui.eventually(s"$advisor is listed") { ui.all(row(advisor)).nonEmpty }
      ui.visible(row(advisor)).getText should include("Service advisor")
      ui.signOut()
    }

    it("the new technician signs in to their jobs, the new advisor to the day board") {
      ui.signIn(techEmail, password)
      ui.waitForPath("/jobs")
      ui.see("My jobs")
      ui.signOut()
      ui.signIn(advisorEmail, password)
      ui.waitForPath("/staff/board")
      ui.see("Saigon Moto Care – District 1")
      ui.signOut()
    }

    it("deactivates the new technician again") {
      ui.signIn("admin.d1@demo.vn")
      ui.open("/admin")
      tab("Technicians")
      ui.click(ui.visible(row(tech)).findElement(By.xpath(".//button[normalize-space(.)='Deactivate']")))
      ui.eventually(s"$tech can be reactivated") {
        ui.visible(row(tech)).findElements(By.xpath(".//button[normalize-space(.)='Reactivate']")).size == 1
      }
      ui.signOut()
    }
  }

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

    it("its admin signs in to an empty workshop") {
      ui.signIn(dealerAdminEmail, password)
      ui.waitForPath("/staff/board") // an admin starts where an advisor does
      ui.see(dealership)
      ui.open("/admin")
      ui.see("Bays & team")
      ui.see("Add a bay")
      ui.all(By.cssSelector("table.table tbody tr")) shouldBe empty
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
