package scheduler.http

import java.util.UUID
import java.util.concurrent.Executors

import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.util.ByteString
import play.api.http.HttpErrorHandler
import play.api.libs.json.*
import play.api.libs.streams.Accumulator
import play.api.libs.typedmap.TypedKey
import play.api.mvc.*
import play.api.mvc.Results.*
import play.api.routing.Router

import scheduler.shared.{AppError, Log, Metrics, TransientStorageError, ValidationFailed}

/** Values the API filter attaches to every request. */
object RequestContext:
  val RequestId: TypedKey[String] = TypedKey("requestId")
  val Logger: TypedKey[Log] = TypedKey("log")

  def requestId(rh: RequestHeader): String = rh.attrs.get(RequestId).getOrElse("unknown")
  def log(rh: RequestHeader): Log = rh.attrs.get(Logger).getOrElse(Log())

/**
 * Where use cases run. JDBC and password hashing block, so every use case runs on its own
 * Java 21 virtual thread: blocking parks the virtual thread instead of an OS thread, so a
 * thousand waiting requests cost memory, not a thousand threads. Play's own threads never block.
 */
object Blocking:
  given ExecutionContext = ExecutionContext.fromExecutorService(Executors.newVirtualThreadPerTaskExecutor())

/** One error format for the whole API: `{"error": {"code", "message", "details"?, "requestId"}}`. */
object ErrorMapper:
  def body(code: String, message: String, requestId: String, details: Option[JsValue] = None): JsObject =
    Json.obj("error" -> (Json.obj("code" -> code, "message" -> message) ++
      details.fold(Json.obj())(d => Json.obj("details" -> d)) ++ Json.obj("requestId" -> requestId)))

  def toResult(rh: RequestHeader, e: Throwable): Result =
    val requestId = RequestContext.requestId(rh)
    e match
      case v: ValidationFailed =>
        BadRequest(body("VALIDATION_FAILED", v.getMessage, requestId,
          Some(Json.toJson(v.errors.map(f => Json.obj("path" -> f.path, "message" -> f.message))))))
      case a: AppError => Status(a.status)(body(a.code, a.message, requestId, a.details))
      case t: TransientStorageError =>
        // A deadlock outside the booking flow (which retries by itself): nothing was written.
        RequestContext.log(rh).warn("http.transient_storage_error", "cause" -> String.valueOf(t.getCause))
        ServiceUnavailable(body("TRY_AGAIN", "The system was busy. Please try again.", requestId)).withHeaders("Retry-After" -> "1")
      case other =>
        // Unexpected: log everything for us, reveal nothing to the caller except the ID to quote.
        RequestContext.log(rh).error("http.unhandled_error", other)
        InternalServerError(body("INTERNAL_ERROR", "Something went wrong on our side. Please try again.", requestId))

/**
 * Observability and error handling for every request, in one place:
 *  - correlation ID: reuse the caller's X-Request-Id (e.g. from a gateway) or mint one, return it
 *    in the response header and in every error body, and put it on every log line;
 *  - failures become the API's JSON error format;
 *  - one structured log line and one latency observation per request.
 */
final class ApiFilter(baseLog: Log)(using ec: ExecutionContext) extends EssentialFilter:
  private val ValidId = "[A-Za-z0-9._-]{8,64}".r

  def apply(next: EssentialAction): EssentialAction = EssentialAction { rh =>
    val started = System.nanoTime()
    val requestId = rh.headers.get("X-Request-Id").filter(ValidId.matches).getOrElse(UUID.randomUUID().toString)
    val tagged = rh.addAttr(RequestContext.RequestId, requestId).addAttr(RequestContext.Logger, baseLog.child("requestId" -> requestId))

    val accumulator: Accumulator[ByteString, Result] =
      try next(tagged)
      catch case e: Exception => Accumulator.done(ErrorMapper.toResult(tagged, e))
    accumulator
      .recover { case e: Exception => ErrorMapper.toResult(tagged, e) }
      .map { result =>
        record(tagged, result.header.status, (System.nanoTime() - started) / 1e6)
        result.withHeaders("X-Request-Id" -> requestId)
      }
  }

  private def record(rh: RequestHeader, status: Int, ms: Double): Unit =
    val route = rh.attrs.get(Router.Attrs.HandlerDef).map(h => ApiFilter.routePattern(h.path)).getOrElse("unmatched")
    Metrics.httpDuration.labelValues(rh.method, route, status.toString).observe(ms / 1000)
    if route != "/metrics" && route != "/health" then // keep probe noise out of the logs
      val log = RequestContext.log(rh)
      val fields = Seq("method" -> rh.method, "route" -> route, "url" -> rh.uri, "statusCode" -> status, "durationMs" -> math.round(ms))
      if status >= 500 then log.error("http.request", null, fields*)
      else if status >= 400 then log.warn("http.request", fields*)
      else log.info("http.request", fields*)

object ApiFilter:
  /** "/api/appointments/$id<[^/]+>" (Play's route syntax) → "/api/appointments/:id". */
  def routePattern(path: String): String = path.replaceAll("""\$(\w+)<[^>]+>""", ":$1")

/** Errors Play raises itself (no route, malformed JSON, body too large) in the same JSON format. */
final class JsonErrorHandler extends HttpErrorHandler:
  def onClientError(rh: RequestHeader, statusCode: Int, message: String): Future[Result] =
    val requestId = RequestContext.requestId(rh)
    val result = statusCode match
      case 404 => NotFound(ErrorMapper.body("ROUTE_NOT_FOUND", s"No route ${rh.method} ${rh.path}", requestId))
      case 400 => BadRequest(ErrorMapper.body("INVALID_REQUEST", if message.nonEmpty then message else "The request could not be read", requestId))
      case 413 => EntityTooLarge(ErrorMapper.body("PAYLOAD_TOO_LARGE", "The request body is too large", requestId))
      case s   => Status(s)(ErrorMapper.body("BAD_REQUEST", message, requestId))
    Future.successful(result.withHeaders("X-Request-Id" -> requestId))

  def onServerError(rh: RequestHeader, e: Throwable): Future[Result] =
    Future.successful(ErrorMapper.toResult(rh, e).withHeaders("X-Request-Id" -> RequestContext.requestId(rh)))
