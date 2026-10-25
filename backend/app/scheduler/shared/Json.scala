package scheduler.shared

import java.time.{Instant, LocalDate, LocalTime, ZoneOffset, ZonedDateTime}
import java.time.format.DateTimeFormatter

import play.api.libs.json.*

/**
 * JSON conventions of the API, shared by every module:
 *  - absent optional values are written as `null` (the web app relies on the keys being there);
 *  - instants are UTC ISO-8601 with milliseconds (`2026-11-04T02:00:00.000Z`);
 *  - appointment and slot times carry the dealership's offset (`2026-11-04T09:00:00.000+07:00`);
 *  - local times are `HH:mm`; enums are written by name.
 */
object JsonFormats:
  given JsonConfiguration = JsonConfiguration(optionHandlers = OptionHandlers.WritesNull)

  private val WithOffset = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSxxx")
  private val Utc = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
  private val HourMinute = DateTimeFormatter.ofPattern("HH:mm")

  def offsetIso(z: ZonedDateTime): String = z.format(WithOffset)
  def utcIso(i: Instant): String = Utc.format(i)
  def hhmm(t: LocalTime): String = t.format(HourMinute)

  given Writes[ZonedDateTime] = Writes(z => JsString(offsetIso(z)))
  given Writes[Instant] = Writes(i => JsString(utcIso(i)))
  given Writes[LocalTime] = Writes(t => JsString(hhmm(t)))
  given Writes[LocalDate] = Writes(d => JsString(d.toString))
  given enumWrites[E <: scala.reflect.Enum]: Writes[E] = Writes(e => JsString(e.toString))
