package scheduler.shared

import play.api.libs.json.JsObject

/**
 * Errors that are part of the API contract. Anything else that is thrown is
 * treated as a bug: logged with its stack and returned as a generic 500.
 */
final case class AppError(status: Int, code: String, message: String, details: Option[JsObject] = None)
    extends RuntimeException(message)

object AppError:
  def badRequest(code: String, message: String): AppError = AppError(400, code, message)
  def unauthorized(message: String = "Authentication required"): AppError = AppError(401, "UNAUTHENTICATED", message)
  def forbidden(message: String = "You do not have permission to do this"): AppError = AppError(403, "FORBIDDEN", message)
  def notFound(what: String): AppError = AppError(404, "NOT_FOUND", s"$what not found")
  def conflict(code: String, message: String, details: Option[JsObject] = None): AppError = AppError(409, code, message, details)
  def unprocessable(code: String, message: String): AppError = AppError(422, code, message)

/** One problem in a request body or query string, with the field it is about. */
final case class FieldError(path: String, message: String)

/** The request did not match its schema (400 VALIDATION_FAILED). */
final case class ValidationFailed(errors: Seq[FieldError])
    extends RuntimeException(errors.headOption.map(_.message).getOrElse("Invalid request"))

/**
 * The storage layer gave up on an operation that is safe to try again (a deadlock or a
 * serialisation failure; nothing was written). Adapters raise it so application code can
 * retry without knowing which database produced it.
 */
final class TransientStorageError(cause: Throwable) extends RuntimeException("Transient storage conflict", cause)
