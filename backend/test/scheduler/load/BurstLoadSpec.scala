package scheduler.load

import java.net.{ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import java.util.concurrent.CompletableFuture

import scala.util.Using

import anorm.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.Json
import play.api.test.TestServer

import scheduler.support.{Fixtures, Harness, TestCustomer, Workshop}

/**
 * 1000 requests fired at the same instant over real HTTP (Play's Pekko HTTP server on a
 * socket; no in-process shortcut). Reports latency and checks that nothing fails and nothing
 * is double-booked. Run with `sbt loadTest`; `sbt test` skips it.
 */
class BurstLoadSpec extends AnyFunSpec with Matchers with BeforeAndAfterAll:
  private val N = 1000
  private val Now = "2026-11-02T07:00:00+07:00" // Monday 07:00
  private val Days = Seq("2026-11-03", "2026-11-04", "2026-11-05", "2026-11-06", "2026-11-07", "2026-11-09") // Tue–Sat, Mon
  private val Hours = Seq("08", "09", "10", "11", "12", "13", "14", "15", "16") // 60-minute package, so these never overlap

  private val enabled = sys.props.get("scheduler.loadTest").contains("true")
  private lazy val h = Harness(Now)
  private lazy val port = Using.resource(ServerSocket(0))(_.getLocalPort)
  private lazy val server = TestServer(port, h.app)
  private lazy val base = s"http://127.0.0.1:$port"
  private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(30)).build()

  override def beforeAll(): Unit = if enabled then server.start()
  override def afterAll(): Unit = if enabled then
    server.stop()
    h.close()

  private final case class Result(status: Int, ms: Double)

  /** Sends every request at once and waits for all answers. */
  private def burst(requests: Seq[HttpRequest]): (Seq[Result], Double) =
    val t0 = System.nanoTime()
    val futures = requests.map { r =>
      val s = System.nanoTime()
      client.sendAsync(r, HttpResponse.BodyHandlers.ofString()).thenApply(res => Result(res.statusCode, (System.nanoTime() - s) / 1e6))
    }
    CompletableFuture.allOf(futures*).join()
    (futures.map(_.join()), (System.nanoTime() - t0) / 1e6)

  private def report(name: String, run: (Seq[Result], Double)): Map[Int, Int] =
    val (results, wallMs) = run
    val ms = results.map(_.ms).sorted
    def pct(p: Int) = f"${ms(math.min(ms.size - 1, (p / 100.0 * ms.size).toInt))}%.0f"
    val byStatus = results.groupMapReduce(_.status)(_ => 1)(_ + _)
    info(f"$name: ${results.size} requests in $wallMs%.0f ms (${results.size / wallMs * 1000}%.0f req/s)")
    info(s"  latency p50 ${pct(50)} ms | p95 ${pct(95)} ms | p99 ${pct(99)} ms | max ${f"${ms.last}%.0f"} ms")
    info(s"  status ${byStatus.toSeq.sorted.map((s, n) => s"$s: $n").mkString(", ")}")
    byStatus

  private def book(c: TestCustomer, f: Workshop, start: String) =
    HttpRequest.newBuilder(URI.create(s"$base/api/appointments"))
      .header("Authorization", s"Bearer ${c.token}")
      .header("Content-Type", "application/json")
      .timeout(Duration.ofSeconds(60))
      .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(
        Json.obj("dealershipId" -> f.dealershipId, "vehicleId" -> c.vehicleId, "packageId" -> f.packageId, "start" -> start))))
      .build()

  describe(s"burst of $N simultaneous requests over HTTP") {
    it(s"$N customers book the SAME slot (worst-case contention, 4 bay+technician pairs)") {
      assume(enabled, "run with `sbt loadTest`")
      val f = Fixtures.workshop(h, "LOAD-HOT", 4, 4, N)
      val by = report("Same slot", burst(f.customers.map(book(_, f, s"${Days.head}T09:00:00+07:00"))))
      by.get(201) shouldBe Some(4) // exactly the capacity
      by.get(409) shouldBe Some(N - 4) // everyone else gets SLOT_UNAVAILABLE with alternatives
    }

    it(s"$N customers book slots spread over 6 days (20 pairs, enough room for everyone)") {
      assume(enabled, "run with `sbt loadTest`")
      val f = Fixtures.workshop(h, "LOAD-SPREAD", 20, 20, N)
      val slots = for d <- Days; hh <- Hours yield s"${d}T$hh:00:00+07:00" // 54 slots x 20 pairs
      val by = report("Spread slots", burst(f.customers.zipWithIndex.map((c, i) => book(c, f, slots(i % slots.size)))))
      by.get(201) shouldBe Some(N)
      h.sql {
        SQL"""SELECT count(*)::int FROM appointments a JOIN appointments b ON a.id < b.id
                AND a.period && b.period AND (a.bay_id = b.bay_id OR a.technician_id = b.technician_id)
              WHERE a.status = 'CONFIRMED' AND b.status = 'CONFIRMED'""".as(SqlParser.scalar[Int].single)
      } shouldBe 0
    }

    it(s"$N simultaneous availability reads") {
      assume(enabled, "run with `sbt loadTest`")
      val d = h.idOf(SQL"SELECT id FROM dealerships WHERE code = 'LOAD-SPREAD'")
      val p = h.idOf(SQL"SELECT id FROM service_packages WHERE code = 'MB-ESSENTIAL'")
      val c = Fixtures.workshop(h, "LOAD-READ", 1, 1, 1).customers.head
      val requests = (0 until N).map { i =>
        HttpRequest.newBuilder(URI.create(s"$base/api/availability?dealershipId=$d&packageId=$p&date=${Days(i % Days.size)}"))
          .header("Authorization", s"Bearer ${c.token}").timeout(Duration.ofSeconds(60)).GET().build()
      }
      report("Availability reads", burst(requests)).get(200) shouldBe Some(N)
    }
  }

