package scheduler.e2e

import org.openqa.selenium.{By, WebElement}

/**
 * The "consult first, then book" activity diagram (activity-consult-first.puml), branch by
 * branch: UC05 inspection → UC31 the advisor approves → UC07 the customer books it → UC09
 * cancelling re-opens the request → UC10 withdrawing it; and UC06 customisation → UC31 the
 * advisor declines → the customer reads the reason.
 */
class ConsultFirstSpec extends E2ESuite with CustomerSteps:
  private val email = s"e2e-consult-$run@demo.vn"
  private val password = "E2eTest2026"
  private val plate = newPlate("59-C3")
  private val inspection = s"E2E $run: the engine knocks above 60 km/h."
  private val customisation = s"E2E $run: a louder exhaust, street-legal please."
  private val advice = s"E2E $run: probably the timing chain, we will check it."
  private val declined = s"E2E $run: we cannot fit that exhaust legally."
  private val day = quietWeekday(1)

  private var reference: Option[String] = None

  /** The customer's card for the request whose description is `text`. */
  private def myRequest(text: String): By =
    By.xpath(s"//div[contains(@class,'panel') and .//p[contains(normalize-space(.), ${Ui.literal(text)})]]")

  /** The advisor's card for the request whose description is `text` (the queue shows one card per request). */
  private def queueCard(text: String): WebElement =
    ui.visible(By.xpath(s"//div[contains(@class,'panel-tight') and .//p[contains(normalize-space(.), ${Ui.literal(text)})]]"))

  /** A queue tab; "Waiting for advice" carries a count badge, so match the start of its text. */
  private def tab(label: String): Unit =
    ui.click(By.xpath(s"//button[@role='tab' and starts-with(normalize-space(.), ${Ui.literal(label)})]"))

  describe("Customer sends two requests (UC05, UC06)") {
    it("registers, adds a motorbike and asks for an inspection") {
      register("E2E Consult", email, password)
      ui.open("/requests/new?kind=INSPECTION")
      ui.see("First, add your vehicle")
      fillVehicle("Yamaha", "Exciter", plate)
      ui.see("Where is the problem?")
      ui.click(By.xpath("(//fieldset//label[contains(@class,'check')])[1]"))
      ui.fill("Describe it in your own words", inspection)
      ui.select("Which dealership?").selectByVisibleText("Saigon Moto Care – District 1")
      ui.clickButton("Send to an advisor")
      ui.waitForPath("/requests")
      ui.see("Sent. An advisor usually replies")
      ui.visible(myRequest(inspection)).getText should include("Waiting for an advisor")
    }

    it("asks for a customisation") {
      ui.open("/requests/new?kind=MODIFICATION")
      ui.see("What would you like to change?")
      ui.click(By.xpath("(//fieldset//label[contains(@class,'check')])[2]"))
      ui.fill("Describe it in your own words", customisation)
      ui.select("Which dealership?").selectByVisibleText("Saigon Moto Care – District 1")
      ui.clickButton("Send to an advisor")
      ui.visible(myRequest(customisation)).getText should include("Waiting for an advisor")
      ui.signOut()
    }
  }

  describe("Service advisor reviews them (UC31)") {
    it("approves the inspection with time, skill, bay, estimate and a message") {
      ui.signIn("advisor.d1@demo.vn")
      ui.open("/staff/requests")
      tab("Waiting for advice")
      val card = queueCard(inspection)
      card.getText should include("E2E Consult")
      org.openqa.selenium.support.ui.Select(ui.inputIn(card, "Time needed")).selectByVisibleText("1 h 30 min")
      org.openqa.selenium.support.ui.Select(ui.inputIn(card, "Skill needed")).selectByVisibleText("Engine & drivetrain")
      ui.inputIn(card, "Estimate in VND (optional)").sendKeys("800000")
      ui.inputIn(card, "Message to the customer").sendKeys(advice)
      ui.click(card.findElement(By.xpath(".//button[normalize-space(.)='Approve and let them book']")))
      tab("Approved, not booked")
      ui.eventually("the inspection is approved") { queueCard(inspection).getText.contains(advice) }
    }

    it("cannot decline without a reason, then declines the customisation with one") {
      tab("Waiting for advice")
      val card = queueCard(customisation)
      val decline = card.findElement(By.xpath(".//button[normalize-space(.)='Decline']"))
      decline.isEnabled shouldBe false // a reason of at least 5 characters is required
      ui.inputIn(card, "Message to the customer").sendKeys(declined)
      ui.click(decline)
      ui.eventually("the customisation leaves the waiting list") { ui.notSee(customisation) }
      tab("All")
      queueCard(customisation).getText should include("Not possible")
      ui.signOut()
    }
  }

  describe("Customer reads the replies and books (UC07)") {
    it("sees the advisor's reason for the declined request, with no way to book it") {
      ui.signIn(email, password)
      ui.open("/requests")
      val card = ui.visible(myRequest(customisation))
      card.getText should include("Not possible")
      card.getText should include(declined)
      card.findElements(By.linkText("Pick a time")) shouldBe empty
    }

    it("sees the approval with its duration and estimate, and picks a time") {
      val card = ui.visible(myRequest(inspection))
      card.getText should include("Ready to book")
      card.getText should include(advice)
      card.getText should include("About 1 h 30 min")
      ui.click(card.findElement(By.linkText("Pick a time")))

      ui.see("Pick a time for your request")
      ui.see("Saigon Moto Care – District 1")
      ui.all(ui.exact("h2", "Where?")) shouldBe empty // the request fixes the dealership
      pickDay(day)
      pickLastAfternoonSlot()
      val ref = holdAndConfirm()
      reference = Some(ref)
      ref should startWith("SV-")
    }

    it("shows the request as booked and the appointment as an inspection") {
      val ref = reference.getOrElse(cancel("the booking step did not finish"))
      ui.open("/requests")
      ui.visible(myRequest(inspection)).getText should include("Booked")
      ui.open("/appointments")
      val row = ui.visible(appointmentRow(ref)).getText
      row should include("Confirmed")
      row should include("Inspection")
    }
  }

  describe("Afterwards") {
    it("the advisor sees it under Booked") {
      assume(reference.isDefined, "the booking step did not finish")
      ui.signOut()
      ui.signIn("advisor.d1@demo.vn")
      ui.open("/staff/requests")
      tab("Booked")
      queueCard(inspection).getText should include("Booked")
      ui.signOut()
    }

    it("cancelling the appointment puts the request back to ready to book") {
      val ref = reference.getOrElse(cancel("the booking step did not finish"))
      ui.signIn(email, password)
      cancelFromMyAppointments(ref)
      reference = None
      ui.open("/requests")
      ui.eventually("the request is ready to book again") {
        val card = ui.visible(myRequest(inspection))
        card.getText.contains("Ready to book") && card.findElements(By.linkText("Pick a time")).size == 1
      }
    }

    it("withdrawing the request closes it") {
      val card = ui.visible(myRequest(inspection))
      ui.click(card.findElement(By.xpath(".//button[normalize-space(.)='Withdraw request']")))
      ui.eventually("the request is withdrawn") { ui.visible(myRequest(inspection)).getText.contains("Withdrawn") }
      ui.visible(myRequest(inspection)).findElements(By.linkText("Pick a time")) shouldBe empty
      ui.signOut()
    }
  }

  override def afterAll(): Unit =
    reference.foreach { ref =>
      scala.util.Try {
        ui.step(s"clean-up: cancel $ref")
        ui.signIn(email, password)
        cancelFromMyAppointments(ref)
      }
    }
    super.afterAll()
