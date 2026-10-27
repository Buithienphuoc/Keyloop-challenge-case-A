package scheduler.e2e

import org.openqa.selenium.By

/**
 * Who sees what. A customer sees only their own bookings and chooses the workshop. Advisors,
 * technicians and admins read every appointment of every workshop and move its status, and
 * nothing can be deleted. The system manager lists customers, advisors, technicians, admins and
 * workshops. The booking made here ends finished and paid.
 */
class RolePermissionsSpec extends E2ESuite with CustomerSteps:
  private val email = s"e2e-roles-$run@demo.vn"
  private val password = "E2eTest2026"
  private val plate = newPlate("59-R1")
  private val day = quietWeekday(4)

  private var reference: Option[String] = None
  private def needsBooking(): String = { assume(reference.isDefined, "the booking step did not finish"); reference.get }

  private def row(ref: String): By =
    By.xpath(s"//div[contains(@class,'item') and @data-appointment=${Ui.literal(ref)}]")

  private def openAllAppointments(workshop: String): Unit =
    ui.open("/staff/appointments")
    ui.see("All appointments")
    ui.select("Workshop").selectByVisibleText(workshop)

  private def press(ref: String, action: String, thenStatus: String): Unit =
    ui.click(ui.visible(row(ref)).findElement(By.xpath(s".//button[normalize-space(.)=${Ui.literal(action)}]")))
    ui.eventually(s"$ref is $thenStatus") { ui.visible(row(ref)).getText.contains(thenStatus) }

  private def noDeleteButton(): Unit =
    ui.all(By.xpath("//button[contains(translate(normalize-space(.), 'DELETE', 'delete'), 'delete')]")) shouldBe empty

  describe("Customer") {
    it("chooses the workshop when booking, and sees only their own appointments") {
      register("E2E Roles", email, password)
      ui.open("/vehicles")
      ui.clickButton("Add your first vehicle")
      fillVehicle("Honda", "Lead", plate)
      ui.see(plate)
      reference = Some(bookEssential(day))
      ui.open("/appointments")
      ui.visible(appointmentRow(reference.get)).getText should include("District 1")
      ui.all(By.cssSelector(".item")).map(_.getText).foreach(_ should include(plate))
      ui.open("/staff/appointments")
      ui.waitForPath("/")
      ui.signOut()
    }
  }

  describe("Staff of every workshop") {
    it("an advisor of another workshop finds the booking under All workshops") {
      val ref = needsBooking()
      ui.signIn("advisor.td@demo.vn")
      openAllAppointments("My workshop")
      ui.eventually("the list has loaded") { ui.all(By.cssSelector(".loading")).isEmpty }
      ui.notSee(ref) shouldBe true
      openAllAppointments("All workshops")
      ui.visible(row(ref)).getText should include("District 1")
      noDeleteButton()
      ui.signOut()
    }

    it("a technician sees every job, also one assigned to someone else, and starts it") {
      val ref = needsBooking()
      ui.signIn("tech.hung@demo.vn")
      openAllAppointments("My workshop")
      ui.visible(row(ref))
      noDeleteButton()
      ui.visible(row(ref)).findElements(By.xpath(".//button[normalize-space(.)='Cancel booking']")) shouldBe empty
      press(ref, "Start work", "In the workshop")
      ui.signOut()
    }

    it("an advisor of another workshop finishes it") {
      val ref = needsBooking()
      ui.signIn("advisor.td@demo.vn")
      openAllAppointments("Saigon Moto Care – District 1")
      press(ref, "Finish job", "Awaiting payment")
      ui.signOut()
    }

    it("the admin records the payment") {
      val ref = needsBooking()
      ui.signIn("admin.d1@demo.vn")
      openAllAppointments("My workshop")
      press(ref, "Payment received", "Finished")
      noDeleteButton()
      ui.signOut()
    }

    it("a technician reads the advice requests of every workshop") {
      ui.signIn("tech.hung@demo.vn")
      ui.open("/staff/requests")
      ui.see("Advice requests")
      ui.select("Workshop").selectByVisibleText("All workshops")
      ui.clickButton("All")
      ui.see("District 1")
      ui.signOut()
    }
  }

  describe("System manager") {
    it("lists customers, advisors, technicians and admins") {
      ui.signIn("sysadmin@demo.vn")
      ui.open("/system/people")
      val expected = Seq(
        "Customers" -> "customer@demo.vn",
        "Advisors" -> "advisor.d1@demo.vn",
        "Technicians" -> "tech.hung@demo.vn",
        "Admins" -> "admin.d1@demo.vn"
      )
      for (tab, someone) <- expected do
        ui.click(By.xpath(s"//div[@role='tablist']/button[starts-with(normalize-space(.), ${Ui.literal(tab)})]"))
        ui.visible(By.cssSelector(s"[data-person='$someone']"))
      ui.click(By.xpath("//div[@role='tablist']/button[starts-with(normalize-space(.), 'Advisors')]"))
      ui.visible(By.cssSelector("[data-person='advisor.td@demo.vn']")).getText should include("Thu Duc")
    }

    it("lists the workshops") {
      ui.open("/system")
      ui.see("Saigon Moto Care – District 1")
      ui.see("Riverside Auto & Moto – Thu Duc")
      ui.signOut()
    }
  }
