package scheduler.e2e

import java.nio.file.Files

import scala.jdk.CollectionConverters.*

import org.openqa.selenium.By

/**
 * The decision points of the "book a maintenance package" activity diagram
 * (activity-book-package.puml) that the happy path in BookingJourneySpec does not reach:
 * only packages for the vehicle's type (UC04), "cannot do this job here" (UC20), the time
 * taken by someone else while confirming, with the nearest alternatives (UC22), and the
 * calendar file (UC11). Also UC03, managing vehicles.
 */
class BookingRulesSpec extends E2ESuite with CustomerSteps:
  private val email = s"e2e-rules-$run@demo.vn"
  private val password = "E2eTest2026"
  private val bike = newPlate("59-R5")
  private val car = newPlate("51R")
  private val day = quietWeekday(3)
  private val district1 = "Saigon Moto Care – District 1"

  private var reference: Option[String] = None
  /** Holds placed through the API by other customers; released at the end. */
  private var otherHolds = Seq.empty[(Api, String)]

  private def vehicleChoice(plate: String): By =
    By.xpath(s"//button[contains(@class,'choice') and .//*[normalize-space(.)=${Ui.literal(plate)}]]")

  private def packageNames: Seq[String] =
    ui.all(By.cssSelector(".choices button.choice .title")).map(_.getText.replace("Recommended", "").trim)

  describe("My vehicles (UC03)") {
    it("adds a motorbike and a car and lists both with their type") {
      register("E2E Rules", email, password)
      ui.open("/vehicles")
      ui.see("No vehicles yet")
      ui.clickButton("Add your first vehicle")
      fillVehicle("Honda", "Air Blade", bike)
      ui.see(bike)
      ui.clickButton("Add a vehicle")
      fillVehicle("Toyota", "Vios", car, car = true)
      ui.see(car)
      val rows = ui.all(By.cssSelector(".list .item")).map(_.getText)
      rows.find(_.contains(bike)).get should include("Motorbike")
      rows.find(_.contains(car)).get should include("Car")
    }
  }

  describe("Book a service: the checks along the way") {
    it("offers only packages made for the chosen vehicle's type") {
      ui.open("/book")
      ui.see("Which vehicle?")
      ui.click(vehicleChoice(car))
      ui.see("What should we do?")
      packageNames should contain("Minor service")
      packageNames should not contain "Essential maintenance"

      ui.clickButton("Back")
      ui.click(vehicleChoice(bike))
      ui.see("What should we do?")
      packageNames should contain("Essential maintenance")
      packageNames should not contain "Minor service"
    }

    it("says so when a dealership cannot do the job, and offers times at one that can") {
      ui.clickButton("Back")
      ui.click(vehicleChoice(car))
      ui.click(ui.containing("button", "Minor service"))
      ui.clickButton("Choose a time")
      ui.click(ui.containing("button", "District 1")) // motorbikes only: no car lift
      ui.see("cannot do this job")
      ui.all(By.cssSelector("button.slot")) shouldBe empty
      ui.visible(ui.button("Hold this time and review")).isEnabled shouldBe false

      ui.click(ui.containing("button", "Thu Duc"))
      ui.eventually("start times appear") { ui.all(By.cssSelector("button.slot:not([disabled])")).nonEmpty }
      ui.notSee("cannot do this job") shouldBe true
    }

    it("offers the nearest free times when someone takes the chosen time first, and books one of them") {
      ui.clickButton("Back")
      ui.clickButton("Back")
      ui.click(vehicleChoice(bike))
      ui.click(ui.containing("button", "Essential maintenance"))
      ui.clickButton("Choose a time")
      ui.click(ui.containing("button", "District 1"))
      pickDay(day)
      val start = pickLastAfternoonSlot()

      // Behind the customer's back, other customers hold every place left at that time.
      val first = Api.signIn("walkin1@demo.vn")
      val dealership = Api.dealershipId(first, district1)
      val pkg = Api.packageId(first, "Essential maintenance")
      val slot = Api.slot(first, dealership, pkg, day, start)
      val places = Api.field(slot, "capacity").toString.toDouble.toInt
      ui.step(s"$places place(s) left at $start; other customers take them")
      places should (be >= 1 and be <= 5)
      otherHolds = (1 to places).map { i =>
        val other = if i == 1 then first else Api.signIn(s"walkin$i@demo.vn")
        other -> Api.hold(other, dealership, pkg, Api.field(slot, "start").toString)
      }

      ui.clickButton("Hold this time and review")
      ui.eventually("the page says the time was taken") { ui.alertText.exists(_.contains(s"Someone booked $start a moment ago")) }
      ui.alertText.get should include("nearest free times")
      val alternatives = ui.all(By.cssSelector(".stack .slots button.slot"))
      alternatives should not be empty
      alternatives.size should be <= 3
      ui.all(By.cssSelector("[role='timer']")) shouldBe empty
      ui.visible(ui.button("Confirm booking")).isEnabled shouldBe false

      val alternative = alternatives.head.getText.linesIterator.next()
      ui.step(s"take the alternative $alternative")
      ui.click(alternatives.head)
      ui.visible(By.cssSelector("[role='timer']"))
      ui.clickButton("Confirm booking")
      ui.see("You're booked in")
      reference = Some(ui.visible(By.cssSelector(".confirmed .ref")).getText.trim)
      ui.see(alternative)
    }

    it("downloads the booking as a calendar file (UC11)") {
      val ref = reference.getOrElse(cancel("the booking step did not finish"))
      val file = Settings.downloads.resolve(s"service-$ref.ics")
      Files.deleteIfExists(file)
      ui.clickButton("Add to calendar")
      ui.eventually(s"$file is saved") { Files.exists(file) && Files.size(file) > 0 }
      val ics = Files.readAllLines(file).asScala.mkString("\n")
      ics should include("BEGIN:VEVENT")
      ics should include(ref)
      ics should include(bike)
      ics should include("Essential maintenance")
    }
  }

  override def afterAll(): Unit =
    otherHolds.foreach((api, id) => scala.util.Try(Api.cancel(api, id)))
    reference.foreach { ref =>
      scala.util.Try {
        ui.step(s"clean-up: cancel $ref")
        cancelFromMyAppointments(ref)
      }
    }
    super.afterAll()
