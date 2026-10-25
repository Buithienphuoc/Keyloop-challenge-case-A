package scheduler.http

import scala.concurrent.{ExecutionContext, Future}

import play.api.libs.json.*
import play.api.mvc.*

import scheduler.modules.identity.{AuthContext, Authenticator, Role}
import scheduler.shared.{AppError, Log, Validate}
import Blocking.given

/** A request that passed authentication, with a logger that names the caller. */
final class AuthRequest[A](val auth: AuthContext, val log: Log, request: Request[A]) extends WrappedRequest[A](request)

/**
 * Authenticates the caller from `Authorization: Bearer <token>` and, when roles are given,
 * authorises them. On success the request logger is enriched with userId / role /
 * dealershipId, so every later log line for this request can be traced back to who made it.
 */
final class Guard(authenticator: Authenticator, parser: BodyParser[AnyContent]):
  def apply(roles: Role*): ActionBuilder[AuthRequest, AnyContent] = new ActionBuilder[AuthRequest, AnyContent]:
    val parser: BodyParser[AnyContent] = Guard.this.parser
    protected val executionContext: ExecutionContext = summon[ExecutionContext]

    def invokeBlock[A](request: Request[A], block: AuthRequest[A] => Future[Result]): Future[Result] =
      Future {
        val token = Guard.bearerToken(request).getOrElse(throw AppError.unauthorized())
        val auth = authenticator.authenticate(token).getOrElse(throw AppError.unauthorized("Your session has expired. Please sign in again."))
        val log = RequestContext.log(request).child("userId" -> auth.userId, "role" -> auth.role, "dealershipId" -> auth.dealershipId)
        if roles.nonEmpty && !roles.contains(auth.role) then
          log.warn("auth.forbidden", "requiredRoles" -> roles.map(_.toString))
          throw AppError.forbidden()
        AuthRequest(auth, log, request)
      }.flatMap(block)

object Guard:
  def bearerToken(rh: RequestHeader): Option[String] =
    rh.headers.get("Authorization").collect { case h if h.startsWith("Bearer ") => h.drop(7).trim }.filter(_.nonEmpty)

/**
 * Base for the HTTP adapters (controllers). They only parse input, call one use case on a
 * virtual thread and choose the status code; the rules live in the application services.
 */
abstract class ApiController(cc: ControllerComponents) extends AbstractController(cc):

  /** Runs a (blocking) use case on a virtual thread and answers with its JSON. */
  protected def json[T: Writes](status: Status = Ok)(useCase: => T): Future[Result] =
    Future(useCase).map(v => status(Json.toJson(v)))

  protected def noContent(useCase: => Unit): Future[Result] = Future(useCase).map(_ => NoContent)

  /** The JSON body (an empty body counts as `{}`), validated into `T`. */
  protected def body[T: Reads](request: Request[AnyContent]): T =
    Validate.body[T](request.body.asJson.getOrElse(Json.obj()))

  protected def query[T: Reads](request: RequestHeader): T = Validate.query[T](request.queryString)

  protected def uuid(value: String, name: String = "id"): String =
    Validate.body(Json.obj(name -> value))(using (__ \ name).read(Validate.uuid))

  protected def requestLog(request: RequestHeader): Log = RequestContext.log(request)
