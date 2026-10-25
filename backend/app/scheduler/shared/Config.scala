package scheduler.shared

import java.nio.file.Path

import play.api.Configuration

/** Settings from conf/application.conf; each one can be overridden by an environment variable. */
final case class Config(
    /** When empty, the server starts an embedded PostgreSQL so the app runs with one command. */
    databaseUrl: Option[String],
    embeddedPgPort: Int,
    dataDir: Path,
    slowQueryMs: Long,
    sessionTtlHours: Double,
    seedDemoData: Boolean,
    corsOrigins: Seq[String],
    dbPoolSize: Int
)

object Config:
  def from(c: Configuration, rootPath: java.io.File): Config =
    val dataDir = Path.of(c.get[String]("scheduler.data-dir"))
    Config(
      databaseUrl = c.getOptional[String]("scheduler.database-url").map(_.trim).filter(_.nonEmpty),
      embeddedPgPort = c.get[Int]("scheduler.embedded-pg-port"),
      dataDir = if dataDir.isAbsolute then dataDir else rootPath.toPath.resolve(dataDir).normalize(),
      slowQueryMs = c.get[Long]("scheduler.slow-query-ms"),
      sessionTtlHours = c.get[Double]("scheduler.session-ttl-hours"),
      seedDemoData = c.get[Boolean]("scheduler.seed-demo-data"),
      corsOrigins = c.get[String]("scheduler.cors-origins").split(",").map(_.trim).filter(_.nonEmpty).toSeq,
      dbPoolSize = c.get[Int]("scheduler.db-pool-size")
    )
