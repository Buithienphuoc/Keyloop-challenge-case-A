package scheduler.http

import scala.util.Using

import play.api.Environment
import play.api.libs.json.Json
import play.api.mvc.*

import scheduler.shared.Metrics
import Blocking.given

/** Operational endpoints: health probe, Prometheus metrics and the API documentation. */
final class OpsController(cc: ControllerComponents, env: Environment, databaseIsUp: () => Boolean) extends AbstractController(cc):
  private val SwaggerUi = "META-INF/resources/webjars/swagger-ui/5.17.14/"
  private val SafeAsset = "[A-Za-z0-9._-]+".r

  private lazy val spec: String =
    val in = env.resourceAsStream("openapi.yaml").getOrElse(throw IllegalStateException("openapi.yaml missing from the classpath"))
    Using.resource(in)(s => String(s.readAllBytes(), "UTF-8"))

  def health: Action[AnyContent] = Action.async {
    scala.concurrent.Future(databaseIsUp()).map {
      case true  => Ok(Json.obj("status" -> "ok", "db" -> "ok"))
      case false => ServiceUnavailable(Json.obj("status" -> "degraded", "db" -> "unreachable"))
    }
  }

  def metrics: Action[AnyContent] = Action(Ok(Metrics.scrape()).as(Metrics.contentType))

  def openapi: Action[AnyContent] = Action(Ok(spec).as("application/yaml"))

  /** Swagger UI from the webjar, pointed at our hand-written contract. */
  def docs: Action[AnyContent] = Action {
    Ok(s"""<!doctype html>
          |<html lang="en">
          |<head>
          |  <meta charset="utf-8" />
          |  <title>Service Scheduler API</title>
          |  <link rel="stylesheet" href="/docs/swagger-ui.css" />
          |</head>
          |<body>
          |  <div id="swagger-ui"></div>
          |  <script src="/docs/swagger-ui-bundle.js"></script>
          |  <script>window.ui = SwaggerUIBundle({ url: '/docs/openapi.yaml', dom_id: '#swagger-ui' });</script>
          |</body>
          |</html>""".stripMargin).as(HTML)
  }

  def swaggerAsset(file: String): Action[AnyContent] = Action {
    val asset = if SafeAsset.matches(file) then env.resourceAsStream(SwaggerUi + file) else None
    asset.fold(NotFound(ErrorMapper.body("ROUTE_NOT_FOUND", s"No route GET /docs/$file", "unknown"))) { in =>
      val bytes = Using.resource(in)(_.readAllBytes())
      val contentType = file.split('.').last match
        case "js"  => "application/javascript"
        case "css" => "text/css"
        case "png" => "image/png"
        case _     => "application/octet-stream"
      Ok(bytes).as(contentType).withHeaders(CACHE_CONTROL -> "public, max-age=86400")
    }
  }
