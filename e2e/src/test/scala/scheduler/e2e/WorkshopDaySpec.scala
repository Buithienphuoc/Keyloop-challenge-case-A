package scheduler.e2e

import org.openqa.selenium.By

/**
 * The workshop day activity diagram (activity-workshop-day.puml) and the appointment
 * lifecycle (state-appointment.puml): the advisor starts and finishes one job from the board
 * (UC32), the assigned technician starts and finishes another from My jobs (UC40, UC41), the
 * advisor cancels a third (UC33), and no-show is not offered before the start time.
 * Three bookings on three quiet weekdays, so they never compete with each other.
 */
class WorkshopDaySpec extends E2ESuite with CustomerSteps:
  private val email = s"e2e-day-$run@demo.vn"
  private val password = "E2eTest2026"
  private val plate = newPlate("59-W4")
  private val technicianWithLogin = "Trần Quốc Hùng" // tech.hung@demo.vn
  private val days = Seq(quietWeekday(0, from = 5), quietWeekday(1, from = 5), quietWeekday(2, from = 5))

  /** Booking references by role in the story: advisor's job, technician's job, cancelled job. */
  private var refs = Map.empty[String, String]
  private def ref(key: String): String = refs.getOrElse(key, cancel(s"the $key booking did not finish"))

  /** The board's side panel for booking `ref` on its day. */
  private def openOnBoard(reference: String, dayIndex: Int): Unit =
    ui.open("/staff/board")
    ui.goToDay(days(dayIndex))
    ui.click(By.xpath(s"//button[contains(@class,'job') and .//strong[normalize-space(.)=${Ui.literal(plate)}]]"))
    ui.see(reference)

  private def panelStatus: String = ui.visible(By.cssSelector("aside .pill")).getText

  describe("Customer") {
    it("books three services on three days") {
      register("E2E Workshop", email, password)
      ui.open("/vehicles")
      ui.clickButton("Add your first vehicle")
      fillVehicle("Honda", "Wave Alpha", plate)
      ui.see(plate)
      refs = Map("advisor" -> bookEssential(days(0)), "technician" -> bookEssential(days(1)), "cancelled" -> bookEssential(days(2)))
      ui.signOut()
    }
  }

  describe("Service advisor on the day board (UC30, UC32, UC33)") {
    it("does not offer no-show before the start time") {
      val r = ref("advisor")
      ui.signIn("advisor.d1@demo.vn")
      openOnBoard(r, 0)
      panelStatus shouldBe "Confirmed"
      ui.visible(ui.button("Vehicle arrived, start work"))
      ui.all(ui.button("Customer did not come")) shouldBe empty
    }

    it("starts the job when the vehicle arrives, then marks it done") {
      ref("advisor")
      ui.clickButton("Vehicle arrived, start work")
      ui.eventually("the job is in the workshop") { panelStatus == "In the workshop" }
      ui.all(ui.button("Cancel booking")) shouldBe empty // no cancelling once work has started
      ui.clickButton("Mark as done")
      ui.eventually("the job is done") { panelStatus == "Done" }
      ui.all(By.cssSelector("aside button.btn-primary")) shouldBe empty // nothing follows Done
    }

    it("cancels a booking before work starts") {
      val r = ref("cancelled")
      openOnBoard(r, 2)
      ui.clickButton("Cancel booking")
      // A cancelled job leaves the board, which frees its bay and technician.
      ui.eventually("the job leaves the board") {
        ui.all(By.xpath(s"//button[contains(@class,'job') and .//strong[normalize-space(.)=${Ui.literal(plate)}]]")).isEmpty
      }
      refs -= "cancelled"
      ui.signOut()
    }
  }

  describe("Dealership admin (an advisor and more)") {
    it("gives the second job to the technician who has a login") {
      val r = ref("technician")
      ui.signIn("admin.d1@demo.vn")
      openOnBoard(r, 1)
      val current = ui.visible(By.cssSelector("dl.kv")).getText
      if !current.contains(technicianWithLogin) then
        ui.select("Technician").selectByVisibleText(technicianWithLogin)
        ui.clickButton("Move job")
        ui.see("Moved.")
      ui.eventually(s"the job shows $technicianWithLogin") {
        ui.visible(By.cssSelector("dl.kv")).getText.contains(technicianWithLogin)
      }
      ui.signOut()
    }
  }

  describe("Technician (UC40, UC41)") {
    it("starts and finishes their own job from My jobs") {
      ref("technician")
      ui.signIn("tech.hung@demo.vn")
      ui.waitForPath("/jobs")
      ui.goToDay(days(1))
      val card = By.xpath(s"//article[contains(@class,'job-card') and .//*[normalize-space(.)=${Ui.literal(plate)}]]")
      ui.visible(card).getText should include("Essential maintenance")
      ui.click(ui.visible(card).findElement(By.xpath(".//button[normalize-space(.)='Start job']")))
      ui.eventually("the job is in the workshop") { ui.visible(card).getText.contains("In the workshop") }
      ui.click(ui.visible(card).findElement(By.xpath(".//button[normalize-space(.)='Job done']")))
      ui.eventually("the job is done") { ui.visible(card).getText.contains("Done") }
      ui.visible(card).findElements(By.tagName("button")) shouldBe empty
      ui.signOut()
    }
  }

  describe("Customer, afterwards (UC08)") {
    it("sees the two finished jobs as done and the third as cancelled, none of them cancellable") {
      ui.signIn(email, password)
      ui.open("/appointments")
      ui.see("Past and cancelled")
      for key <- Seq("advisor", "technician") do
        val row = ui.visible(appointmentRow(ref(key)))
        row.getText should include("Done")
        row.findElements(By.xpath(".//button[normalize-space(.)='Cancel']")) shouldBe empty
      ui.all(By.xpath("//div[contains(@class,'item') and contains(normalize-space(.), 'Cancelled')]")) should not be empty
      ui.signOut()
    }
  }

  override def afterAll(): Unit =
    // A failed run still gives back any time it holds; finished jobs no longer hold anything.
    if refs.nonEmpty && scala.util.Try(ui.signIn(email, password)).isSuccess then
      for r <- refs.values do
        ui.step(s"clean-up: cancel $r if still upcoming")
        scala.util.Try(cancelFromMyAppointments(r))
    super.afterAll()
