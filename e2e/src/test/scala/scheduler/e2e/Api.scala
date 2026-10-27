package scheduler.e2e

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.LocalDate
import java.util.UUID

import scala.jdk.CollectionConverters.*

import org.openqa.selenium.json.Json

/**
 * A second "person" for the tests that need one: it calls the same REST API the web app uses,
 * through the same address (nginx or the dev server), so a test can make something happen
 * behind the browser's back, such as another customer taking a time.
 */
final class Api(token: String):
  import Api.*

  def get(path: String): Any = send(request(path).GET())

  def post(path: String, body: Map[String, Any], headers: (String, String)*): Any =
    val b = request(path).POST(HttpRequest.BodyPublishers.ofString(json.toJson(body.asJava)))
      .header("Content-Type", "application/json")
    headers.foreach((k, v) => b.header(k, v))
    send(b)

  private def send(b: HttpRequest.Builder): Any =
    val res = client.send(b.header("Authorization", s"Bearer $token").build(), HttpResponse.BodyHandlers.ofString())
    if res.statusCode() >= 300 then throw ApiFailure(res.statusCode(), res.body())
    if res.body().isEmpty then null else json.toType[Any](res.body(), classOf[Object])

object Api:
  final case class ApiFailure(status: Int, body: String) extends RuntimeException(s"HTTP $status: $body")

  // HTTP/1.1: the h2c upgrade Java tries by default hangs on some dev servers. No proxy: the app is local.
  private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
    .proxy(java.net.ProxySelector.of(null)).connectTimeout(Settings.timeout).build()
  private val json = Json()
  private def uri(path: String) = URI.create(Settings.baseUrl + path)
  private def request(path: String) = HttpRequest.newBuilder(uri(path)).timeout(Settings.timeout)

  def signIn(email: String, password: String = Settings.DemoPassword): Api =
    val req = request("/api/auth/login")
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(json.toJson(Map("email" -> email, "password" -> password).asJava)))
      .build()
    val res = client.send(req, HttpResponse.BodyHandlers.ofString())
    if res.statusCode() != 200 then throw ApiFailure(res.statusCode(), res.body())
    Api(field(json.toType[Any](res.body(), classOf[Object]), "token").toString)

  /** Reads a field of a JSON object, `a.b.c` style. */
  def field(value: Any, path: String): Any =
    path.split('.').foldLeft(value)((v, k) => v.asInstanceOf[java.util.Map[String, Any]].get(k))

  def list(value: Any): Seq[Any] = value.asInstanceOf[java.util.List[Any]].asScala.toSeq

  // ---- what the tests need -----------------------------------------------------------------------

  def dealershipId(api: Api, name: String): String =
    list(api.get("/api/dealerships")).find(d => field(d, "name") == name).map(field(_, "id").toString)
      .getOrElse(throw IllegalStateException(s"no dealership $name"))

  def packageId(api: Api, name: String): String =
    list(api.get("/api/service-packages")).find(p => field(p, "name") == name).map(field(_, "id").toString)
      .getOrElse(throw IllegalStateException(s"no package $name"))

  /** The slot starting at `hhmm` on `day`, as the availability endpoint reports it. */
  def slot(api: Api, dealershipId: String, packageId: String, day: LocalDate, hhmm: String): Any =
    list(field(api.get(s"/api/availability?dealershipId=$dealershipId&date=$day&packageId=$packageId"), "slots"))
      .find(s => field(s, "start").toString.contains(s"T$hhmm"))
      .getOrElse(throw IllegalStateException(s"no $hhmm slot on $day"))

  /**
   * Holds `start` for a new motorbike of this account and returns the held appointment's id.
   * A new one because a vehicle with a live appointment cannot be held again, and the demo
   * accounts' own bikes may already be booked.
   */
  def hold(api: Api, dealershipId: String, packageId: String, start: String): String =
    val n = System.nanoTime()
    val plate = f"59-Z9 ${n / 1000 % 1000}%03d.${n % 100}%02d"
    val vehicle = api.post("/api/me/vehicles", Map("vehicleType" -> "MOTORBIKE", "make" -> "Honda", "model" -> "Wave", "plate" -> plate))
    val held = api.post("/api/appointments/holds",
      Map("dealershipId" -> dealershipId, "vehicleId" -> field(vehicle, "id"), "start" -> start, "packageId" -> packageId),
      "Idempotency-Key" -> UUID.randomUUID().toString)
    field(held, "id").toString

  def cancel(api: Api, appointmentId: String): Unit =
    api.post(s"/api/appointments/$appointmentId/cancel", Map("reason" -> "E2E clean-up"))
