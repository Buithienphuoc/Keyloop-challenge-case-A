package scheduler.e2e

import org.openqa.selenium.By

/**
 * The brief's main feature from end to end, with the three roles of the app:
 * a new customer books a service for their car at a dealership and time (with a hold before
 * confirming) → the system checks that a bay and a qualified technician on shift are both free
 * for the whole job and records the appointment with customer, vehicle, technician and bay →
 * the technician sees it under Service requests, starts it, finishes it and closes it once
 * paid → the customer sees it finished → the system manager sees it in All data.
 *
 * Air-con check needs the electrical skill and a diagnostic bay. At District 1 at 08:00 only
 * Trần Quốc Hùng (tech.hung, who can sign in) has that skill and is on shift: Phạm Đức Khoa
 * starts at 09:00. The day is a quiet weekday no other suite books.
 */
class BookingJourneySpec extends E2ESuite with CustomerSteps:
  private val email = s"e2e-$run@demo.vn"
  private val password = "E2eTest2026"
  private val plate = newPlate("51-E2")
  private val technician = "Trần Quốc Hùng"
  private val day = quietWeekday(8)

  private var reference: Option[String] = None
  private def needsBooking(): String = { assume(reference.isDefined, "the booking step did not finish"); reference.get }

  private def jobCard: By =
    By.xpath(s"//article[contains(@class,'job-card') and .//*[normalize-space(.)=${Ui.literal(plate)}]]")

  describe("Customer") {
    it("creates an account and lands on Home") {
      register("E2E Tester", email, password)
      ui.see("what does your vehicle need?")
      ui.notSee("Advice requests") shouldBe true
      ui.notSee("Meet an advisor") shouldBe true
    }

    it("adds a car, chooses a service, holds a time, changes it, and confirms") {
      ui.open("/book")
      ui.see("Which vehicle?")
      fillVehicle("Toyota", "Vios", plate, car = true)

      ui.see("What should we do?")
      ui.click(ui.containing("button", "Air-con check"))
      ui.visible(By.cssSelector("ul[aria-label='What is included']"))
      ui.clickButton("Choose a time")

      ui.click(ui.containing("button", "District 1"))
      pickDay(day)
      val first = By.cssSelector("[aria-label='Morning start times'] button.slot:not([disabled])")
      ui.visible(first).getText.linesIterator.next() shouldBe "08:00"
      ui.click(first)
      ui.fill("Anything the technician should know? (optional)", "E2E run: please ignore")

      ui.clickButton("Hold this time and review")
      ui.visible(By.cssSelector("[role='timer']")).getText should include("Nobody else can book it")

      // Going back gives the time away; holding again takes it back.
      ui.clickButton("Change time")
      ui.see("When?")
      ui.clickButton("Hold this time and review")
      ui.visible(By.cssSelector("[role='timer']"))

      ui.clickButton("Confirm booking")
      ui.see("You're booked in")
      ui.see(s"$technician will look after your Toyota") // the technician is assigned by the booking
      reference = Some(ui.visible(By.cssSelector(".confirmed .ref")).getText.trim)
      ui.step(s"booked ${reference.get} for $day 08:00")
      reference.get should startWith("SV-")
    }

    it("sees the confirmed appointment with its technician in My appointments") {
      val ref = needsBooking()
      ui.open("/appointments")
      val item = ui.visible(appointmentRow(ref)).getText
      item should include("Confirmed")
      item should include("Air-con check")
      item should include(technician)
      ui.signOut()
    }
  }

  describe("Technician (tech.hung)") {
    it("lands on Service requests and finds the job with the customer's note") {
      needsBooking()
      ui.signIn("tech.hung@demo.vn")
      ui.waitForPath("/jobs")
      ui.see("Service requests")
      ui.goToDay(day)
      val card = ui.visible(jobCard).getText
      card should include("Air-con check")
      card should include("E2E Tester")
      card should include("E2E run: please ignore")
    }

    it("starts the job, finishes it, and closes it once the customer has paid") {
      needsBooking()
      ui.click(ui.visible(jobCard).findElement(By.xpath(".//button[normalize-space(.)='Start job']")))
      ui.eventually("the job is in the workshop") { ui.visible(jobCard).getText.contains("In the workshop") }
      ui.click(ui.visible(jobCard).findElement(By.xpath(".//button[normalize-space(.)='Customer checked the vehicle, finish job']")))
      ui.eventually("the job waits for payment") { ui.visible(jobCard).getText.contains("Awaiting payment") }
      ui.click(ui.visible(jobCard).findElement(By.xpath(".//button[normalize-space(.)='Customer has paid, close job']")))
      ui.eventually("the job is finished") { ui.visible(jobCard).getText.contains("Finished") }
      ui.visible(jobCard).findElements(By.tagName("button")) shouldBe empty
      reference = reference.map(r => s"done:$r") // nothing left to cancel
      ui.signOut()
    }
  }

  describe("Customer, afterwards") {
    it("sees the job as finished, no longer cancellable") {
      val ref = needsBooking().stripPrefix("done:")
      ui.signIn(email, password)
      ui.open("/appointments")
      ui.see("Past and cancelled")
      val row = ui.visible(appointmentRow(ref))
      row.getText should include("Finished")
      row.findElements(By.xpath(".//button[normalize-space(.)='Cancel']")) shouldBe empty
      ui.signOut()
    }
  }

  describe("System manager") {
    it("sees the appointment with its customer, vehicle, technician and bay in All data") {
      val ref = needsBooking().stripPrefix("done:")
      ui.signIn("sysadmin@demo.vn")
      ui.open("/system/data")
      val row = ui.visible(By.cssSelector(s"[data-appointment='$ref']")).getText
      row should include("E2E Tester")
      row should include(plate)
      row should include(technician)
      row should include("Diagnostic")
      ui.signOut()
    }
  }

  override def afterAll(): Unit =
    // A failed run still gives its time back, so the next run (and the demo) find it free.
    reference.filterNot(_.startsWith("done:")).foreach { ref =>
      scala.util.Try {
        ui.step(s"clean-up: cancel $ref")
        ui.signIn(email, password)
        cancelFromMyAppointments(ref)
      }
    }
    super.afterAll()
