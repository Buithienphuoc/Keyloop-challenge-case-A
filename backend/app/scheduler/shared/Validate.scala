package scheduler.shared

import java.time.{LocalDate, LocalTime, ZoneId}
import java.time.format.DateTimeParseException
import java.util.UUID

import scala.util.Try
import scala.util.matching.Regex

import play.api.libs.json.*

/**
 * Request validation at the edge of each use case: small `Reads` building blocks with
 * readable messages, and `body` / `query` to apply them. A failure becomes
 * 400 VALIDATION_FAILED with one entry per field, for example
 * `{"path": "password", "message": "Password must be at least 8 characters"}`.
 */
object Validate:

  def body[T](json: JsValue)(using r: Reads[T]): T = json.validate[T] match
    case JsSuccess(value, _) => value
    case JsError(errors)     => throw ValidationFailed(fieldErrors(errors))

  /** Query strings are validated with the same Reads as bodies (every value is a string). */
  def query[T](params: Map[String, Seq[String]])(using Reads[T]): T =
    body(JsObject(params.collect { case (k, v +: _) => k -> JsString(v) }))

  // ---- building blocks -------------------------------------------------------------------------

  def error(message: String): JsonValidationError = JsonValidationError(message)

  /** A string, trimmed, between `min` and `max` characters. */
  def text(min: Int = 1, max: Int = 10_000, tooShort: String = ""): Reads[String] =
    Reads.StringReads.map(_.trim)
      .filter(error(if tooShort.nonEmpty then tooShort else if min == 1 then "Required" else s"Must be at least $min characters"))(_.length >= min)
      .filter(error(s"Must be at most $max characters"))(_.length <= max)

  def matching(regex: Regex, message: String): Reads[String] =
    Reads.StringReads.map(_.trim).filter(error(message))(s => regex.matches(s))

  val uuid: Reads[String] =
    Reads.StringReads.filter(error("Must be a UUID"))(s => s.length == 36 && Try(UUID.fromString(s)).isSuccess).map(_.toLowerCase)

  val isoDate: Reads[LocalDate] = Reads.StringReads.flatMapResult { s =>
    if """\d{4}-\d{2}-\d{2}""".r.matches(s) then
      Try(LocalDate.parse(s)).fold(_ => JsError(error("Not a valid date")), JsSuccess(_))
    else JsError(error("Use the format YYYY-MM-DD"))
  }

  /** An ISO-8601 date-time that states its offset, e.g. 2026-11-04T09:00:00+07:00 or …Z. Kept as text. */
  val isoDateTimeWithOffset: Reads[String] = Reads.StringReads.filter(error("Use an ISO-8601 date-time with an offset")) { s =>
    try
      java.time.OffsetDateTime.parse(s)
      true
    catch case _: DateTimeParseException => false
  }

  val hhmm: Reads[LocalTime] =
    matching("""([01]\d|2[0-3]):[0-5]\d""".r, "Use HH:mm").map(LocalTime.parse)

  val email: Reads[String] =
    matching("""[^\s@]+@[^\s@]+\.[^\s@]+""".r, "Not a valid email address").filter(error("Must be at most 200 characters"))(_.length <= 200)

  val timeZone: Reads[ZoneId] =
    Reads.StringReads.flatMapResult(s => Try(ZoneId.of(s)).fold(_ => JsError(error("Unknown time zone")), JsSuccess(_)))

  def int(min: Int, max: Int): Reads[Int] =
    Reads.IntReads.filter(error(s"Must be at least $min"))(_ >= min).filter(error(s"Must be at most $max"))(_ <= max)

  /** One of the cases of a Scala enum, by name. */
  def oneOf[E](values: Array[E]): Reads[E] =
    val byName = values.map(v => v.toString -> v).toMap
    Reads.StringReads.flatMapResult { s =>
      byName.get(s).fold(JsError(error(s"Must be one of ${values.mkString(", ")}")))(JsSuccess(_))
    }

  def listOf[T](item: Reads[T], min: Int = 0, max: Int = 1000, tooFew: String = ""): Reads[List[T]] =
    Reads.list(using item)
      .filter(error(if tooFew.nonEmpty then tooFew else s"Give at least $min"))(_.length >= min)
      .filter(error(s"Give at most $max"))(_.length <= max)

  // ---- error formatting --------------------------------------------------------------------------

  private val Friendly = Map(
    "error.path.missing" -> "Required",
    "error.expected.jsstring" -> "Expected text",
    "error.expected.jsnumber" -> "Expected a number",
    "error.expected.int" -> "Expected a whole number",
    "error.expected.jsboolean" -> "Expected true or false",
    "error.expected.jsarray" -> "Expected a list",
    "error.expected.jsobject" -> "Expected an object",
  )

  private def fieldErrors(errors: collection.Seq[(JsPath, collection.Seq[JsonValidationError])]): Seq[FieldError] =
    errors.toSeq.flatMap { (path, errs) =>
      errs.map(e => FieldError(pathString(path), e.messages.lastOption.map(m => Friendly.getOrElse(m, m)).getOrElse("Invalid value")))
    }

  private def pathString(p: JsPath): String =
    p.path.map {
      case KeyPathNode(k)  => k
      case IdxPathNode(i)  => i.toString
      case other           => other.toString
    }.mkString(".")
