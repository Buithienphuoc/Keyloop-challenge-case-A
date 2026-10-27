package scheduler.integration

import java.sql.SQLException

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

import anorm.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.*

import scheduler.shared.PgErrors
import scheduler.support.{Fixtures, Harness, Response, TestCustomer, Workshop}

/**
 * The scenario the whole design is built around: many customers press "Book" for the
 * same slot at the same moment. Requests run truly concurrently against PostgreSQL
 * (separate pooled connections, overlapping transactions).
 */
class BookingConcurrencySpec extends AnyFunSpec with Matchers with BeforeAndAfterAll:
  private val Now = "2026-11-02T07:00:00+07:00" // Monday morning
  private val SlotStart = "2026-11-03T09:00:00+07:00" // Tuesday 09:00
  private val Racers = 50

  private lazy val h = Harness(Now) // seed provides skills, bay types and packages
  override def afterAll(): Unit = h.close()

  private def fixture(code: String, bays: Int, technicians: Int, customers: Int) =
    Fixtures.workshop(h, code, bays, technicians, customers)

  private def bookingBody(f: Workshop, c: TestCustomer) =
    Json.obj("dealershipId" -> f.dealershipId, "vehicleId" -> c.vehicleId, "packageId" -> f.packageId, "start" -> SlotStart)

  /** Fires all requests at once and waits for every answer. */
  private def race(f: Workshop, sameCustomerKey: Option[String] = None): Seq[Response] =
    given ExecutionContext = h.components.executionContext
    val all = (0 until Racers).map { i =>
      val c = if sameCustomerKey.isDefined then f.customers.head else f.customers(i)
      h.request("POST", "/api/appointments", Some(c.token), Some(bookingBody(f, c)), sameCustomerKey.map("Idempotency-Key" -> _).toSeq)
    }
    Await.result(Future.sequence(all), 2.minutes)

  private def activeAt(dealershipId: String): Int = h.sql {
    SQL"""SELECT count(*)::int FROM appointments
          WHERE dealership_id = $dealershipId::uuid AND status = 'CONFIRMED' AND period @> $SlotStart::timestamptz"""
      .as(SqlParser.scalar[Int].single)
  }

  describe("concurrent booking of the same slot") {
    it(s"$Racers customers, 1 bay and 1 technician: exactly one booking wins") {
      val f = fixture("ONE", 1, 1, Racers)
      val responses = race(f)
      val rejected = responses.filter(_.status == 409)
      responses.count(_.status == 201) shouldBe 1
      rejected should have size (Racers - 1)
      all(rejected.map(r => (r.json \ "error" \ "code").as[String])) shouldBe "SLOT_UNAVAILABLE"
      activeAt(f.dealershipId) shouldBe 1
    }

    it("3 bays but only 2 technicians: exactly two win (the technician is the bottleneck)") {
      val f = fixture("TWO", 3, 2, Racers)
      val responses = race(f)
      responses.count(_.status == 201) shouldBe 2
      responses.count(_.status == 409) shouldBe (Racers - 2)
      activeAt(f.dealershipId) shouldBe 2
    }

    it("the same request sent 50 times with one Idempotency-Key books once and returns the same appointment") {
      val f = fixture("IDEM", 2, 2, 1)
      val responses = race(f, Some("double-tap-key-0001"))
      all(responses.map(_.status)) should (equal(201) or equal(200))
      responses.count(_.status == 201) shouldBe 1
      responses.map(r => (r.json \ "id").as[String]).toSet should have size 1
      activeAt(f.dealershipId) shouldBe 1
    }

    // Found by the 1000-request load test: one same-key race in four ended with some 500s,
    // because PostgreSQL broke a deadlock between the unique-key and exclusion checks (40P01).
    // Ten rounds make that failure near-certain if the retry is ever removed.
    it("same-key races never surface a deadlock as a 500 (10 rounds)") {
      for round <- 0 until 10 do
        val f = fixture(s"IDEM-R$round", 2, 2, 1)
        val responses = race(f, Some(s"round-key-$round-000000"))
        responses.map(_.status).filterNot(s => s == 200 || s == 201) shouldBe empty
        responses.map(r => (r.json \ "id").as[String]).toSet should have size 1
    }
  }

  /**
   * One customer, one vehicle, many presses: a double or triple tap on a slow page, a
   * second tab, a retry after a network blip. Whatever the keys, the vehicle ends up with
   * exactly one live appointment, because the vehicle EXCLUDE constraint covers holds too.
   */
  describe("one customer pressing Book many times before the first press is saved") {
    // Presses for one vehicle queue on a lock instead of deadlocking inside the vehicle
    // constraint; before that lock, 50 presses could take PostgreSQL minutes to untangle.
    def fireAll(n: Int)(send: Int => Future[Response]): Seq[Response] =
      given ExecutionContext = h.components.executionContext
      val started = System.nanoTime()
      val responses = Await.result(Future.sequence((0 until n).map(send)), 2.minutes)
      (System.nanoTime() - started).nanos.toSeconds should be < 15L
      responses

    def liveFor(vehicleId: String): Int = h.sql {
      SQL"""SELECT count(*)::int FROM appointments
            WHERE vehicle_id = $vehicleId::uuid AND status IN ('HELD', 'CONFIRMED', 'IN_PROGRESS')"""
        .as(SqlParser.scalar[Int].single)
    }

    def code(r: Response) = (r.json \ "error" \ "code").asOpt[String]

    // Capacity 3, so only the vehicle rule can stop the extra presses.
    it("bookings with a new Idempotency-Key per press: one is booked, the rest are refused") {
      val f = fixture("TAP-KEYS", 3, 3, 1)
      val c = f.customers.head
      val responses = fireAll(Racers)(i =>
        h.request("POST", "/api/appointments", Some(c.token), Some(bookingBody(f, c)), Seq("Idempotency-Key" -> s"tap-key-$i-0000")))
      responses.count(_.status == 201) shouldBe 1
      responses.filter(_.status == 409).flatMap(code).toSet shouldBe Set("VEHICLE_HAS_APPOINTMENT")
      responses.count(_.status == 409) shouldBe (Racers - 1)
      liveFor(c.vehicleId) shouldBe 1
    }

    it("bookings with no Idempotency-Key at all: still one") {
      val f = fixture("TAP-NOKEY", 3, 3, 1)
      val c = f.customers.head
      val responses = fireAll(Racers)(_ => h.request("POST", "/api/appointments", Some(c.token), Some(bookingBody(f, c))))
      responses.count(_.status == 201) shouldBe 1
      responses.map(_.status).filterNot(s => s == 201 || s == 409) shouldBe empty
      liveFor(c.vehicleId) shouldBe 1
    }

    it("holds from several tabs, each with its own key: one hold, the rest are refused") {
      val f = fixture("TAP-HOLD", 3, 3, 1)
      val c = f.customers.head
      val responses = fireAll(Racers)(i =>
        h.request("POST", "/api/appointments/holds", Some(c.token), Some(bookingBody(f, c)), Seq("Idempotency-Key" -> s"hold-key-$i-000")))
      responses.count(_.status == 201) shouldBe 1
      responses.filter(_.status == 409).flatMap(code).toSet shouldBe Set("VEHICLE_HAS_APPOINTMENT")
      responses.count(_.status == 409) shouldBe (Racers - 1)
      liveFor(c.vehicleId) shouldBe 1
    }

    it("a hold and a direct booking at the same moment: one of them") {
      val f = fixture("TAP-MIX", 3, 3, 1)
      val c = f.customers.head
      val responses = fireAll(Racers)(i =>
        h.request("POST", if i % 2 == 0 then "/api/appointments/holds" else "/api/appointments", Some(c.token), Some(bookingBody(f, c))))
      responses.count(_.status == 201) shouldBe 1
      liveFor(c.vehicleId) shouldBe 1
    }

    it("overlapping start times from two tabs (09:00 and 09:30): one of them") {
      val f = fixture("TAP-SHIFT", 3, 3, 1)
      val c = f.customers.head
      val responses = fireAll(Racers)(i =>
        val start = if i % 2 == 0 then SlotStart else "2026-11-03T09:30:00+07:00"
        h.request("POST", "/api/appointments", Some(c.token), Some(bookingBody(f, c) ++ Json.obj("start" -> start))))
      responses.count(_.status == 201) shouldBe 1
      liveFor(c.vehicleId) shouldBe 1
    }

    it("Confirm pressed 50 times on one hold: one confirmation, one audit row, the same appointment every time") {
      val f = fixture("TAP-CONFIRM", 2, 2, 1)
      val c = f.customers.head
      val held = h.api("POST", "/api/appointments/holds", c.token, bookingBody(f, c))
      held.status shouldBe 201
      val id = (held.json \ "id").as[String]
      val responses = fireAll(Racers)(_ => h.request("POST", s"/api/appointments/$id/confirm", Some(c.token)))
      all(responses.map(_.status)) shouldBe 200
      all(responses.map(r => (r.json \ "status").as[String])) shouldBe "CONFIRMED"
      responses.map(r => (r.json \ "id").as[String]).toSet shouldBe Set(id)
      liveFor(c.vehicleId) shouldBe 1
      h.sql {
        SQL"""SELECT count(*)::int FROM appointment_events
              WHERE appointment_id = $id::uuid AND from_status = 'HELD' AND to_status = 'CONFIRMED'"""
          .as(SqlParser.scalar[Int].single)
      } shouldBe 1
    }

    it("two vehicles of the same customer may still be booked side by side") {
      val f = fixture("TAP-TWO", 3, 3, 1)
      val c = f.customers.head
      val second = h.idOf(SQL"""
        INSERT INTO vehicles (owner_id, vehicle_type, make, model, plate)
        SELECT owner_id, 'MOTORBIKE', 'Honda', 'Vision', 'TAP-TWO-B' FROM vehicles WHERE id = ${c.vehicleId}::uuid RETURNING id""")
      val responses = fireAll(2)(i =>
        val body = if i == 0 then bookingBody(f, c) else bookingBody(f, c) ++ Json.obj("vehicleId" -> second)
        h.request("POST", "/api/appointments", Some(c.token), Some(body)))
      responses.map(_.status) shouldBe Seq(201, 201)
    }
  }

  /**
   * Each EXCLUDE constraint is checked on its own, by writing straight to the table.
   * (Mutation testing showed why: in the race tests every request picks the same first
   * bay, so the bay constraint alone was enough to make them pass. Without these direct
   * checks, deleting the technician constraint went unnoticed.)
   */
  describe("the database itself rejects overlaps, even writes that bypass the application") {
    lazy val f = fixture("RAW", 2, 2, 2)
    lazy val booked =
      val ok = h.api("POST", "/api/appointments", f.customers.head.token, bookingBody(f, f.customers.head))
      ok.status shouldBe 201
      (( ok.json \ "bay" \ "id").as[String], (ok.json \ "technician" \ "id").as[String], f.customers.head.vehicleId)
    lazy val other = (
      h.idOf(SQL"SELECT id FROM service_bays WHERE dealership_id = ${f.dealershipId}::uuid AND id <> ${booked._1}::uuid"),
      h.idOf(SQL"SELECT id FROM technicians WHERE dealership_id = ${f.dealershipId}::uuid AND id <> ${booked._2}::uuid"),
      f.customers(1).vehicleId
    )

    var n = 0
    def rawInsert(bayId: String, technicianId: String, vehicleId: String,
        range: String = "['2026-11-03 09:30+07','2026-11-03 10:30+07')"): Unit =
      n += 1
      h.sql {
        SQL"""
          INSERT INTO appointments (reference, dealership_id, customer_id, vehicle_id, service_package_id, bay_id, technician_id,
            period, status, created_by)
          VALUES (${s"SV-RAW$n"}, ${f.dealershipId}::uuid, (SELECT owner_id FROM vehicles WHERE id = $vehicleId::uuid), $vehicleId::uuid,
            ${f.packageId}::uuid, $bayId::uuid, $technicianId::uuid, $range::tstzrange, 'CONFIRMED',
            (SELECT owner_id FROM vehicles WHERE id = $vehicleId::uuid))""".execute()
      }

    def rejectedBy(constraint: String)(write: => Unit): Unit =
      val e = the[SQLException] thrownBy write
      PgErrors.sqlState(e) shouldBe Some("23P01")
      PgErrors.constraint(e) shouldBe Some(constraint)

    it("same bay, different technician and vehicle") {
      rejectedBy("appointments_no_bay_overlap")(rawInsert(booked._1, other._2, other._3))
    }

    it("same technician, different bay and vehicle") {
      rejectedBy("appointments_no_technician_overlap")(rawInsert(other._1, booked._2, other._3))
    }

    it("same vehicle, different bay and technician") {
      rejectedBy("appointments_no_vehicle_overlap")(rawInsert(other._1, other._2, booked._3))
    }

    it("back-to-back on the same bay and technician is allowed") {
      noException should be thrownBy rawInsert(booked._1, booked._2, other._3, "['2026-11-03 10:00+07','2026-11-03 11:00+07')")
    }
  }
