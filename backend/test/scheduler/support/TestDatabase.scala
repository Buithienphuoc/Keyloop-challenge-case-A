package scheduler.support

import java.nio.file.{Files, Path}
import java.sql.DriverManager
import java.util.UUID

import scala.util.Using

import scheduler.shared.{EmbeddedDb, Log}

/**
 * One throw-away PostgreSQL for the whole test run (started on first use, stopped when the
 * JVM exits), unless TEST_DATABASE_URL points at one, e.g. a service container in CI.
 * Every suite gets its own freshly created database, so suites never see each other's rows.
 */
object TestDatabase:
  private val Port = sys.env.get("TEST_PG_PORT").map(_.toInt).getOrElse(54331)

  /** JDBC URL of a database we may CREATE DATABASE from. */
  private lazy val adminUrl: String = sys.env.get("TEST_DATABASE_URL").filter(_.nonEmpty).getOrElse {
    val dataDir = Path.of("../.data/test").toAbsolutePath.normalize()
    Files.createDirectories(dataDir)
    val pg = EmbeddedDb.start(dataDir, Port, Log.silent, persistent = false, database = "admin")
    sys.addShutdownHook(pg.stop())
    pg.jdbcUrl
  }

  /** Creates an empty database for the calling suite and returns its JDBC URL. Migrations run when the app starts. */
  def fresh(): String =
    val name = s"t_${UUID.randomUUID().toString.replace("-", "").take(16)}"
    Using.resource(DriverManager.getConnection(adminUrl))(_.createStatement().execute(s"CREATE DATABASE $name"))
    withDatabase(adminUrl, name)

  private def withDatabase(url: String, name: String): String =
    val (base, query) = url.span(_ != '?')
    base.substring(0, base.lastIndexOf('/') + 1) + name + query
