package scheduler.integration

import java.util.concurrent.{CountDownLatch, Executors}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

import anorm.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.*

import scheduler.support.{Harness, Response}

/**
 * The features added in the second release, end to end on the seeded demo data: temporary
 * holds, the workshop controller's tools, opening hours, the package catalogue and parts.
 * The clock starts on Monday 2 Nov 2026, 07:00 in Ho Chi Minh City; the seed has filled
 * Tuesday 09:00 (2 of 3 stands), 10:00 (all) and 14:00 (1) at District 1.
 */
class FeaturesSpec extends AnyFunSpec with Matchers with BeforeAndAfterAll:
  private val Now = "2026-11-02T07:00:00+07:00"
  private val Tue = "2026-11-03"
  private val Wed = "2026-11-04"

  private lazy val h = Harness(Now)
  override def afterAll(): Unit = h.close()

  private lazy val d1 = h.idOf(SQL"SELECT id FROM dealerships WHERE code = 'HCM-D1'")
  private lazy val essential = h.idOf(SQL"SELECT id FROM service_packages WHERE code = 'MB-ESSENTIAL'")
  private def vehicle(plate: String) = h.idOf(SQL"SELECT id FROM vehicles WHERE plate = $plate")
  private lazy val airBlade = vehicle("59-X1 234.56")
  private lazy val exciter = vehicle("59-T2 678.90")
  private def technician(name: String) = h.idOf(SQL"SELECT id FROM technicians WHERE display_name = $name")
  private def bay(name: String) = h.idOf(SQL"SELECT b.id FROM service_bays b WHERE b.dealership_id = $d1::uuid AND b.name = $name")

  private lazy val customer = h.login("customer@demo.vn")
  private lazy val walkin = h.login("walkin1@demo.vn")
  private lazy val advisorD1 = h.login("advisor.d1@demo.vn")
  private lazy val adminD1 = h.login("admin.d1@demo.vn")
  private lazy val controller = h.login("controller.d1@demo.vn")
  private lazy val partsD1 = h.login("parts.d1@demo.vn")
  private lazy val partsTD = h.login("parts.td@demo.vn")
  private lazy val techHung = h.login("tech.hung@demo.vn")
  private lazy val sys = h.login("sysadmin@demo.vn")

  private def get(url: String, token: String = null) = h.api("GET", url, token)
  private def post(url: String, token: String = null, body: JsValue = null) = h.api("POST", url, token, body)

  extension (r: Response)
    def errorCode: String = (r.json \ "error" \ "code").as[String]
    def str(path: String): String = path.split('.').foldLeft(JsDefined(r.json): JsLookupResult)(_ \ _).as[String]

  private def capacity(date: String, time: String): Int =
    val slots = (get(s"/api/availability?dealershipId=$d1&date=$date&packageId=$essential", customer).json \ "slots").as[Seq[JsObject]]
    slots.find(s => (s \ "start").as[String].slice(11, 16) == time).map(s => (s \ "capacity").as[Int]).get

  private def hold(token: String, vehicleId: String, start: String, more: (String, Json.JsValueWrapper)*) =
    post("/api/appointments/holds", token,
      Json.obj(Seq[(String, Json.JsValueWrapper)]("dealershipId" -> d1, "vehicleId" -> vehicleId, "start" -> start) ++
        (if more.exists(_._1 == "consultationRequestId") then more else more :+ ("packageId" -> (essential: Json.JsValueWrapper)))*))

  describe("temporary holds (HELD → CONFIRMED)") {
    it("holds a slot for ten minutes, which takes it out of availability like a booking") {
      val before = capacity(Wed, "11:00")
      val res = hold(customer, airBlade, s"${Wed}T11:00:00+07:00")
      res.status shouldBe 201
      res.str("status") shouldBe "HELD"
      res.str("holdExpiresAt") shouldBe "2026-11-02T00:10:00.000Z" // 07:10 in Ho Chi Minh City
      capacity(Wed, "11:00") shouldBe before - 1
    }

    it("confirms a running hold, and confirming twice answers the same booking") {
      val id = hold(customer, exciter, s"${Wed}T13:00:00+07:00").str("id")
      h.clock.set("2026-11-02T07:09:00+07:00")
      val confirmed = post(s"/api/appointments/$id/confirm", customer)
      confirmed.status shouldBe 200
      confirmed.str("status") shouldBe "CONFIRMED"
      (confirmed.json \ "holdExpiresAt").toOption.flatMap(_.asOpt[String]) shouldBe None
      post(s"/api/appointments/$id/confirm", customer).str("status") shouldBe "CONFIRMED"
      post(s"/api/appointments/$id/confirm", walkin).status shouldBe 404 // not theirs
      h.clock.set(Now)
    }

    it("refuses to confirm a hold that ran out and frees the slot") {
      val before = capacity(Wed, "15:00")
      val id = hold(customer, airBlade, s"${Wed}T15:00:00+07:00").str("id")
      capacity(Wed, "15:00") shouldBe before - 1
      h.clock.set("2026-11-02T07:11:00+07:00")
      capacity(Wed, "15:00") shouldBe before // already ignored before anyone sweeps it
      val late = post(s"/api/appointments/$id/confirm", customer)
      late.status shouldBe 409
      late.errorCode shouldBe "HOLD_EXPIRED"
      h.sql(SQL"SELECT status FROM appointments WHERE id = $id::uuid".as(SqlParser.scalar[String].single)) shouldBe "EXPIRED"
      h.clock.set(Now)
    }

    it("lets someone else book the last place once a hold on it runs out, without waiting for the sweeper") {
      capacity(Tue, "09:00") shouldBe 1
      hold(customer, airBlade, s"${Tue}T09:00:00+07:00").status shouldBe 201
      capacity(Tue, "09:00") shouldBe 0
      val walkinBike = h.idOf(SQL"SELECT v.id FROM vehicles v JOIN users u ON u.id = v.owner_id WHERE u.email = 'walkin3@demo.vn'")
      def bookIt() = post("/api/appointments", walkin3Token, Json.obj("dealershipId" -> d1, "vehicleId" -> walkinBike,
        "packageId" -> essential, "start" -> s"${Tue}T09:00:00+07:00"))
      bookIt().errorCode shouldBe "SLOT_UNAVAILABLE"

      h.clock.set("2026-11-02T07:20:00+07:00")
      val booked = bookIt()
      booked.status shouldBe 201
      booked.str("status") shouldBe "CONFIRMED"
      h.clock.set(Now)
    }

    it("releases lapsed holds in the background and reopens a consultation whose hold lapsed") {
      val request = h.idOf(SQL"SELECT id FROM consultation_requests WHERE kind = 'MODIFICATION' AND status = 'APPROVED'")
      hold(customer, exciter, "2026-11-05T09:00:00+07:00", "consultationRequestId" -> request).status shouldBe 201
      get(s"/api/consultations/$request", customer).str("status") shouldBe "BOOKED"

      h.clock.set("2026-11-02T07:30:00+07:00")
      h.components.holdSweeper.sweep() should be >= 1
      get(s"/api/consultations/$request", customer).str("status") shouldBe "APPROVED"
      h.components.holdSweeper.sweep() shouldBe 0
      h.clock.set(Now)
    }

    it("lets the customer release a hold by cancelling it") {
      val id = hold(customer, airBlade, s"${Wed}T16:00:00+07:00").str("id")
      post(s"/api/appointments/$id/cancel", customer, Json.obj()).str("status") shouldBe "CANCELLED"
      post(s"/api/appointments/$id/confirm", customer).errorCode shouldBe "NOT_HELD"
    }
  }

  private lazy val walkin3Token = h.login("walkin3@demo.vn")

  describe("workshop controller") {
    it("sees how loaded each technician and bay is on a day") {
      val res = get(s"/api/staff/workload?date=$Tue", controller)
      res.status shouldBe 200
      val techs = (res.json \ "technicians").as[Seq[JsObject]]
      val minh = techs.find(t => (t \ "name").as[String] == "Nguyễn Văn Minh").get
      (minh \ "availableMinutes").as[Int] shouldBe 570 // 08:00–17:30
      (minh \ "bookedMinutes").as[Int] should be > 0
      val khoa = techs.find(t => (t \ "name").as[String] == "Phạm Đức Khoa").get
      (khoa \ "bookedMinutes").as[Int] shouldBe 0 // not a general-service technician
      (res.json \ "bays").as[Seq[JsObject]].map(b => (b \ "name").as[String]) should contain("Stand A")
      get(s"/api/staff/workload?date=$Tue", customer).status shouldBe 403
    }

    it("moves a job to a free, qualified technician and records who did it") {
      // At 10:00 on Tuesday all three stands are taken by Minh, Hùng and Lan, so pick a 14:00 job.
      val job = h.idOf(SQL"""SELECT a.id FROM appointments a WHERE a.dealership_id = $d1::uuid AND a.status = 'CONFIRMED'
                              AND lower(a.period) = '2026-11-03T14:00:00+07:00'::timestamptz LIMIT 1""")
      val current = h.sql(SQL"SELECT technician_id FROM appointments WHERE id = $job::uuid".as(SqlParser.scalar[java.util.UUID].single)).toString
      val target = Seq("Nguyễn Văn Minh", "Trần Quốc Hùng", "Lê Thị Lan").map(technician).find(_ != current).get

      post(s"/api/appointments/$job/reassign", advisorD1, Json.obj("technicianId" -> target)).status shouldBe 403
      post(s"/api/appointments/$job/reassign", controller, Json.obj()).status shouldBe 400
      post(s"/api/appointments/$job/reassign", controller, Json.obj("technicianId" -> technician("Phạm Đức Khoa")))
        .errorCode shouldBe "TECHNICIAN_NOT_QUALIFIED"
      post(s"/api/appointments/$job/reassign", controller, Json.obj("bayId" -> bay("Diagnostic 1")))
        .errorCode shouldBe "BAY_TYPE_MISMATCH"

      val moved = post(s"/api/appointments/$job/reassign", controller, Json.obj("technicianId" -> target, "reason" -> "Sick leave"))
      moved.status shouldBe 200
      moved.str("technician.id") shouldBe target
      h.sql(SQL"SELECT note FROM appointment_events WHERE appointment_id = $job::uuid ORDER BY id DESC LIMIT 1"
        .as(SqlParser.scalar[String].single)) should include("reason: Sick leave")
    }

    it("moves only the bay of a job whose technician has since been deactivated") {
      val (job, bayId, techId) = h.sql(SQL"""SELECT id::text, bay_id::text, technician_id::text FROM appointments
          WHERE dealership_id = $d1::uuid AND status = 'CONFIRMED' AND lower(period) = '2026-11-03T14:00:00+07:00'::timestamptz LIMIT 1"""
        .as((SqlParser.str("id") ~ SqlParser.str("bay_id") ~ SqlParser.str("technician_id")).map { case a ~ b ~ c => (a, b, c) }.single))
      val otherStand = Seq("Stand A", "Stand B", "Stand C").map(bay).find(_ != bayId).get
      def setActive(active: Boolean) =
        h.api("PATCH", s"/api/admin/technicians/$techId", adminD1, Json.obj("isActive" -> active)).status shouldBe 200

      setActive(false)
      try
        val moved = post(s"/api/appointments/$job/reassign", controller, Json.obj("bayId" -> otherStand, "reason" -> "Lift repair"))
        moved.status shouldBe 200
        moved.str("bay.id") shouldBe otherStand
        moved.str("technician.id") shouldBe techId
        // Naming the unchanged technician explicitly is fine too: only what changes is checked.
        post(s"/api/appointments/$job/reassign", controller, Json.obj("technicianId" -> techId, "bayId" -> bayId)).status shouldBe 200
      finally setActive(true)
    }

    it("never double-books: moving onto a technician who is busy then is refused") {
      val tenOClock = h.sql(SQL"""SELECT id::text, technician_id::text FROM appointments WHERE dealership_id = $d1::uuid
          AND status = 'CONFIRMED' AND lower(period) = '2026-11-03T10:00:00+07:00'::timestamptz ORDER BY id"""
        .as((SqlParser.str("id") ~ SqlParser.str("technician_id")).map { case a ~ b => (a, b) }.*))
      tenOClock should have size 3
      val ((job, _), (_, busyTech)) = (tenOClock(0), tenOClock(1))
      val res = post(s"/api/appointments/$job/reassign", controller, Json.obj("technicianId" -> busyTech))
      res.status shouldBe 409
      res.errorCode shouldBe "RESOURCE_BUSY"
    }
  }

  describe("opening hours set by the dealership admin") {
    it("applies new hours to availability at once and counts the bookings now outside them") {
      val res = h.api("PUT", "/api/admin/opening-hours", adminD1, Json.obj("openingTime" -> "08:00", "closingTime" -> "12:00",
        "openDays" -> Seq(1, 2, 3, 4, 5, 6), "slotMinutes" -> 30, "minLeadMinutes" -> 60, "maxDaysAhead" -> 60))
      res.status shouldBe 200
      (res.json \ "upcomingOutsideHours").as[Int] should be >= 1 // Tuesday 14:00 and the 13:00–16:00 tests above
      val slots = (get(s"/api/availability?dealershipId=$d1&date=$Wed&packageId=$essential", customer).json \ "slots").as[Seq[JsObject]]
      slots.map(s => (s \ "start").as[String].slice(11, 16)).last shouldBe "11:00"

      h.api("PUT", "/api/admin/opening-hours", adminD1, Json.obj("openingTime" -> "12:00", "closingTime" -> "08:00",
        "openDays" -> Seq(1), "slotMinutes" -> 30, "minLeadMinutes" -> 60, "maxDaysAhead" -> 60)).errorCode shouldBe "INVALID_HOURS"
      h.api("PUT", "/api/admin/opening-hours", advisorD1, Json.obj()).status shouldBe 403

      h.api("PUT", "/api/admin/opening-hours", adminD1, Json.obj("openingTime" -> "08:00", "closingTime" -> "17:30",
        "openDays" -> Seq(1, 2, 3, 4, 5, 6), "slotMinutes" -> 30, "minLeadMinutes" -> 60, "maxDaysAhead" -> 60)).status shouldBe 200
    }
  }

  describe("service package catalogue (platform manager)") {
    val wash = Json.obj("code" -> "mb-wash", "name" -> "Wash and polish", "description" -> "Foam wash, chain clean, polish.",
      "vehicleType" -> "MOTORBIKE", "durationMinutes" -> 30, "requiredSkill" -> "GENERAL_SERVICE", "bayType" -> "MOTORBIKE_STAND",
      "priceAmount" -> 80000, "checklist" -> Seq("Foam wash", "Chain clean", "Polish"))

    it("adds a package that customers can book straight away, and retires it again") {
      val created = post("/api/system/packages", sys, wash)
      created.status shouldBe 201
      created.str("code") shouldBe "MB-WASH"
      val id = created.str("id")
      def publicCodes = get("/api/service-packages?vehicleType=MOTORBIKE").json.as[Seq[JsObject]].map(p => (p \ "code").as[String])
      publicCodes should contain("MB-WASH")

      post("/api/system/packages", sys, wash).errorCode shouldBe "PACKAGE_CODE_TAKEN"
      post("/api/system/packages", sys, wash ++ Json.obj("code" -> "MB-LIFT", "bayType" -> "CAR_LIFT")).errorCode shouldBe "BAY_NOT_FOR_VEHICLE"
      post("/api/system/packages", sys, wash ++ Json.obj("code" -> "MB-ODD", "durationMinutes" -> 20)).status shouldBe 400
      post("/api/system/packages", adminD1, wash).status shouldBe 403

      val retired = h.api("PATCH", s"/api/system/packages/$id", sys, Json.obj("isActive" -> false, "priceAmount" -> 90000))
      (retired.json \ "isActive").as[Boolean] shouldBe false
      (retired.json \ "priceAmount").as[Int] shouldBe 90000
      publicCodes should not contain "MB-WASH"
    }
  }

  describe("replacement parts") {
    def part(sku: String) = get("/api/parts", partsD1).json.as[Seq[JsObject]].find(p => (p \ "sku").as[String] == sku).get
    def stock(sku: String) = (part(sku) \ "stockQty").as[Int]
    def setStatus(id: String, status: String, token: String = partsD1) = post(s"/api/part-orders/$id/status", token, Json.obj("status" -> status))
    lazy val job = h.idOf(SQL"SELECT id FROM appointments WHERE dealership_id = $d1::uuid AND status = 'CONFIRMED' LIMIT 1")

    it("lets a technician order parts for a job; parts staff order what is missing and issue it") {
      val pads = (part("BRAKE-PAD-F-MB") \ "id").as[String]
      val exhaust = (part("EXHAUST-SLIPON") \ "id").as[String]
      val padsBefore = stock("BRAKE-PAD-F-MB")

      val order = post("/api/part-orders", techHung, Json.obj("appointmentId" -> job, "note" -> "Customer wants the sport exhaust",
        "lines" -> Seq(Json.obj("partId" -> pads, "quantity" -> 2), Json.obj("partId" -> exhaust, "quantity" -> 1))))
      order.status shouldBe 201
      order.str("status") shouldBe "REQUESTED"
      order.str("appointment.id") shouldBe job
      (order.json \ "total").as[Int] shouldBe 2 * 180_000 + 3_200_000
      val id = order.str("id")

      setStatus(id, "ISSUED", techHung).status shouldBe 403
      val short = setStatus(id, "ISSUED")
      short.errorCode shouldBe "OUT_OF_STOCK"
      short.str("error.message") should include("Slip-on exhaust")
      setStatus(id, "RECEIVED").errorCode shouldBe "INVALID_STATUS_TRANSITION"

      setStatus(id, "ORDERED").str("status") shouldBe "ORDERED"
      setStatus(id, "RECEIVED").str("status") shouldBe "RECEIVED"
      stock("EXHAUST-SLIPON") shouldBe 1
      stock("BRAKE-PAD-F-MB") shouldBe padsBefore + 2
      setStatus(id, "ISSUED").str("status") shouldBe "ISSUED"
      stock("EXHAUST-SLIPON") shouldBe 0
      stock("BRAKE-PAD-F-MB") shouldBe padsBefore

      setStatus(id, "ISSUED", partsTD).status shouldBe 403 // another dealership
      get(s"/api/part-orders?appointmentId=$job", advisorD1).json.as[Seq[JsObject]].map(o => (o \ "id").as[String]) should contain(id)
    }

    it("never issues more than is on the shelf, even when orders race for the last units") {
      val chain = (part("CHAIN-KIT-428") \ "id").as[String]
      val onShelf = stock("CHAIN-KIT-428")
      val orders = (1 to onShelf + 4).map { _ =>
        post("/api/part-orders", advisorD1, Json.obj("lines" -> Seq(Json.obj("partId" -> chain, "quantity" -> 1)))).str("id")
      }
      val pool = Executors.newFixedThreadPool(orders.size)
      given ExecutionContext = ExecutionContext.fromExecutor(pool)
      val gate = CountDownLatch(1)
      val racing = orders.map(id => Future { gate.await(); setStatus(id, "ISSUED").status })
      gate.countDown()
      val results = Await.result(Future.sequence(racing), 60.seconds)
      pool.shutdown()
      results.count(_ == 200) shouldBe onShelf
      results.count(_ == 409) shouldBe 4
      stock("CHAIN-KIT-428") shouldBe 0
    }

    it("keeps the shelf to parts staff and admins") {
      post("/api/parts", techHung, Json.obj("sku" -> "X-1", "name" -> "Thing", "unitPrice" -> 1)).status shouldBe 403
      val added = post("/api/parts", partsD1, Json.obj("sku" -> "mirror-l", "name" -> "Left mirror", "unitPrice" -> 95000, "stockQty" -> 2))
      added.status shouldBe 201
      added.str("sku") shouldBe "MIRROR-L"
      post("/api/parts", partsD1, Json.obj("sku" -> "MIRROR-L", "name" -> "Again", "unitPrice" -> 1)).errorCode shouldBe "SKU_TAKEN"
      post(s"/api/parts/${added.str("id")}/stock", partsD1, Json.obj("delta" -> -3)).errorCode shouldBe "OUT_OF_STOCK"
      (post(s"/api/parts/${added.str("id")}/stock", partsD1, Json.obj("delta" -> 5)).json \ "stockQty").as[Int] shouldBe 7
    }
  }

  describe("tracing and scale-out signals") {
    it("continues the caller's trace (W3C traceparent) and says which instance answered") {
      val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
      val res = h.api("GET", "/api/me/appointments", customer, headers = Seq("traceparent" -> s"00-$traceId-00f067aa0ba902b7-01"))
      res.status shouldBe 200
      res.header("X-Trace-Id") shouldBe Some(traceId)
      res.header("X-Served-By").getOrElse("") should not be empty
      get("/health").header("X-Trace-Id").map(_.length) shouldBe Some(32) // a new trace when the caller sent none
    }
  }
