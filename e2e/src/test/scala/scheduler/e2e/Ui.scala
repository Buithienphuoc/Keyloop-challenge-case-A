package scheduler.e2e

import java.nio.file.{Files, Path}
import java.time.{Duration, LocalDate, ZoneId}

import scala.jdk.CollectionConverters.*
import scala.util.Try

import org.openqa.selenium.chrome.{ChromeDriver, ChromeOptions}
import org.openqa.selenium.support.ui.{ExpectedConditions as EC, Select, WebDriverWait}
import org.openqa.selenium.{By, OutputType, TakesScreenshot, WebDriver, WebElement}
import org.slf4j.LoggerFactory

/** Where the tests point and how the browser starts. Every value can come from the environment. */
object Settings:
  /** The web app (nginx in front of the API), e.g. docker compose's http://localhost:8080. */
  val baseUrl: String = sys.env.getOrElse("BASE_URL", "http://localhost:8080").stripSuffix("/")
  /** HEADLESS=false opens a visible window, handy when writing a new test. */
  val headless: Boolean = !sys.env.get("HEADLESS").contains("false")
  /** Optional: a Chrome binary and a matching chromedriver; otherwise Selenium Manager finds them. */
  val chromeBinary: Option[String] = sys.env.get("CHROME_BIN").filter(_.nonEmpty)
  val chromeDriver: Option[String] = sys.env.get("CHROMEDRIVER").filter(_.nonEmpty)
  val timeout: Duration = Duration.ofSeconds(sys.env.get("E2E_TIMEOUT_SECONDS").flatMap(_.toIntOption).getOrElse(20).toLong)

  /** The demo dealership's zone: "today" in the app means today in Ho Chi Minh City. */
  val Zone: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
  val DemoPassword = "Demo@2026"

  /** Where Chrome saves downloads (the .ics calendar file). */
  val downloads: Path = Path.of("target", "downloads").toAbsolutePath

/**
 * A thin layer over Selenium for this app: find things the way a person would (by visible
 * text, label or role), wait for them instead of sleeping, and log each step so a failing
 * run reads like a story.
 */
final class Ui(val driver: WebDriver):
  private val log = LoggerFactory.getLogger("e2e")
  private val waiter = WebDriverWait(driver, Settings.timeout)

  def step(what: String): Unit = log.info(what)

  // ---- navigation ------------------------------------------------------------------------------

  def open(path: String): Unit =
    step(s"open $path")
    driver.get(Settings.baseUrl + path)

  def path: String = java.net.URI(driver.getCurrentUrl).getPath

  def waitForPath(expected: String): Unit =
    waiter.until(_ => path == expected)

  // ---- finding ---------------------------------------------------------------------------------

  /** An element whose own text, trimmed, is exactly `text` (buttons, links, tabs, headings). */
  def exact(tag: String, text: String): By = By.xpath(s"//$tag[normalize-space(.)=${Ui.literal(text)}]")

  /** The innermost elements of `tag` that contain `text` somewhere inside. */
  def containing(tag: String, text: String): By =
    By.xpath(s"//$tag[contains(normalize-space(.), ${Ui.literal(text)})]")

  def button(text: String): By = exact("button", text)

  def visible(by: By): WebElement = waiter.until(EC.visibilityOfElementLocated(by))

  def all(by: By): Seq[WebElement] = driver.findElements(by).asScala.toSeq

  /** Waits until the page shows `text` anywhere. */
  def see(text: String): Unit =
    step(s"expect to see \"$text\"")
    waiter.until(_ => driver.findElement(By.tagName("body")).getText.contains(text))

  def notSee(text: String): Boolean = !driver.findElement(By.tagName("body")).getText.contains(text)

  /** Waits for a condition the page reaches on its own (a refetch, a redirect). */
  def eventually(what: String)(condition: => Boolean): Unit =
    step(s"wait until $what")
    waiter.until(_ => Try(condition).getOrElse(false))

  // ---- acting ----------------------------------------------------------------------------------

  def click(by: By): Unit =
    val e = waiter.until(EC.elementToBeClickable(by))
    // Sticky headers can cover an element near the top; scroll it to the middle first.
    scrollIntoView(e)
    waiter.until(EC.elementToBeClickable(e)).click()

  def click(e: WebElement): Unit =
    scrollIntoView(e)
    waiter.until(EC.elementToBeClickable(e)).click()

  def clickButton(text: String): Unit =
    step(s"click \"$text\"")
    click(button(text))

  /** Types into the input or textarea whose <label> says `label`. */
  def fill(label: String, value: String): Unit =
    step(s"fill \"$label\"")
    val input = visible(inputFor(label))
    input.clear()
    input.sendKeys(value)

  /** Types into the input whose id is `id`, for forms where two fields share a label. */
  def fillId(id: String, value: String): Unit =
    step(s"fill #$id")
    val input = visible(By.id(id))
    input.clear()
    input.sendKeys(value)

  /** The input, select or textarea labelled `label` inside `scope` (one card among many). */
  def inputIn(scope: WebElement, label: String): WebElement =
    val forId = scope.findElement(By.xpath(s".//label[normalize-space(.)=${Ui.literal(label)}]")).getAttribute("for")
    scope.findElement(By.id(forId))

  def select(label: String): Select = Select(visible(inputFor(label)))

  def inputFor(label: String): By =
    By.xpath(s"//*[@id=//label[normalize-space(.)=${Ui.literal(label)}]/@for]")

  private def scrollIntoView(e: WebElement): Unit =
    driver.asInstanceOf[org.openqa.selenium.JavascriptExecutor].executeScript("arguments[0].scrollIntoView({block: 'center'})", e)

  // ---- the app's own building blocks -----------------------------------------------------------

  def signIn(email: String, password: String = Settings.DemoPassword): Unit =
    step(s"sign in as $email")
    open("/login")
    // Someone may still be signed in (a previous step failed half-way): sign them out first.
    waiter.until(_ => all(By.id("email")).nonEmpty || all(button("Sign out")).nonEmpty)
    if all(By.id("email")).isEmpty then
      signOut()
      open("/login")
    fill("Email", email)
    fill("Password", password)
    clickButton("Sign in")
    visible(exact("button", "Sign out"))

  def signOut(): Unit =
    step("sign out")
    clickButton("Sign out")
    waitForPath("/login")

  /** Moves a page's day picker (‹ date ›) forward to `date`. */
  def goToDay(date: LocalDate): Unit =
    val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(Settings.Zone), date).toInt
    step(s"go $days day(s) ahead to $date")
    for _ <- 1 to days do click(By.cssSelector("button[aria-label='Next day']"))
    waiter.until(_ => driver.findElement(By.cssSelector("input[aria-label='Date']")).getAttribute("value") == date.toString)

  /** The error message the app shows in a role="alert" box, if any. */
  def alertText: Option[String] = all(By.cssSelector("[role='alert']")).headOption.map(_.getText)

  def screenshot(to: Path): Unit =
    Files.createDirectories(to.getParent)
    val png = driver.asInstanceOf[TakesScreenshot].getScreenshotAs(OutputType.BYTES)
    Files.write(to, png)
    step(s"screenshot saved to $to")

  def quit(): Unit = Try(driver.quit())

object Ui:
  def start(): Ui =
    Settings.chromeDriver.foreach(p => System.setProperty("webdriver.chrome.driver", p))
    val options = ChromeOptions()
    Settings.chromeBinary.foreach(b => options.setBinary(b))
    if Settings.headless then options.addArguments("--headless=new")
    options.addArguments(
      "--window-size=1440,1000",
      "--lang=en-US",
      "--no-sandbox", // containers and CI runners
      "--disable-dev-shm-usage",
      "--disable-search-engine-choice-screen"
    )
    Files.createDirectories(Settings.downloads)
    options.setExperimentalOption("prefs", java.util.Map.of(
      "download.default_directory", Settings.downloads.toString,
      "download.prompt_for_download", false
    ))
    // The app is on plain http://localhost; never send it through an outbound proxy.
    options.addArguments("--proxy-server=direct://", "--proxy-bypass-list=*")
    val driver = ChromeDriver(options)
    driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30))
    Ui(driver)

  /** An XPath string literal for any text, including one with both kinds of quote. */
  def literal(s: String): String =
    if !s.contains("'") then s"'$s'"
    else if !s.contains("\"") then s"\"$s\""
    else s.split("'", -1).map(p => s"'$p'").mkString("concat(", ", \"'\", ", ")")
