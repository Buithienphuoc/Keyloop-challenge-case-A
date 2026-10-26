package scheduler.e2e

import java.nio.file.Path

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, Failed, Outcome}

/**
 * One browser per suite. Tests inside a suite run in order and may build on each other
 * (a booking made by the customer is then handled by staff); a later step is cancelled,
 * not failed, when the step it needs did not happen.
 * A failing test leaves a screenshot in e2e/target/screenshots/.
 */
abstract class E2ESuite extends AnyFunSpec with Matchers with BeforeAndAfterAll:
  private var browser: Option[Ui] = None
  protected def ui: Ui = browser.getOrElse(throw IllegalStateException("browser not started"))

  override def beforeAll(): Unit =
    browser = Some(Ui.start())
    ui.step(s"${getClass.getSimpleName}: testing ${Settings.baseUrl}")

  override def afterAll(): Unit = browser.foreach(_.quit())

  override def withFixture(test: NoArgTest): Outcome =
    val outcome = super.withFixture(test)
    outcome match
      case Failed(_) =>
        val name = s"${getClass.getSimpleName}-${test.name}".replaceAll("[^A-Za-z0-9._-]+", "_").take(150)
        browser.foreach(b => scala.util.Try(b.screenshot(Path.of("target", "screenshots", s"$name.png"))))
      case _ => ()
    outcome
