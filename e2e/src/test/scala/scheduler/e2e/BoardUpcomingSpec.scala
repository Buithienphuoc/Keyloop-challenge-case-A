package scheduler.e2e

import org.openqa.selenium.By

/**
 * A booking made by a customer must reach the advisor even though the board opens on today
 * and customers can only book a little ahead: the board's "Coming up" list shows it, and one
 * click opens it on its day. Ends by cancelling the booking, so the demo data is left as it was.
 */
class BoardUpcomingSpec extends E2ESuite with CustomerSteps:
  private val email = s"e2e-upcoming-$run@demo.vn"
  private val password = "E2eTest2026"
  private val plate = newPlate("59-U1")
  // Close enough to be among the soonest bookings, far enough to never be today.
  private val day = quietWeekday(0, from = 2)

  private var reference: Option[String] = None
  private def needsBooking(): String = { assume(reference.isDefined, "the booking step did not finish"); reference.get }

  private def comingUp = By.cssSelector("[aria-label='Coming up']")

  describe("Customer") {
    it("books a service on a later day") {
      register("E2E Upcoming", email, password)
      ui.open("/vehicles")
      ui.clickButton("Add your first vehicle")
      fillVehicle("Honda", "Vision", plate)
      ui.see(plate)
      reference = Some(bookEssential(day))
      ui.signOut()
    }
  }

  describe("Service advisor") {
    it("sees the new booking under Coming up while the board still shows today") {
      val ref = needsBooking()
      ui.signIn("advisor.d1@demo.vn")
      ui.waitForPath("/staff/advisor-appointments")
      ui.open("/staff/board")
      ui.eventually(s"$ref is listed under Coming up") { ui.visible(comingUp).getText.contains(ref) }
      ui.visible(By.cssSelector("input[aria-label='Date']")).getAttribute("value") should not be day.toString
    }

    it("opens the booking on its day with one click") {
      val ref = needsBooking()
      ui.click(ui.visible(comingUp).findElement(By.xpath(s".//button[contains(normalize-space(.), ${Ui.literal(ref)})]")))
      ui.eventually(s"the board moves to $day") {
        ui.visible(By.cssSelector("input[aria-label='Date']")).getAttribute("value") == day.toString
      }
      ui.visible(By.xpath(s"//button[contains(@class,'job') and .//strong[normalize-space(.)=${Ui.literal(plate)}]]"))
      ui.visible(By.cssSelector("aside")).getText should include(ref)
    }

    it("cancels it, and it leaves Coming up") {
      val ref = needsBooking()
      ui.clickButton("Cancel booking")
      ui.eventually("the panel says Cancelled") { ui.visible(By.cssSelector("aside .pill")).getText == "Cancelled" }
      ui.clickButton("Close")
      ui.eventually(s"$ref leaves Coming up") { !ui.visible(comingUp).getText.contains(ref) }
    }
  }
