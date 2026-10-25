package scheduler.shared

import java.nio.file.{Files, Path}

import scala.util.Using

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

/** A running embedded PostgreSQL and the URL of its application database. */
final class EmbeddedDatabase(pg: EmbeddedPostgres, val jdbcUrl: String):
  def stop(): Unit = pg.close()

/**
 * Starts a real PostgreSQL server (not an imitation) from binaries packaged on Maven Central.
 * Used when DATABASE_URL is not set, so `sbt run` works without Docker.
 */
object EmbeddedDb:
  def start(dataDir: Path, port: Int, log: Log, persistent: Boolean = true, database: String = "scheduler"): EmbeddedDatabase =
    val dir = dataDir.resolve(s"pg-$port")
    Files.createDirectories(dataDir)
    val fresh = !Files.exists(dir.resolve("PG_VERSION"))
    val pg = EmbeddedPostgres.builder()
      .setPort(port)
      .setDataDirectory(dir)
      .setCleanDataDirectory(!persistent)
      .setServerConfig("max_connections", "200")
      .start()
    Using.resource(pg.getPostgresDatabase.getConnection) { c =>
      val exists = Using.resource(c.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) { ps =>
        ps.setString(1, database)
        ps.executeQuery().next()
      }
      if !exists then Using.resource(c.createStatement())(_.execute(s"CREATE DATABASE \"$database\""))
    }
    log.info("embedded_pg.started", "port" -> port, "dir" -> dir.toString, "fresh" -> fresh)
    EmbeddedDatabase(pg, pg.getJdbcUrl("postgres", database))
