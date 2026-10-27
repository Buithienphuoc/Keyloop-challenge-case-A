package scheduler.e2e

import org.openqa.selenium.By

/**
 * The workshop day activity diagram (activity-workshop-day.puml) and the appointment
 * lifecycle (state-appointment.puml): the advisor starts and finishes one job from the board
 * (UC32), the assigned technician starts and finishes another from My jobs (UC40, UC41), the
 * advisor cancels a third (UC33), and no-show is not offered before the start time. Both
 * finished jobs wait for payment until the dealership admin records it (UC36); only then
 * does the customer see them as finished. The jobs are started days before their booked
 * time, which is allowed (the vehicle can arrive early).
 * Three bookings on three quiet weekdays, so they never compete with each other, for three
 * motorbikes: a vehicle with a live appointment cannot be booked again until it is cancelled.
 */
class WorkshopDaySpec extends E2ESuite with CustomerSteps:
  private val email = s"e2e-day-$run@demo.vn"
  private val password = "E2eTest2026"
  /** One motorbike per booking, by its role in the story. */
  private val plates = Map("advisor" -> newPlate("59-W4"), "technician" -> newPlate("59-W5"), "cancelled" -> newPlate("59-W6"))
  private val technicianWithLogin = "Trần Quốc Hùng" // tech.hung@demo.vn
  // Weekdays no other suite books: with `from = 5` these used to fall on BookingJourneySpec's
  // day whenever day 4 and day 5 were a weekend, and a job finished early frees its stand, so
  // the two blocks overlapped on the board.
  private val days = Seq(quietWeekday(8), quietWeekday(9), quietWeekday(10))

  /** Booking references by role in the story: advisor's job, technician's job, cancelled job. */
  private var refs = Map.empty[String, String]
  private def ref(key: String): String = refs.getOrElse(key, cancel(s"the $key booking did not finish"))

  private def jobOnBoard(key: String): By =
    By.xpath(s"//button[contains(@class,'job') and .//strong[normalize-space(.)=${Ui.literal(plates(key))}]]")

  /** The board's side panel for the `key` booking on its day. */
  private def openOnBoard(key: String, dayIndex: Int): Unit =
    val reference = ref(key)
    ui.open("/staff/board")
    ui.goToDay(days(dayIndex))
    ui.click(jobOnBoard(key))
    ui.see(reference)

  private def panelStatus: String = ui.visible(By.cssSelector("aside .pill")).getText

  describe("Customer") {
    it("books three services on three days") {
      register("E2E Workshop", email, password)
      ui.open("/vehicles")
      ui.clickButton("Add your first vehicle")
      fillVehicle("Honda", "Wave Alpha", plates("advisor"))
      ui.see(plates("advisor"))
      for key <- Seq("technician", "cancelled") do
        ui.clickButton("Add a vehicle")
        fillVehicle("Honda", "Wave Alpha", plates(key))
        ui.see(plates(key))
      refs = Map("advisor" -> bookEssential(days(0), plates("advisor")), "technician" -> bookEssential(days(1), plates("technician")),
        "cancelled" -> bookEssential(days(2), plates("cancelled")))
      ui.signOut()
    }
  }

  describe("Service advisor on the day board (UC30, UC32, UC33)") {
    it("does not offer no-show before the start time") {
      ui.signIn("advisor.d1@demo.vn")
      openOnBoard("advisor", 0)
      panelStatus shouldBe "Confirmed"
      ui.visible(ui.button("Vehicle arrived, start work"))
      ui.all(ui.button("Customer did not come")) shouldBe empty
    }

    it("starts the job when the vehicle arrives, then finishes it once the customer has checked it") {
      ref("advisor")
      ui.clickButton("Vehicle arrived, start work")
      ui.eventually("the job is in the workshop") { panelStatus == "In the workshop" }
      ui.all(ui.button("Cancel booking")) shouldBe empty // no cancelling once work has started
      ui.clickButton("Customer checked the vehicle, finish job")
      ui.eventually("the job waits for payment") { panelStatus == "Awaiting payment" }
      ui.all(By.cssSelector("aside button.btn-primary")) shouldBe empty // only the admin records the payment
    }

    it("cancels a booking before work starts") {
      openOnBoard("cancelled", 2)
      ui.clickButton("Cancel booking")
      // A cancelled job leaves the board, which frees its bay and technician.
      ui.eventually("the job leaves the board") { ui.all(jobOnBoard("cancelled")).isEmpty }
      refs -= "cancelled"
      ui.signOut()
    }
  }

  describe("Dealership admin (an advisor and more)") {
    it("gives the second job to the technician who has a login") {
      ui.signIn("admin.d1@demo.vn")
      openOnBoard("technician", 1)
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
      val card = By.xpath(s"//article[contains(@class,'job-card') and .//*[normalize-space(.)=${Ui.literal(plates("technician"))}]]")
      ui.visible(card).getText should include("Essential maintenance")
      ui.click(ui.visible(card).findElement(By.xpath(".//button[normalize-space(.)='Start job']")))
      ui.eventually("the job is in the workshop") { ui.visible(card).getText.contains("In the workshop") }
      ui.click(ui.visible(card).findElement(By.xpath(".//button[normalize-space(.)='Customer checked the vehicle, finish job']")))
      ui.eventually("the job waits for payment") { ui.visible(card).getText.contains("Awaiting payment") }
      ui.visible(card).findElements(By.tagName("button")) shouldBe empty
      ui.signOut()
    }
  }

  describe("Customer, before paying (UC08)") {
    it("sees both finished jobs waiting for payment, ready to collect") {
      ui.signIn(email, password)
      ui.open("/appointments")
      for key <- Seq("advisor", "technician") do
        val row = ui.visible(appointmentRow(ref(key)))
        row.getText should include("Awaiting payment")
        row.getText should include("Your vehicle is ready")
        row.findElements(By.xpath(".//button[normalize-space(.)='Cancel']")) shouldBe empty
      ui.signOut()
    }
  }

  describe("Dealership admin, at the desk (UC36)") {
    def jobRow(r: String) = By.cssSelector(s"[data-job='$r']")

    it("sees both jobs under Awaiting payment and records each payment") {
      ui.signIn("admin.d1@demo.vn")
      ui.open("/admin/jobs")
      for key <- Seq("advisor", "technician") do
        val r = ref(key)
        val row = ui.visible(jobRow(r))
        row.getText should include("Awaiting payment")
        ui.click(row.findElement(By.xpath(".//button[normalize-space(.)='Payment received']")))
        ui.click(ui.visible(jobRow(r)).findElement(By.xpath(".//button[starts-with(normalize-space(.),'Yes,')]")))
        ui.eventually(s"$r leaves Awaiting payment") { ui.all(jobRow(r)).isEmpty }
    }

    it("finds them under Finished, with every other status one tab away") {
      ui.click(By.xpath("//button[@role='tab' and starts-with(normalize-space(.),'Finished')]"))
      for key <- Seq("advisor", "technician") do ui.visible(jobRow(ref(key))).getText should include("Finished")
      ui.click(By.xpath("//button[@role='tab' and starts-with(normalize-space(.),'Cancelled')]"))
      ui.visible(By.xpath(s"//*[@data-job and contains(normalize-space(.), ${Ui.literal(plates("cancelled"))})]")).getText should include("Cancelled")
      ui.signOut()
    }
  }

  describe("Customer, afterwards (UC08)") {
    it("sees the two paid jobs as finished and the third as cancelled, none of them cancellable") {
      ui.signIn(email, password)
      ui.open("/appointments")
      ui.see("Past and cancelled")
      for key <- Seq("advisor", "technician") do
        val row = ui.visible(appointmentRow(ref(key)))
        row.getText should include("Finished")
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
