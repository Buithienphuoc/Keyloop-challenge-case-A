package scheduler.unit

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

/**
 * The dependency rule of the hexagonal architecture, checked on every test run:
 * domain and application code (and the ports they own) never import adapters, HTTP,
 * Play's MVC layer, the database driver, Anorm or SQL helpers. Breaking it fails the
 * build, instead of relying on code review to notice.
 */
class ArchitectureSpec extends AnyFunSpec with Matchers:
  private val Src = Path.of("app/scheduler").toAbsolutePath

  private def scalaFilesUnder(dir: Path): Seq[Path] =
    Files.walk(dir).iterator.asScala.filter(_.toString.endsWith(".scala")).toSeq

  private def rel(f: Path) = Src.relativize(f).toString.replace('\\', '/')
  private def importsOf(f: Path): Seq[String] =
    Files.readAllLines(f).asScala.map(_.trim).collect { case l if l.startsWith("import ") => l.stripPrefix("import ").trim }.toSeq

  /** Inner layers: domain/ and application/ folders, plus each module's Domain.scala and Ports.scala. */
  private val inner = scalaFilesUnder(Src.resolve("modules")).filter { f =>
    val r = "/" + rel(f)
    r.contains("/domain/") || r.contains("/application/") || r.endsWith("/Domain.scala") || r.endsWith("/Ports.scala")
  }

  private val Forbidden = Seq(
    ".adapters." -> "an adapter",
    "scheduler.http" -> "the HTTP layer",
    "play.api.mvc" -> "Play's MVC layer",
    "anorm" -> "the SQL library",
    "java.sql" -> "JDBC",
    "org.postgresql" -> "the PostgreSQL driver",
    "PgErrors" -> "PostgreSQL error codes",
    "scheduler.shared.Sql" -> "SQL helpers",
    "scheduler.shared.PgDb" -> "the PostgreSQL pool"
  )

  describe("dependency rule (ports and adapters)") {
    it("finds the inner-layer files it is meant to check") {
      inner.size should be > 20
    }

    for f <- inner.sortBy(rel) do
      it(s"${rel(f)} depends only inward") {
        val violations = for
          spec <- importsOf(f)
          (pattern, why) <- Forbidden
          if spec.startsWith(pattern) || spec.contains(pattern)
        yield s"imports $spec ($why)"
        violations shouldBe empty
      }

    it("domain code is pure: only the JDK, its own package and the Role type from identity") {
      for
        f <- scalaFilesUnder(Src.resolve("modules/scheduling/domain"))
        spec <- importsOf(f)
      do
        withClue(s"${rel(f)} imports $spec: ") {
          (spec.startsWith("java.") || spec.startsWith("scala.") || spec == "scheduler.modules.identity.Role" ||
            spec.head.isUpper /* a relative import from its own package, e.g. AppointmentStatus.* */) shouldBe true
        }
    }
  }
