package scheduler.e2e

import org.openqa.selenium.chromium.{ChromiumDriver, ChromiumNetworkConditions}
import scala.jdk.CollectionConverters.*

import org.openqa.selenium.{By, JavascriptExecutor, WebElement}

/**
 * The main feature's worst case: the customer presses Hold or Confirm again and again while
 * the first press is still on its way (a slow connection, an impatient thumb), or books the
 * same vehicle from two tabs. However it is pressed, the vehicle ends with exactly one
 * appointment and the page shows no false error. Once booked, the vehicle cannot be booked
 * again until that appointment is cancelled: the buttons say so, and the API refuses it.
 */
class DoubleSubmitSpec extends E2ESuite with CustomerSteps:
  private val email = s"e2e-double-$run@demo.vn"
  private val password = "E2eTest2026"
  // One motorbike for the presses, one for changing the time, one for the two tabs.
  private val bikes = Seq(newPlate("59-D6"), newPlate("59-D7"), newPlate("59-D8"))
  private val days = Seq(quietWeekday(4), quietWeekday(5), quietWeekday(6), quietWeekday(7))

  private lazy val api = Api.signIn(email, password)
  private var references = Seq.empty[String]

  /** The customer's appointments that still claim a vehicle: on hold, booked or in the workshop. */
  private def live: Seq[Any] =
    Api.list(api.get("/api/me/appointments")).filter(a => Set("HELD", "CONFIRMED", "IN_PROGRESS")(Api.field(a, "status").toString))

  private def plateOf(a: Any): String = Api.field(Api.field(a, "vehicle"), "plate").toString

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
      fillVehicle("Honda", "Lead", bikes(0))
      ui.see(bikes(0))
      for plate <- bikes.tail do
        ui.clickButton("Add a vehicle")
        fillVehicle("Honda", "Lead", plate)
        ui.see(plate)

      upToTime(days(0), bikes(0))
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
      val row = By.xpath(s"//div[contains(@class,'item') and .//*[normalize-space(.)=${Ui.literal(bikes(0))}]]")
      ui.visible(row).getText should include(s"Already booked: ${references.head}")
      ui.visible(row).findElement(By.xpath(".//button[normalize-space(.)='Book a service']")).isEnabled shouldBe false

      ui.open("/book")
      ui.see("Which vehicle?")
      ui.visible(vehicleChoice(bikes(0))).isEnabled shouldBe false
      ui.see(s"Already booked: ${references.head}")
      ui.visible(vehicleChoice(bikes(1))).isEnabled shouldBe true
    }

    it("is refused by the API too, however the request is sent") {
      val vehicleId = Api.field(Api.list(api.get("/api/me/vehicles")).find(v => Api.field(v, "plate") == bikes(0)).get, "id").toString
      val error = intercept[Api.ApiFailure] {
        api.post("/api/appointments/holds", Map("dealershipId" -> Api.field(live.head, "dealership.id"), "vehicleId" -> vehicleId,
          "start" -> s"${days(3)}T08:00:00+07:00", "packageId" -> Api.packageId(api, "Essential maintenance")))
      }
      error.status shouldBe 409
      error.body should include("VEHICLE_HAS_APPOINTMENT")
    }

    it("can be booked again once that appointment is cancelled") {
      cancelFromMyAppointments(references.head)
      references = references.tail
      ui.open("/vehicles")
      val row = By.xpath(s"//div[contains(@class,'item') and .//*[normalize-space(.)=${Ui.literal(bikes(0))}]]")
      ui.eventually("Book a service is a link again") {
        ui.visible(row).findElements(By.xpath(".//a[normalize-space(.)='Book a service']")).size == 1
      }
      live shouldBe empty
    }

    it("double-clicked Hold and then Confirm like a person would: one booking") {
      upToTime(days(1), bikes(0))
      slowNetwork(800)
      try
        val actions = org.openqa.selenium.interactions.Actions(ui.driver)
        actions.doubleClick(ui.visible(ui.button("Hold this time and review"))).perform()
        ui.visible(By.cssSelector("[role='timer']"))
        actions.doubleClick(ui.visible(ui.button("Confirm booking"))).perform()
        booked()
      finally normalNetwork()
      live should have size 1
      ui.alertText shouldBe None
    }
  }

  describe("Changing the time while the hold is on its way") {
    it("gives the first hold back and holds the new time, with no error") {
      upToTime(days(2), bikes(1))
      val free = ui.all(By.cssSelector("[aria-label='Afternoon start times'] button.slot:not([disabled])"))
      assume(free.size >= 2, "needs two free afternoon times")
      slowNetwork(1500)
      try
        ui.clickButton("Hold this time and review")
        ui.clickButton("Change time") // the hold is still on its way
        ui.see("When?")
        val other = ui.all(By.cssSelector("[aria-label='Afternoon start times'] button.slot:not([disabled])")).head
        ui.click(other)
        ui.clickButton("Hold this time and review")
        ui.visible(By.cssSelector("[role='timer']"))
      finally normalNetwork()
      ui.alertText shouldBe None
      ui.eventually("only the second hold is left for that vehicle") {
        live.count(a => plateOf(a) == bikes(1)) == 1
      }
      ui.clickButton("Confirm booking")
      booked()
    }
  }

  describe("The same customer in two tabs") {
    it("holds the same vehicle at the same time in both: the second tab is told, one appointment") {
      val day = days(3)
      val first = ui.driver.getWindowHandle
      val start = upToTime(day, bikes(2))
      ui.driver.switchTo().newWindow(org.openqa.selenium.WindowType.TAB)
      upToTime(day, bikes(2)) shouldBe start

      ui.driver.switchTo().window(first)
      ui.clickButton("Hold this time and review")
      ui.visible(By.cssSelector("[role='timer']"))

      ui.driver.switchTo().window(ui.driver.getWindowHandles.asScala.find(_ != first).get)
      // Either the tab already knows (it refreshed on focus) or it finds out on pressing Hold.
      ui.all(ui.button("Hold this time and review")).headOption.foreach(ui.click)
      ui.eventually("the second tab says the vehicle already has an appointment") {
        ui.all(By.tagName("body")).head.getText.contains("already has an appointment")
      }
      ui.all(By.cssSelector("[role='timer']")) shouldBe empty
      ui.all(ui.button("Confirm booking")).filter(_.isEnabled) shouldBe empty
      ui.driver.close()

      ui.driver.switchTo().window(first)
      ui.clickButton("Confirm booking")
      booked()
      live.count(a => plateOf(a) == bikes(2)) shouldBe 1
    }
  }

  describe("In the end") {
    it("the customer has one confirmed appointment per vehicle, one per booking made") {
      val mine = live
      mine.map(a => Api.field(a, "status")).toSet shouldBe Set("CONFIRMED")
      mine.map(plateOf).sorted shouldBe bikes.sorted
      mine.map(a => Api.field(a, "reference").toString).toSet shouldBe references.toSet
    }
  }

  override def afterAll(): Unit =
    normalNetwork()
    scala.util.Try {
      for a <- live do Api.cancel(api, Api.field(a, "id").toString)
    }
    super.afterAll()
