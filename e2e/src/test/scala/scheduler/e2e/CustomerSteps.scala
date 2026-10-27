package scheduler.e2e

import java.time.format.TextStyle
import java.time.{DayOfWeek, LocalDate}
import java.util.Locale

import org.openqa.selenium.By

/** Customer steps that several suites walk through: register, add a vehicle, pick a day and a time, hold and confirm. */
trait CustomerSteps:
  self: E2ESuite =>

  /** A unique tag for this run, so names, plates and emails never clash with an earlier run. */
  protected val run: String = java.lang.Long.toString(System.currentTimeMillis(), 36)

  /** A plate no other run has used (the API keeps plates unique per owner). */
  protected def newPlate(prefix: String): String =
    f"$prefix ${System.currentTimeMillis() / 1000 % 1000}%03d.${System.nanoTime() % 100}%02d"

  /**
   * The `n`-th weekday (Monday to Friday) from `from` days out: every District 1 technician
   * works then, and the demo data never books that far ahead.
   */
  protected def quietWeekday(n: Int, from: Int = 4): LocalDate =
    Iterator.iterate(LocalDate.now(Settings.Zone).plusDays(from.toLong))(_.plusDays(1))
      .filter(_.getDayOfWeek.getValue <= DayOfWeek.FRIDAY.getValue).drop(n).next()

  protected def register(name: String, email: String, password: String): Unit =
    ui.open("/register")
    ui.fill("Your name", name)
    ui.fill("Email", email)
    ui.fill("Password", password)
    ui.clickButton("Create account")
    ui.visible(ui.button("Sign out"))
    ui.see(name)

  /** Fills the vehicle form that the booking, request and vehicles pages share. */
  protected def fillVehicle(make: String, model: String, plate: String, car: Boolean = false): Unit =
    // The radio itself is visually hidden; its label is what a person clicks.
    if car then ui.click(By.xpath("//label[.//input[@name='vehicleType' and @value='CAR']]"))
    ui.fill("Make", make)
    ui.fill("Model", model)
    ui.fill("Plate number", plate)
    ui.clickButton("Add vehicle")

  /** On the "When?" step: chooses `day` in the day strip. */
  protected def pickDay(day: LocalDate): Unit =
    val month = day.getMonth.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    ui.step(s"pick $day")
    ui.click(By.xpath(
      s"//div[@aria-label='Day']//button[.//div[@class='n' and text()='${day.getDayOfMonth}'] and .//div[@class='mo' and text()='$month']]"))
    ui.eventually(s"$day is selected") {
      ui.all(By.cssSelector("div[aria-label='Day'] button[aria-pressed='true']")).exists(b =>
        b.getText.contains(month) && b.getText.linesIterator.contains(day.getDayOfMonth.toString))
    }

  /** Picks the last free afternoon start (the quietest part of a quiet day) and returns it as "HH:mm". */
  protected def pickLastAfternoonSlot(): String =
    val free = By.cssSelector("[aria-label='Afternoon start times'] button.slot:not([disabled])")
    ui.visible(free)
    val slot = ui.all(free).last
    val start = slot.getText.linesIterator.next()
    ui.step(s"pick $start")
    ui.click(slot)
    start

  /** Holds the chosen time, confirms it, and returns the booking reference. */
  protected def holdAndConfirm(): String =
    ui.clickButton("Hold this time and review")
    ui.visible(By.cssSelector("[role='timer']"))
    ui.clickButton("Confirm booking")
    ui.see("You're booked in")
    val ref = ui.visible(By.cssSelector(".confirmed .ref")).getText.trim
    ui.step(s"booked $ref")
    ref

  /** The vehicle step's choice for `plate`. */
  protected def vehicleChoice(plate: String): By =
    By.xpath(s"//button[contains(@class,'choice') and .//*[normalize-space(.)=${Ui.literal(plate)}]]")

  /**
   * Books Essential maintenance at District 1 on `day` and returns the reference. Without a
   * plate the customer has one motorbike, and the booking page skips the vehicle step.
   */
  protected def bookEssential(day: LocalDate, plate: String = ""): String =
    ui.open("/book")
    if plate.nonEmpty then
      ui.see("Which vehicle?")
      ui.click(vehicleChoice(plate))
    ui.see("What should we do?")
    ui.click(ui.containing("button", "Essential maintenance"))
    ui.clickButton("Choose a time")
    ui.click(ui.containing("button", "District 1"))
    pickDay(day)
    pickLastAfternoonSlot()
    holdAndConfirm()

  /** The customer's row for booking `ref` in My appointments. */
  protected def appointmentRow(ref: String): By =
    By.xpath(s"//div[contains(@class,'item') and contains(normalize-space(.), ${Ui.literal(ref)})]")

  /** Cancels booking `ref` from My appointments (as its customer). */
  protected def cancelFromMyAppointments(ref: String): Unit =
    ui.open("/appointments")
    val item = appointmentRow(ref)
    ui.click(ui.visible(item).findElement(By.xpath(".//button[normalize-space(.)='Cancel']")))
    ui.click(ui.visible(item).findElement(By.xpath(".//button[normalize-space(.)='Yes, cancel it']")))
    ui.eventually(s"$ref is cancelled") { ui.visible(item).getText.contains("Cancelled") }
