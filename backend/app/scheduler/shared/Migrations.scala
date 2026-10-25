package scheduler.shared

import javax.sql.DataSource

import org.flywaydb.core.Flyway

/**
 * Applies the numbered SQL files in conf/db/migration (V1__init.sql, …) in order, once each.
 * Flyway records them in `flyway_schema_history` and takes a lock so two instances never
 * migrate at the same time.
 */
object Migrations:
  def run(dataSource: DataSource, log: Log): Unit =
    val result = Flyway.configure()
      .dataSource(dataSource)
      .locations("classpath:db/migration")
      .load()
      .migrate()
    if result.migrationsExecuted > 0 then
      log.info("db.migrations_applied", "count" -> result.migrationsExecuted, "version" -> result.targetSchemaVersion)
