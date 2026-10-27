package scheduler.e2e

import org.openqa.selenium.chromium.{ChromiumDriver, ChromiumNetworkConditions}

import org.openqa.selenium.{By, JavascriptExecutor, WebElement}

/**
 * The main feature's worst case: the customer presses Hold or Confirm again and again while
 * the first press is still on its way (a slow connection, an impatient thumb). However it is
 * pressed, the vehicle ends with exactly one appointment and the page shows no false error.
 * Once booked, the vehicle cannot be booked again: the buttons say so. Concurrent bookings by
 * many customers, two tabs, retries and changing the time mid-hold are covered by the
 * backend's BookingConcurrencySpec, which is much faster than a browser.
 */
class DoubleSubmitSpec extends E2ESuite with CustomerSteps:
  private val email = s"e2e-double-$run@demo.vn"
  private val password = "E2eTest2026"
  private val bike = newPlate("59-D6")
  private val day = quietWeekday(4)

  private lazy val api = Api.signIn(email, password)
  private var references = Seq.empty[String]

  /** The customer's appointments that still claim a vehicle: on hold, booked or in the workshop. */
  private def live: Seq[Any] =
    Api.list(api.get("/api/me/appointments")).filter(a => Set("HELD", "CONFIRMED", "IN_PROGRESS")(Api.field(a, "status").toString))

  /** Several clicks in one go, before the page can re-render or disable anything. */
  private def clickTimes(e: WebElement, n: Int): Unit =
    ui.step(s"click ${n}x at once")
    ui.driver.asInstanceOf[JavascriptExecutor].executeScript(s"for (let i = 0; i < $n; i++) arguments[0].click()", e)

  /** Every request the browser makes takes `ms` longer. */
  private def slowNetwork(ms: Int): Unit =
    val c = ChromiumNetworkConditions()
    c.setLatency(java.time.Duration.ofMillis(ms.toLong))
    ui.driver.asInstanceOf[ChromiumDriver].setNetworkConditions(c)

  private def normalNetwork(): Unit = scala.util.Try(ui.driver.asInstanceOf[ChromiumDriver].deleteNetworkConditions())

  /** Book page up to a picked time on `day` for the motorbike `plate`. */
  private def upToTime(day: java.time.LocalDate, plate: String): String =
    ui.open("/book")
    ui.see("Which vehicle?")
    ui.click(vehicleChoice(plate))
    ui.see("What should we do?")
    ui.click(ui.containing("button", "Essential maintenance"))
    ui.clickButton("Choose a time")
    ui.click(ui.containing("button", "District 1"))
    pickDay(day)
    pickLastAfternoonSlot()

  private def booked(): String =
    ui.see("You're booked in")
    val ref = ui.visible(By.cssSelector(".confirmed .ref")).getText.trim
    references :+= ref
    ref

  describe("A customer on a slow connection") {
    it("presses Hold five times at once: one hold, no error") {
      register("E2E Double", email, password)
      ui.open("/vehicles")
      ui.clickButton("Add your first vehicle")
      fillVehicle("Honda", "Lead", bike)
      ui.see(bike)

      upToTime(day, bike)
      slowNetwork(1500)
      try
        clickTimes(ui.visible(ui.button("Hold this time and review")), 5)
        ui.visible(By.cssSelector("[role='timer']"))
      finally normalNetwork()
      ui.alertText shouldBe None
      live should have size 1
      Api.field(live.head, "status") shouldBe "HELD"
    }

    it("presses Confirm five times at once: one booking") {
      slowNetwork(1500)
      try
        clickTimes(ui.visible(ui.button("Confirm booking")), 5)
        booked()
      finally normalNetwork()
      live should have size 1
      Api.field(live.head, "status") shouldBe "CONFIRMED"
    }
  }

  describe("A booked vehicle") {
    it("cannot be booked again: the buttons are locked and say why") {
      ui.open("/vehicles")
      val row = By.xpath(s"//div[contains(@class,'item') and .//*[normalize-space(.)=${Ui.literal(bike)}]]")
      ui.visible(row).getText should include(s"Already booked: ${references.head}")
      ui.visible(row).findElement(By.xpath(".//button[normalize-space(.)='Book a service']")).isEnabled shouldBe false
    }
  }

  describe("In the end") {
    it("the customer has one confirmed appointment, the one booked") {
      val mine = live
      mine.map(a => Api.field(a, "status")) shouldBe Seq("CONFIRMED")
      mine.map(a => Api.field(a, "reference").toString) shouldBe references
    }
  }

  override def afterAll(): Unit =
    normalNetwork()
    scala.util.Try {
      for a <- live do Api.cancel(api, Api.field(a, "id").toString)
    }
    super.afterAll()
