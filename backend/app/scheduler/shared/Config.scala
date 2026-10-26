package scheduler.shared

import java.nio.file.Path

import play.api.Configuration

/** Settings from conf/application.conf; each one can be overridden by an environment variable. */
final case class Config(
    profile: Profile,
    /** When empty, the server starts an embedded PostgreSQL so the app runs with one command. */
    databaseUrl: Option[String],
    embeddedPgPort: Int,
    dataDir: Path,
    slowQueryMs: Long,
    sessionTtlHours: Double,
    seedDemoData: Boolean,
    corsOrigins: Seq[String],
    dbPoolSize: Int,
    holdMinutes: Int,
    holdSweepSeconds: Int,
    /** prod only: demo account email (lower case) -> password or stored hash. */
    seedAccountPasswords: Map[String, String]
)

object Config:
  def from(c: Configuration, rootPath: java.io.File): Config =
    val dataDir = Path.of(c.get[String]("scheduler.data-dir"))
    Config(
      profile = Profile.parse(c.get[String]("scheduler.profile")),
      databaseUrl = c.getOptional[String]("scheduler.database-url").map(_.trim).filter(_.nonEmpty),
      embeddedPgPort = c.get[Int]("scheduler.embedded-pg-port"),
      dataDir = if dataDir.isAbsolute then dataDir else rootPath.toPath.resolve(dataDir).normalize(),
      slowQueryMs = c.get[Long]("scheduler.slow-query-ms"),
      sessionTtlHours = c.get[Double]("scheduler.session-ttl-hours"),
      seedDemoData = c.get[Boolean]("scheduler.seed-demo-data"),
      corsOrigins = c.get[String]("scheduler.cors-origins").split(",").map(_.trim).filter(_.nonEmpty).toSeq,
      dbPoolSize = c.get[Int]("scheduler.db-pool-size"),
      holdMinutes = c.get[Int]("scheduler.hold-minutes"),
      holdSweepSeconds = c.get[Int]("scheduler.hold-sweep-seconds"),
      seedAccountPasswords = parsePasswords(c.get[String]("scheduler.seed-account-passwords"))
    )

  /**
   * Refuses settings that would expose the demo accounts: the dev profile on a hosted platform
   * (Azure Container Apps sets CONTAINER_APP_NAME), or prod without a real database or secret key.
   */
  def checkProfile(config: Config, playSecret: String, env: Map[String, String]): Unit =
    config.profile match
      case Profile.Dev =>
        require(!env.contains("CONTAINER_APP_NAME"),
          "APP_PROFILE=dev is for localhost only: set APP_PROFILE=prod on a hosted deployment")
      case Profile.Prod =>
        require(config.databaseUrl.isDefined, "APP_PROFILE=prod needs DATABASE_URL (no embedded database in production)")
        require(!playSecret.startsWith("dev-only-"), "APP_PROFILE=prod needs APPLICATION_SECRET")

  /** "email=password" entries separated by new lines or ";". The password may itself hold "=". */
  def parsePasswords(text: String): Map[String, String] =
    text.split("[;\\r\\n]+").iterator.map(_.trim).filter(_.nonEmpty).map { entry =>
      entry.split("=", 2) match
        case Array(email, password) if email.trim.nonEmpty && password.trim.nonEmpty => email.trim.toLowerCase -> password.trim
        case _ => throw IllegalArgumentException("SEED_ACCOUNT_PASSWORDS: every entry must be email=password")
    }.toMap

/** Where the server runs. Decides how the demo accounts are protected. */
enum Profile:
  case Dev, Prod

object Profile:
  def parse(value: String): Profile = value.trim.toLowerCase match
    case "dev" | "development"  => Dev
    case "prod" | "production" => Prod
    case other => throw IllegalArgumentException(s"APP_PROFILE must be dev or prod, not '$other'")
