package scheduler.support

import java.time.{Instant, OffsetDateTime}
import java.util.UUID

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

import anorm.*
import play.api.{Application, ApplicationLoader, Environment, Play}
import play.api.libs.json.*
import play.api.mvc.Result
import play.api.test.{FakeRequest, Helpers}
import play.api.test.Helpers.{writeableOf_AnyContentAsEmpty, writeableOf_AnyContentAsJson}

import scheduler.AppComponents
import scheduler.Seed
import scheduler.shared.Sql

/** A mutable clock the app reads "now" from, so time-based rules can be tested. */
final class TestClock(var current: Instant):
  def set(iso: String): Unit = current = OffsetDateTime.parse(iso).toInstant

/** A response read back as status, headers and JSON. */
final case class Response(status: Int, headers: Map[String, String], body: String):
  lazy val json: JsValue = Json.parse(body)
  def header(name: String): Option[String] = headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v }

/**
 * The real application wired to a real database. Requests go through Play's request handler
 * in-process (filters, routing, body parsing; no network socket), and everything else
 * (validation, auth, SQL, constraints) is real.
 */
final class Harness(now: String, seed: Boolean = true):
  val clock = TestClock(OffsetDateTime.parse(now).toInstant)

  val components: AppComponents =
    val context = ApplicationLoader.Context.create(
      Environment.simple(),
      initialSettings = Map(
        "scheduler.database-url" -> TestDatabase.fresh(),
        "scheduler.seed-demo-data" -> seed.toString,
        "scheduler.slow-query-ms" -> "10000"
      )
    )
    AppComponents(context, () => clock.current)

  val app: Application = components.application
  Play.start(app)

  def close(): Unit = Play.stop(app)

  // ---- HTTP ----------------------------------------------------------------------------------

  def request(method: String, url: String, token: Option[String] = None, body: Option[JsValue] = None,
      headers: Seq[(String, String)] = Nil): Future[Response] =
    val base = FakeRequest(method, url).withHeaders(headers ++ token.map(t => "Authorization" -> s"Bearer $t")*)
    val result: Future[Result] = body match
      case Some(json) => Helpers.route(app, base.withJsonBody(json)).get
      case None       => Helpers.route(app, base).get
    given scala.concurrent.ExecutionContext = components.executionContext
    result.flatMap { r =>
      r.body.consumeData(using components.materializer).map { bytes =>
        Response(r.header.status, r.header.headers ++ r.body.contentType.map("Content-Type" -> _), bytes.utf8String)
      }
    }

  def api(method: String, url: String, token: String = null, body: JsValue = null, headers: Seq[(String, String)] = Nil): Response =
    Await.result(request(method, url, Option(token), Option(body), headers), 30.seconds)

  def login(email: String, password: String = Seed.DemoPassword): String =
    val res = api("POST", "/api/auth/login", body = Json.obj("email" -> email, "password" -> password))
    if res.status != 200 then throw IllegalStateException(s"login $email failed: ${res.body}")
    (res.json \ "token").as[String]

  // ---- direct SQL for fixtures and assertions ------------------------------------------------

  def sql[T](f: java.sql.Connection ?=> T): T = components.db.run(s => f(using Sql.connection(s)))

  def idOf(query: SimpleSql[Row]): String = sql(query.as(SqlParser.scalar[UUID].single).toString)
