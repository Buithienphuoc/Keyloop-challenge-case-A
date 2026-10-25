package scheduler.shared

import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.net.URI
import java.sql.{Connection, PreparedStatement, SQLException}
import javax.sql.DataSource

import scala.util.control.NonFatal

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

/** The JDBC connection behind a [[DbSession]]. Only adapters (code that talks SQL) unwrap it. */
final class JdbcSession(val connection: Connection) extends DbSession

/**
 * PostgreSQL adapter for the [[Db]] port: a HikariCP pool, transactions, slow-query logging
 * and query-latency metrics.
 *
 * JDBC calls block the calling thread. That is fine here because the HTTP layer runs every
 * use case on a Java 21 virtual thread (see `Blocking` in http/Http.scala): a blocked virtual thread
 * costs a few hundred bytes, not an OS thread. The pool size is what limits how much work
 * reaches PostgreSQL at once.
 */
final class PgDb private (val dataSource: HikariDataSource, log: Log, slowQueryMs: Long) extends Db:

  /**
   * The session this thread already holds. A use case may call a port that opens its own
   * `run` while it is inside another `run` or `tx` (e.g. reading the catalogue while
   * computing availability). Reusing the open connection means such nesting never waits
   * for a second pooled connection: under a burst, waiting while holding one would let every
   * connection be held by a request waiting for another, and the pool would starve.
   */
  private val current = ThreadLocal[JdbcSession]()

  def run[T](f: DbSession => T): T =
    val open = current.get
    if open != null then f(open) else withConnection(f)

  def tx[T](f: DbSession => T): T =
    val open = current.get
    if open == null then withConnection(transaction(_, f))
    else if !open.connection.getAutoCommit then f(open) // joins the enclosing transaction
    else transaction(open, f)

  private def withConnection[T](f: JdbcSession => T): T =
    val c = dataSource.getConnection
    val session = JdbcSession(timed(c))
    current.set(session)
    try f(session)
    finally
      current.remove()
      c.close()

  private def transaction[T](session: JdbcSession, f: DbSession => T): T =
    val c = session.connection
    c.setAutoCommit(false)
    try
      try
        val result = f(session)
        c.commit()
        result
      catch
        case NonFatal(e) =>
          try c.rollback()
          catch case NonFatal(_) => ()
          throw (if PgErrors.isTransient(e) then TransientStorageError(e) else e)
    finally c.setAutoCommit(true)

  def close(): Unit = dataSource.close()

  /** Health probe: can we reach PostgreSQL within two seconds? */
  def isUp(): Boolean =
    try
      val c = dataSource.getConnection
      try c.isValid(2)
      finally c.close()
    catch case NonFatal(_) => false

  /**
   * Wraps a connection so that every statement it prepares is timed. Logs only the statement
   * shape when it is slow, never parameter values (they can contain personal data).
   */
  private def timed(c: Connection): Connection =
    proxy(classOf[Connection]) { (method, args) =>
      val result = method.invoke(c, args*)
      result match
        case ps: PreparedStatement if method.getName == "prepareStatement" =>
          val sql = args.head.toString
          proxy(classOf[PreparedStatement]) { (m, a) =>
            if m.getName.startsWith("execute") then
              val started = System.nanoTime()
              try m.invoke(ps, a*)
              finally
                val ms = (System.nanoTime() - started) / 1e6
                Metrics.dbQueryDuration.observe(ms / 1000)
                if ms >= slowQueryMs then
                  log.warn("db.slow_query", "durationMs" -> math.round(ms), "sql" -> sql.replaceAll("\\s+", " ").take(300))
            else m.invoke(ps, a*)
          }
        case other => other
    }

  private def proxy[T](iface: Class[T])(handle: (Method, Array[AnyRef]) => AnyRef): T =
    val handler: InvocationHandler = (_, method, args) =>
      try handle(method, Option(args).getOrElse(Array.empty))
      catch case e: InvocationTargetException => throw e.getCause
    iface.cast(Proxy.newProxyInstance(iface.getClassLoader, Array(iface), handler))

object PgDb:
  def apply(url: String, log: Log, slowQueryMs: Long, poolSize: Int): PgDb =
    val cfg = HikariConfig()
    val (jdbcUrl, user, password) = toJdbc(url)
    cfg.setJdbcUrl(jdbcUrl)
    user.foreach(cfg.setUsername)
    password.foreach(cfg.setPassword)
    cfg.setMaximumPoolSize(poolSize)
    cfg.setPoolName("scheduler")
    // Strings are sent untyped, so PostgreSQL infers uuid / text / enum-like columns itself.
    cfg.addDataSourceProperty("stringtype", "unspecified")
    new PgDb(HikariDataSource(cfg), log, slowQueryMs)

  /** Accepts `postgres://user:pass@host:port/db` (as in docker-compose) or a plain `jdbc:postgresql:` URL. */
  def toJdbc(url: String): (String, Option[String], Option[String]) =
    if url.startsWith("jdbc:") then (url, None, None)
    else
      val u = URI(url)
      val (user, password) = Option(u.getUserInfo).map(_.split(":", 2)) match
        case Some(Array(name, pw)) => (Some(name), Some(pw))
        case Some(Array(name))     => (Some(name), None)
        case _                     => (None, None)
      val port = if u.getPort > 0 then s":${u.getPort}" else ""
      val query = Option(u.getQuery).map("?" + _).getOrElse("")
      (s"jdbc:postgresql://${u.getHost}$port${u.getPath}$query", user, password)

  def dataSourceOf(db: Db): Option[DataSource] = db match
    case pg: PgDb => Some(pg.dataSource)
    case _        => None

/**
 * PostgreSQL error helpers. Only adapters use these; application services see [[AppError]]
 * or [[TransientStorageError]] instead.
 */
object PgErrors:
  val UniqueViolation = "23505"
  val ExclusionViolation = "23P01"
  val ForeignKeyViolation = "23503"
  val CheckViolation = "23514"
  val SerializationFailure = "40001"
  val DeadlockDetected = "40P01"

  def sqlState(e: Throwable): Option[String] = e match
    case s: SQLException => Option(s.getSQLState)
    case _               => Option(e.getCause).flatMap(sqlState)

  def constraint(e: Throwable): Option[String] = e match
    case p: org.postgresql.util.PSQLException => Option(p.getServerErrorMessage).flatMap(m => Option(m.getConstraint))
    case _                                    => Option(e.getCause).flatMap(constraint)

  def isTransient(e: Throwable): Boolean = sqlState(e).exists(s => s == DeadlockDetected || s == SerializationFailure)
