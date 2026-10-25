package scheduler.e2e

import java.time.format.TextStyle
import java.time.{DayOfWeek, LocalDate}
import java.util.Locale

import scala.jdk.CollectionConverters.*

import org.openqa.selenium.{By, WebElement}

/**
 * The whole life of one booking, across every role that touches it:
 * a new customer books (with a hold before confirming) → the advisor sees it on the day board
 * → the workshop controller moves it → the technician sees it and asks for a part → the parts
 * desk issues the part → the customer cancels. It finishes by cancelling, so the demo data is
 * left as it was.
 */
class BookingJourneySpec extends E2ESuite:
  private val run = java.lang.Long.toString(System.currentTimeMillis(), 36)
  private val email = s"e2e-$run@demo.vn"
  private val password = "E2eTest2026"
  private val plate = f"59-E2 ${System.currentTimeMillis() / 1000 % 1000}%03d.${System.nanoTime() % 100}%02d"
  private val technicianWithLogin = "Trần Quốc Hùng" // tech.hung@demo.vn

  /**
   * A weekday a few days out, when every District 1 technician works and the workshop is
   * quiet, so the steps below never depend on what else is booked.
   */
  private val day: LocalDate =
    Iterator.iterate(LocalDate.now(Settings.Zone).plusDays(4))(_.plusDays(1))
      .find(d => d.getDayOfWeek.getValue <= DayOfWeek.FRIDAY.getValue).get

  private var reference: Option[String] = None
  private var partsOrder: Option[String] = None
  private var partName: Option[String] = None

  private def needsBooking(): String = { assume(reference.isDefined, "the booking step did not finish"); reference.get }

  /** The block for our booking on the day board (its plate is on it). */
  private def boardJob: By = By.xpath(s"//button[contains(@class,'job') and .//strong[normalize-space(.)=${Ui.literal(plate)}]]")

  describe("Customer") {
    it("creates an account") {
      ui.open("/register")
      ui.fill("Your name", "E2E Tester")
      ui.fill("Email", email)
      ui.fill("Password", password)
      ui.clickButton("Create account")
      ui.visible(ui.button("Sign out"))
      ui.see("E2E Tester")
    }

    it("adds a motorbike, chooses a package, holds a time, changes it, and confirms") {
      ui.open("/book")
      ui.see("Which vehicle?")
      ui.fill("Make", "Honda")
      ui.fill("Model", "Vision")
      ui.fill("Plate number", plate)
      ui.clickButton("Add vehicle")

      ui.see("What should we do?")
      ui.click(ui.containing("button", "Essential maintenance"))
      ui.visible(By.cssSelector("ul[aria-label='What is included']")) // the checklist opens under the chosen package
      ui.clickButton("Choose a time")

      ui.click(ui.containing("button", "District 1"))
      val month = day.getMonth.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
      ui.step(s"pick $day")
      ui.click(By.xpath(
        s"//div[@aria-label='Day']//button[.//div[@class='n' and text()='${day.getDayOfMonth}'] and .//div[@class='mo' and text()='$month']]"))
      ui.eventually(s"$day is selected") {
        ui.all(By.cssSelector("div[aria-label='Day'] button[aria-pressed='true']")).exists(_.getText.contains(month))
      }

      // The last free afternoon start: the quietest part of a quiet day.
      val free = By.cssSelector("[aria-label='Afternoon start times'] button.slot:not([disabled])")
      ui.visible(free)
      val slot = ui.all(free).last
      val start = slot.getText.linesIterator.next()
      ui.step(s"pick $start")
      ui.click(slot)
      ui.fill("Anything the technician should know? (optional)", "E2E run: please ignore")

      ui.clickButton("Hold this time and review")
      ui.visible(By.cssSelector("[role='timer']")).getText should include("Nobody else can book it")

      // Going back gives the time away; holding again takes it back.
      ui.clickButton("Change time")
      ui.see("When?")
      ui.clickButton("Hold this time and review")
      ui.visible(By.cssSelector("[role='timer']"))
      ui.see(start)

      ui.clickButton("Confirm booking")
      ui.see("You're booked in")
      reference = Some(ui.visible(By.cssSelector(".confirmed .ref")).getText.trim)
      ui.step(s"booked ${reference.get} for $day $start")
      reference.get should startWith("SV-")
    }

    it("sees the booking as confirmed in My appointments") {
      val ref = needsBooking()
      ui.open("/appointments")
      val item = ui.visible(By.xpath(s"//div[contains(@class,'item') and contains(normalize-space(.), ${Ui.literal(ref)})]"))
      item.getText should include("Confirmed")
      item.getText should include("Essential maintenance")
    }

    it("asks an advisor to check something specific") {
      ui.open("/requests/new?kind=INSPECTION")
      ui.see("Check something specific")
      ui.click(By.xpath("(//fieldset//label[contains(@class,'check')])[1]"))
      ui.fill("Describe it in your own words", s"E2E $run: the horn is quiet after rain.")
      ui.select("Which dealership?").selectByVisibleText("Saigon Moto Care – District 1")
      ui.clickButton("Send to an advisor")
      ui.see(s"E2E $run: the horn is quiet after rain.")
      ui.signOut()
    }
  }

  describe("Service advisor (District 1)") {
    it("finds the new booking on the workshop day board") {
      val ref = needsBooking()
      ui.signIn("advisor.d1@demo.vn")
      ui.waitForPath("/staff/board")
      ui.goToDay(day)
      ui.click(boardJob)
      ui.see(ref)
      ui.see("E2E Tester")
      ui.visible(ui.button("Cancel booking")) // the advisor may change its status
    }

    it("sees the customer's advice request waiting") {
      ui.open("/staff/requests")
      ui.see(s"E2E $run: the horn is quiet after rain.")
      ui.signOut()
    }
  }

  describe("Workshop controller (District 1)") {
    it("sees how booked each technician and bay is") {
      needsBooking()
      ui.signIn("controller.d1@demo.vn")
      ui.open("/staff/workload")
      ui.goToDay(day)
      ui.see("Technicians")
      ui.see("Bays")
      ui.all(By.cssSelector("[role='meter']")).size should be > 4
    }

    it("moves the job to another bay and to the technician tech.hung") {
      val ref = needsBooking()
      ui.open("/staff/board")
      ui.goToDay(day)
      ui.click(boardJob)
      ui.see(ref)
      ui.all(ui.button("Cancel booking")) shouldBe empty // a controller moves jobs, it does not change their status

      ui.select("Technician").selectByVisibleText(technicianWithLogin)
      val bays = ui.select("Bay")
      val current = bays.getFirstSelectedOption.getText
      val others = bays.getOptions.asScala.map(_.getText).filterNot(_ == current).toSeq
      others should not be empty
      // Another booking may hold a bay at that time; the server says so, and we try the next.
      val moved = others.iterator.map { bay =>
        ui.step(s"move to $bay")
        ui.select("Bay").selectByVisibleText(bay)
        ui.clickButton("Move job")
        ui.eventually("the move is answered") { !ui.notSee("Moved.") || ui.alertText.isDefined }
        !ui.notSee("Moved.")
      }.find(identity)
      moved shouldBe Some(true)
      ui.eventually(s"the job shows $technicianWithLogin") {
        ui.visible(By.cssSelector("dl.kv")).getText.contains(technicianWithLogin)
      }
      ui.signOut()
    }
  }

  describe("Technician (tech.hung)") {
    it("sees the job in My jobs and requests a part for it") {
      needsBooking()
      ui.signIn("tech.hung@demo.vn")
      ui.waitForPath("/jobs")
      ui.goToDay(day)
      val card = ui.visible(By.xpath(s"//article[contains(@class,'job-card') and .//*[normalize-space(.)=${Ui.literal(plate)}]]"))
      card.getText should include("Essential maintenance")
      card.getText should include("E2E run: please ignore") // the customer's note reaches the technician
      ui.click(card.findElement(By.linkText("Request parts for this job")))

      ui.see("Request parts")
      ui.select("For which job? (optional)").getFirstSelectedOption.getText shouldBe "The job you came from"
      // The part with the most on the shelf, so the parts desk can issue it straight away.
      val part = ui.select("Part")
      val best = part.getOptions.asScala.drop(1).maxBy(stockOf)
      partName = Some(best.getText.replaceAll("""\s*\(\d+ on shelf\)$""", ""))
      part.selectByVisibleText(best.getText)
      ui.fill("Note for the parts desk (optional)", s"E2E $run")
      ui.clickButton("Send to parts desk")

      val sent = ui.visible(By.cssSelector(".notice-ok")).getText
      partsOrder = """Order (\S+) sent""".r.findFirstMatchIn(sent).map(_.group(1))
      ui.step(s"parts order ${partsOrder.getOrElse("?")} for ${partName.get}")
      partsOrder should not be empty
      ui.signOut()
    }
  }

  describe("Parts staff (District 1)") {
    it("restocks one unit, then issues the order from the shelf") {
      assume(partsOrder.isDefined, "the parts request step did not finish")
      ui.signIn("parts.d1@demo.vn")
      ui.waitForPath("/parts")

      // Stock: put one back on the shelf first, so repeated runs never run it dry.
      ui.click(ui.exact("button", "Stock"))
      val name = partName.get
      val row = By.xpath(s"//tr[.//strong[normalize-space(.)=${Ui.literal(name)}]]")
      val before = ui.visible(row).findElements(By.tagName("td")).get(2).getText.toInt
      ui.click(By.cssSelector(s"button[aria-label=${Ui.literal(s"One more $name")}]"))
      ui.eventually(s"$name shows ${before + 1} on the shelf") {
        ui.visible(row).findElements(By.tagName("td")).get(2).getText.toInt == before + 1
      }

      ui.click(ui.exact("button", "Orders"))
      val order = ui.visible(orderCard(partsOrder.get))
      order.getText should include("Requested")
      ui.click(order.findElement(By.xpath(".//button[normalize-space(.)='Issue from stock']")))
      ui.select("Show").selectByVisibleText("All orders")
      ui.eventually("the order is issued") { ui.visible(orderCard(partsOrder.get)).getText.contains("Issued") }

      ui.click(ui.exact("button", "Stock"))
      ui.eventually(s"$name is back to $before on the shelf") {
        ui.visible(row).findElements(By.tagName("td")).get(2).getText.toInt == before
      }
      ui.signOut()
    }
  }

  describe("Customer, afterwards") {
    it("cancels the booking, which then shows as cancelled") {
      val ref = needsBooking()
      ui.signIn(email, password)
      ui.open("/appointments")
      val item = By.xpath(s"//div[contains(@class,'item') and contains(normalize-space(.), ${Ui.literal(ref)})]")
      ui.click(ui.visible(item).findElement(By.xpath(".//button[normalize-space(.)='Cancel']")))
      ui.click(ui.visible(item).findElement(By.xpath(".//button[normalize-space(.)='Yes, cancel it']")))
      ui.eventually("the booking is cancelled") { ui.visible(item).getText.contains("Cancelled") }
      ui.see("Past and cancelled")
      reference = None // nothing left to clean up
    }
  }

  override def afterAll(): Unit =
    // A failed run still gives its time back, so the next run (and the demo) find it free.
    reference.foreach { ref =>
      scala.util.Try {
        ui.step(s"clean-up: cancel $ref")
        ui.signIn(email, password)
        ui.open("/appointments")
        val item = By.xpath(s"//div[contains(@class,'item') and contains(normalize-space(.), ${Ui.literal(ref)})]")
        ui.click(ui.visible(item).findElement(By.xpath(".//button[normalize-space(.)='Cancel']")))
        ui.click(ui.visible(item).findElement(By.xpath(".//button[normalize-space(.)='Yes, cancel it']")))
      }
    }
    super.afterAll()

  private def orderCard(ref: String): By =
    By.xpath(s"//article[.//span[normalize-space(.)=${Ui.literal(ref)}]]")

  private def stockOf(option: WebElement): Int =
    """\((\d+) on shelf\)""".r.findFirstMatchIn(option.getText).map(_.group(1).toInt).getOrElse(0)
