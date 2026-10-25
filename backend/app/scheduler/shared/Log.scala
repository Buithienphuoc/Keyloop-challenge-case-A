package scheduler.shared

import scala.jdk.CollectionConverters.*

import net.logstash.logback.argument.StructuredArguments
import org.slf4j.LoggerFactory

/**
 * The logging surface services depend on. Every event is one structured JSON line with a
 * stable name (for example `booking.created`) and typed fields, so logs can be queried by field.
 * `child` adds fields to every later line, which is how a request's `requestId`, `userId`,
 * `role` and `dealershipId` end up on everything logged while serving it.
 */
trait Log:
  def debug(event: String, fields: (String, Any)*): Unit
  def info(event: String, fields: (String, Any)*): Unit
  def warn(event: String, fields: (String, Any)*): Unit
  def error(event: String, cause: Throwable, fields: (String, Any)*): Unit
  def child(fields: (String, Any)*): Log

object Log:
  /** The application logger; JSON or pretty output is chosen in logback.xml. */
  def apply(name: String = "scheduler"): Log = Slf4jLog(LoggerFactory.getLogger(name), Vector.empty)

  /** Discards everything (unit tests). */
  val silent: Log = new Log:
    def debug(event: String, fields: (String, Any)*): Unit = ()
    def info(event: String, fields: (String, Any)*): Unit = ()
    def warn(event: String, fields: (String, Any)*): Unit = ()
    def error(event: String, cause: Throwable, fields: (String, Any)*): Unit = ()
    def child(fields: (String, Any)*): Log = this

  /** Keep personal data out of logs while still making failed logins traceable. */
  def maskEmail(email: String): String = email.split("@", 2) match
    case Array(local, domain) if domain.nonEmpty => s"${local.take(1)}***@$domain"
    case _                                     => "***"

private final class Slf4jLog(underlying: org.slf4j.Logger, base: Vector[(String, Any)]) extends Log:
  def debug(event: String, fields: (String, Any)*): Unit =
    if underlying.isDebugEnabled then underlying.debug(message(event, fields), args(fields))
  def info(event: String, fields: (String, Any)*): Unit =
    if underlying.isInfoEnabled then underlying.info(message(event, fields), args(fields))
  def warn(event: String, fields: (String, Any)*): Unit =
    if underlying.isWarnEnabled then underlying.warn(message(event, fields), args(fields))
  def error(event: String, cause: Throwable, fields: (String, Any)*): Unit =
    underlying.error(message(event, fields), args(fields), cause)
  def child(fields: (String, Any)*): Log = Slf4jLog(underlying, base ++ fields)

  /**
   * The JSON layout keeps the message as the bare event name (fields are separate JSON keys).
   * The pretty layout (LOG_FORMAT=pretty) has no JSON keys, so the fields go into the line.
   */
  private def message(event: String, fields: Seq[(String, Any)]): String =
    if Slf4jLog.pretty then s"$event ${args(fields)}" else event

  private def args(fields: Seq[(String, Any)]): AnyRef =
    val all = (base ++ fields).collect { case (k, v) if v != None => k -> toJava(v) }
    StructuredArguments.entries(all.toMap.asJava)

  private def toJava(v: Any): Any = v match
    case Some(x)       => toJava(x)
    case m: Map[?, ?]  => m.map((k, x) => k.toString -> toJava(x)).asJava
    case s: Iterable[?] => s.map(toJava).toSeq.asJava
    case e: scala.reflect.Enum => e.toString
    case other         => other

private object Slf4jLog:
  val pretty: Boolean = sys.env.get("LOG_FORMAT").orElse(sys.props.get("LOG_FORMAT")).exists(_.equalsIgnoreCase("pretty"))
