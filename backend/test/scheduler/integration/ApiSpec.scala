package scheduler.integration

import anorm.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.*

import scheduler.support.{Harness, Response}

/**
 * End-to-end tests of the HTTP API on top of the seeded demo data, with the clock pinned
 * to Monday 2 Nov 2026, 07:00 in Ho Chi Minh City. The seed has already filled Tuesday
 * 09:00 (2 of 3 stands) and 10:00 (all stands) at District 1.
 */
class ApiSpec extends AnyFunSpec with Matchers with BeforeAndAfterAll:
  private val Now = "2026-11-02T07:00:00+07:00"
  private val Tue = "2026-11-03"
  private val Wed = "2026-11-04"

  private lazy val h = Harness(Now)
  override def afterAll(): Unit = h.close()

  private def vehicle(plate: String) = h.idOf(SQL"SELECT id FROM vehicles WHERE plate = $plate")
  private lazy val d1 = h.idOf(SQL"SELECT id FROM dealerships WHERE code = 'HCM-D1'")
  private lazy val td = h.idOf(SQL"SELECT id FROM dealerships WHERE code = 'HCM-TD'")
  private lazy val essential = h.idOf(SQL"SELECT id FROM service_packages WHERE code = 'MB-ESSENTIAL'")
  private lazy val carMinor = h.idOf(SQL"SELECT id FROM service_packages WHERE code = 'CAR-MINOR'")
  private lazy val airBlade = vehicle("59-X1 234.56")
  private lazy val exciter = vehicle("59-T2 678.90")
  private lazy val vios = vehicle("51H-123.45")

  private lazy val customer = h.login("customer@demo.vn")
  private lazy val advisorD1 = h.login("advisor.d1@demo.vn")
  private lazy val advisorTD = h.login("advisor.td@demo.vn")
  private lazy val adminD1 = h.login("admin.d1@demo.vn")
  private lazy val techHung = h.login("tech.hung@demo.vn")
  private lazy val sys = h.login("sysadmin@demo.vn")

  private def get(url: String, token: String = null) = h.api("GET", url, token)
  private def post(url: String, token: String = null, body: JsValue = null) = h.api("POST", url, token, body)
  private def book(token: String, fields: (String, Json.JsValueWrapper)*) = post("/api/appointments", token, Json.obj(fields*))

  extension (r: Response)
    def errorCode: String = (r.json \ "error" \ "code").as[String]
    def str(path: String): String = path.split('.').foldLeft(JsDefined(r.json): JsLookupResult)(_ \ _).as[String]
    def ids: Seq[String] = r.json.as[Seq[JsObject]].map(o => (o \ "id").as[String])

  private def slotsByTime(r: Response): Map[String, JsObject] =
    (r.json \ "slots").as[Seq[JsObject]].map(s => (s \ "start").as[String].slice(11, 16) -> s).toMap

  describe("authentication") {
    it("registers a customer, signs them in, and logout really revokes the token") {
      val reg = post("/api/auth/register", body = Json.obj("email" -> "new@demo.vn", "password" -> "Secret123", "fullName" -> "New Rider"))
      reg.status shouldBe 201
      val token = reg.str("token")
      reg.str("user.role") shouldBe "CUSTOMER"

      get("/api/auth/me", token).status shouldBe 200
      post("/api/auth/logout", token).status shouldBe 204
      get("/api/auth/me", token).status shouldBe 401
    }

    it("gives the same answer for a wrong password and an unknown email") {
      val wrongPw = post("/api/auth/login", body = Json.obj("email" -> "customer@demo.vn", "password" -> "nope-1234"))
      val unknown = post("/api/auth/login", body = Json.obj("email" -> "ghost@demo.vn", "password" -> "nope-1234"))
      wrongPw.status shouldBe 401
      unknown.status shouldBe 401
      wrongPw.str("error.message") shouldBe unknown.str("error.message")
    }

    it("rejects a duplicate email and a weak password") {
      val dup = post("/api/auth/register", body = Json.obj("email" -> "CUSTOMER@demo.vn", "password" -> "Secret123", "fullName" -> "Copy Cat"))
      dup.status shouldBe 409
      dup.errorCode shouldBe "EMAIL_TAKEN"
      val weak = post("/api/auth/register", body = Json.obj("email" -> "weak@demo.vn", "password" -> "short", "fullName" -> "Weak"))
      weak.status shouldBe 400
      weak.errorCode shouldBe "VALIDATION_FAILED"
    }
  }

  describe("role-based access") {
    it("keeps each role to its own endpoints") {
      get("/api/me/vehicles").status shouldBe 401
      get("/api/admin/bays", customer).status shouldBe 403
      get("/api/system/dealerships", adminD1).status shouldBe 403
      get("/api/technician/jobs?date=2026-11-03", advisorD1).status shouldBe 403
      book(advisorD1, "dealershipId" -> d1, "vehicleId" -> airBlade, "packageId" -> essential, "start" -> s"${Wed}T09:00:00+07:00")
        .status shouldBe 403
    }
  }

  describe("booking a maintenance package (the main customer journey)") {
    var appointmentId = ""

    it("shows real availability: Tuesday 10:00 is full, 09:00 has one place left") {
      val res = get(s"/api/availability?dealershipId=$d1&date=$Tue&packageId=$essential", customer)
      res.status shouldBe 200
      val slots = slotsByTime(res)
      (slots("09:00") \ "available").as[Boolean] shouldBe true
      (slots("09:00") \ "capacity").as[Int] shouldBe 1
      (slots("10:00") \ "available").as[Boolean] shouldBe false
      (slots("10:00") \ "reason").as[String] shouldBe "FULLY_BOOKED"
      (slots("08:00") \ "capacity").as[Int] shouldBe 3
    }

    it("books a slot, chooses the least specialised free technician, and shows it everywhere it should") {
      val res = book(customer, "dealershipId" -> d1, "vehicleId" -> airBlade, "packageId" -> essential,
        "start" -> s"${Wed}T09:00:00+07:00", "customerNote" -> "Front brake squeaks a little")
      res.status shouldBe 201
      appointmentId = res.str("id")
      res.str("reference") should fullyMatch regex "SV-[A-Z0-9]{6}"
      res.str("status") shouldBe "CONFIRMED"
      res.str("start") shouldBe "2026-11-04T09:00:00.000+07:00"
      res.str("end") shouldBe "2026-11-04T10:00:00.000+07:00"
      res.str("technician.name") shouldBe "Nguyễn Văn Minh" // general servicing only: keeps specialists free
      res.str("bay.name") shouldBe "Stand A"

      get("/api/me/appointments", customer).ids should contain(appointmentId)
      get(s"/api/staff/appointments?date=$Wed", advisorD1).ids should contain(appointmentId)
    }

    it("never lets another dealership see the appointment") {
      get(s"/api/appointments/$appointmentId", advisorTD).status shouldBe 403
      val nosy = post("/api/auth/register", body = Json.obj("email" -> "nosy@demo.vn", "password" -> "Secret123", "fullName" -> "Nosy")).str("token")
      get(s"/api/appointments/$appointmentId", nosy).status shouldBe 404
    }

    it("refuses a second booking for a vehicle that already has one, and names the first") {
      val res = book(customer, "dealershipId" -> d1, "vehicleId" -> airBlade, "packageId" -> essential, "start" -> s"${Wed}T09:30:00+07:00")
      res.status shouldBe 409
      res.errorCode shouldBe "VEHICLE_HAS_APPOINTMENT"
      (res.json \ "error" \ "details" \ "status").as[String] shouldBe "CONFIRMED"
      (res.json \ "error" \ "details" \ "start").as[String] shouldBe s"${Wed}T09:00:00.000+07:00"
    }

    it("answers a taken slot with the nearest free alternatives") {
      val res = book(customer, "dealershipId" -> d1, "vehicleId" -> exciter, "packageId" -> essential, "start" -> s"${Tue}T10:00:00+07:00")
      res.status shouldBe 409
      res.errorCode shouldBe "SLOT_UNAVAILABLE"
      val alternatives = (res.json \ "error" \ "details" \ "alternatives").as[Seq[JsObject]]
      alternatives should have size 3
      all(alternatives.map(a => (a \ "available").as[Boolean])) shouldBe true
      (alternatives.head \ "start").as[String].take(10) shouldBe Tue // same day first
    }

    it("checks the package fits the vehicle and that the dealership can do the job") {
      val wrong = book(customer, "dealershipId" -> d1, "vehicleId" -> airBlade, "packageId" -> carMinor, "start" -> s"${Wed}T13:00:00+07:00")
      wrong.status shouldBe 422
      wrong.errorCode shouldBe "PACKAGE_NOT_FOR_VEHICLE"

      val noLift = get(s"/api/availability?dealershipId=$d1&date=$Wed&packageId=$carMinor", customer)
      (noLift.json \ "offeredHere").as[Boolean] shouldBe false
      all((noLift.json \ "slots").as[Seq[JsObject]].map(s => (s \ "available").as[Boolean])) shouldBe false

      val atThuDuc = get(s"/api/availability?dealershipId=$td&date=$Wed&packageId=$carMinor", customer)
      (atThuDuc.json \ "offeredHere").as[Boolean] shouldBe true
    }

    it("rejects times that break workshop rules with a clear code") {
      book(customer, "dealershipId" -> d1, "vehicleId" -> vios, "packageId" -> essential, "start" -> "2026-11-02T07:30:00+07:00")
        .status shouldBe 422
    }

    it("lets the customer cancel before the start, which frees the slot") {
      def capacityAt9 = (slotsByTime(get(s"/api/availability?dealershipId=$d1&date=$Wed&packageId=$essential", customer))("09:00") \ "capacity").as[Int]
      val before = capacityAt9

      val res = post(s"/api/appointments/$appointmentId/cancel", customer, Json.obj("reason" -> "Plans changed"))
      res.status shouldBe 200
      res.str("status") shouldBe "CANCELLED"
      capacityAt9 shouldBe before + 1

      post(s"/api/appointments/$appointmentId/cancel", customer, Json.obj()).status shouldBe 409
    }
  }

  describe("inspection on request (consult first, then book)") {
    var requestId = ""
    var appointmentId = ""

    it("customer describes the problem; it lands in the advisor queue") {
      val res = post("/api/consultations", customer, Json.obj("kind" -> "INSPECTION", "vehicleId" -> airBlade, "dealershipId" -> d1,
        "areas" -> Seq("ENGINE", "ELECTRICAL"), "description" -> "Engine light comes on after 20 minutes of riding."))
      res.status shouldBe 201
      requestId = res.str("id")
      res.str("status") shouldBe "SUBMITTED"

      get("/api/staff/consultations?status=SUBMITTED", advisorD1).ids should contain(requestId)
      get("/api/staff/consultations", advisorTD).ids should not contain requestId
    }

    it("cannot be booked before an advisor approves it") {
      get(s"/api/availability?dealershipId=$d1&date=$Wed&consultationRequestId=$requestId", customer).status shouldBe 409
    }

    it("the advisor turns it into a schedulable job (and cannot pick a bay that does not fit the vehicle)") {
      post(s"/api/consultations/$requestId/review", advisorD1, Json.obj("decision" -> "APPROVE", "durationMinutes" -> 90,
        "requiredSkill" -> "ENGINE", "bayType" -> "CAR_LIFT")).status shouldBe 422

      val res = post(s"/api/consultations/$requestId/review", advisorD1, Json.obj("decision" -> "APPROVE", "durationMinutes" -> 90,
        "requiredSkill" -> "ENGINE", "bayType" -> "MOTORBIKE_STAND", "estimatedPrice" -> 600000,
        "note" -> "Probably the O2 sensor. We will run diagnostics first."))
      res.status shouldBe 200
      res.str("status") shouldBe "APPROVED"
      (res.json \ "durationMinutes").as[Int] shouldBe 90
      res.str("requiredSkill") shouldBe "ENGINE"

      post(s"/api/consultations/$requestId/review", advisorD1, Json.obj("decision" -> "REJECT", "note" -> "Changed my mind"))
        .status shouldBe 409
    }

    it("the customer books it; only engine-qualified technicians on shift are offered") {
      val avail = get(s"/api/availability?dealershipId=$d1&date=$Wed&consultationRequestId=$requestId", customer)
      avail.status shouldBe 200
      (avail.json \ "durationMinutes").as[Int] shouldBe 90

      // 08:00: Khoa (engine) starts at 09:00, so only Hùng can take it.
      val res = book(customer, "dealershipId" -> d1, "vehicleId" -> airBlade, "consultationRequestId" -> requestId,
        "start" -> s"${Wed}T08:00:00+07:00")
      res.status shouldBe 201
      appointmentId = res.str("id")
      res.str("technician.name") shouldBe "Trần Quốc Hùng"
      res.str("service.kind") shouldBe "INSPECTION"

      val c = get(s"/api/consultations/$requestId", customer)
      c.str("status") shouldBe "BOOKED"
      c.str("appointment.id") shouldBe appointmentId

      val again = book(customer, "dealershipId" -> d1, "vehicleId" -> airBlade, "consultationRequestId" -> requestId,
        "start" -> s"${Wed}T14:00:00+07:00")
      again.status shouldBe 409
      again.errorCode shouldBe "CONSULTATION_NOT_BOOKABLE"
    }

    it("the technician sees the job and works through it; illegal moves are refused") {
      get(s"/api/technician/jobs?date=$Wed", techHung).ids should contain(appointmentId)

      h.clock.set(s"${Wed}T08:05:00+07:00")
      val late = post(s"/api/appointments/$appointmentId/cancel", customer, Json.obj())
      late.status shouldBe 409
      late.errorCode shouldBe "TOO_LATE_TO_CANCEL"

      def move(to: String) = post(s"/api/appointments/$appointmentId/status", techHung, Json.obj("status" -> to))
      move("IN_PROGRESS").str("status") shouldBe "IN_PROGRESS"
      move("COMPLETED").errorCode shouldBe "INVALID_STATUS_TRANSITION" // payment comes first
      move("AWAITING_PAYMENT").str("status") shouldBe "AWAITING_PAYMENT"
      move("COMPLETED").status shouldBe 403 // only the dealership admin takes the payment
      move("IN_PROGRESS").status shouldBe 409

      get("/api/admin/jobs?status=AWAITING_PAYMENT", adminD1).ids should contain(appointmentId)
      get("/api/admin/jobs?status=CONFIRMED", adminD1).ids should not contain appointmentId
      get("/api/admin/jobs", advisorD1).status shouldBe 403
      get("/api/admin/jobs?status=HELD", adminD1).status shouldBe 400

      val paid = post(s"/api/appointments/$appointmentId/status", adminD1, Json.obj("status" -> "COMPLETED", "reason" -> "Paid at the desk"))
      paid.str("status") shouldBe "COMPLETED"
      get("/api/me/appointments", customer).json.as[Seq[JsObject]].find(o => (o \ "id").as[String] == appointmentId)
        .map(o => (o \ "status").as[String]) shouldBe Some("COMPLETED")

      h.sql(SQL"SELECT to_status FROM appointment_events WHERE appointment_id = $appointmentId::uuid ORDER BY id"
        .as(SqlParser.scalar[String].*)) shouldBe Seq("CONFIRMED", "IN_PROGRESS", "AWAITING_PAYMENT", "COMPLETED")
      h.clock.set(Now)
    }

    it("a no-show can only be recorded after the start time") {
      val id = book(customer, "dealershipId" -> d1, "vehicleId" -> exciter, "packageId" -> essential, "start" -> s"${Wed}T15:00:00+07:00").str("id")
      post(s"/api/appointments/$id/status", advisorD1, Json.obj("status" -> "NO_SHOW")).errorCode shouldBe "NOT_STARTED_YET"
      h.clock.set(s"${Wed}T15:20:00+07:00")
      post(s"/api/appointments/$id/status", advisorD1, Json.obj("status" -> "NO_SHOW")).str("status") shouldBe "NO_SHOW"
      h.clock.set(Now)
    }
  }

  describe("customisation request that the customer cancels") {
    it("re-opens the request when its appointment is cancelled, so another time can be picked") {
      val seeded = h.idOf(SQL"SELECT id FROM consultation_requests WHERE kind = 'MODIFICATION' AND status = 'APPROVED'")
      val booked = book(customer, "dealershipId" -> d1, "vehicleId" -> exciter, "consultationRequestId" -> seeded,
        "start" -> "2026-11-05T09:00:00+07:00")
      booked.status shouldBe 201
      booked.str("technician.name") shouldBe "Phạm Đức Khoa" // the only customisation specialist
      post(s"/api/appointments/${booked.str("id")}/cancel", customer, Json.obj())
      get(s"/api/consultations/$seeded", customer).str("status") shouldBe "APPROVED"
    }
  }

  describe("use cases added to the suite after tracing the diagrams") {
    it("UC10: a customer withdraws a submitted request; it cannot be withdrawn twice") {
      val id = post("/api/consultations", customer, Json.obj("kind" -> "MODIFICATION", "vehicleId" -> vios, "dealershipId" -> td,
        "areas" -> Seq("BODY"), "description" -> "Matte black wrap for the whole car, please.")).str("id")
      val res = post(s"/api/consultations/$id/cancel", customer)
      res.status shouldBe 200
      res.str("status") shouldBe "CANCELLED"
      post(s"/api/consultations/$id/cancel", customer).status shouldBe 409
      get("/api/staff/consultations?status=SUBMITTED", advisorTD).ids should not contain id
    }

    it("UC33: an advisor cancels a booking before work starts, freeing the slot; not after it has started") {
      def bookFriday() = book(customer, "dealershipId" -> d1, "vehicleId" -> airBlade, "packageId" -> essential,
        "start" -> "2026-11-06T13:00:00+07:00")
      val booked = bookFriday()
      booked.status shouldBe 201
      val res = post(s"/api/appointments/${booked.str("id")}/cancel", advisorD1, Json.obj("reason" -> "Customer phoned to cancel"))
      res.status shouldBe 200
      res.str("status") shouldBe "CANCELLED"
      res.str("cancelReason") shouldBe "Customer phoned to cancel"

      val again = bookFriday()
      again.status shouldBe 201
      post(s"/api/appointments/${again.str("id")}/status", advisorD1, Json.obj("status" -> "IN_PROGRESS"))
      val late = post(s"/api/appointments/${again.str("id")}/cancel", advisorD1, Json.obj())
      late.status shouldBe 409
      late.errorCode shouldBe "INVALID_STATUS_TRANSITION"
    }

    it("UC41: a technician cannot start a job assigned to another technician") {
      val created = post("/api/admin/technicians", adminD1, Json.obj("displayName" -> "Đinh Văn Phúc", "skills" -> Seq("ELECTRICAL"),
        "shiftStart" -> "08:00", "shiftEnd" -> "17:30", "workDays" -> Seq(1, 2, 3, 4, 5, 6),
        "login" -> Json.obj("email" -> "tech.phuc@demo.vn", "password" -> "Secret123")))
      created.status shouldBe 201
      val phuc = h.login("tech.phuc@demo.vn", "Secret123")
      val job = h.idOf(SQL"""SELECT a.id FROM appointments a JOIN technicians t ON t.id = a.technician_id
        WHERE t.display_name = 'Nguyễn Văn Minh' AND a.status = 'CONFIRMED' LIMIT 1""")
      post(s"/api/appointments/$job/status", phuc, Json.obj("status" -> "IN_PROGRESS")).status shouldBe 403
    }

    it("UC61: the system manager sees every dealership with its live capacity") {
      val res = get("/api/system/dealerships", sys)
      res.status shouldBe 200
      val hcm = res.json.as[Seq[JsObject]].find(d => (d \ "code").as[String] == "HCM-D1").get
      (hcm \ "activeBays").as[Int] shouldBe 4
      (hcm \ "activeTechnicians").as[Int] should be >= 4
      (hcm \ "upcomingAppointments").as[Int] should be > 0
    }
  }

  describe("administration") {
    it("a system manager onboards a dealership; its admin adds a bay and a technician; it becomes bookable") {
      val created = post("/api/system/dealerships", sys, Json.obj("code" -> "HN-CG", "name" -> "Hanoi Moto – Cau Giay",
        "address" -> "1 Xuan Thuy, Cau Giay, Hanoi", "timezone" -> "Asia/Ho_Chi_Minh", "openingTime" -> "08:00",
        "closingTime" -> "17:00", "openDays" -> Seq(1, 2, 3, 4, 5, 6),
        "admin" -> Json.obj("fullName" -> "Hanoi Admin", "email" -> "admin.hn@demo.vn", "password" -> "Secret123")))
      created.status shouldBe 201
      val dealershipId = created.str("dealershipId")
      val admin = h.login("admin.hn@demo.vn", "Secret123")
      def availability = get(s"/api/availability?dealershipId=$dealershipId&date=$Wed&packageId=$essential", customer)

      (availability.json \ "offeredHere").as[Boolean] shouldBe false
      post("/api/admin/bays", admin, Json.obj("name" -> "Stand 1", "bayType" -> "MOTORBIKE_STAND")).status shouldBe 201
      post("/api/admin/technicians", admin, Json.obj("displayName" -> "Hoàng Văn Nam", "skills" -> Seq("GENERAL_SERVICE"),
        "shiftStart" -> "08:00", "shiftEnd" -> "17:00", "workDays" -> Seq(1, 2, 3, 4, 5))).status shouldBe 201

      val after = availability
      (after.json \ "offeredHere").as[Boolean] shouldBe true
      (after.json \ "slots").as[Seq[JsObject]].count(s => (s \ "available").as[Boolean]) should be > 10
    }

    it("an admin cannot touch another dealership") {
      val tdBay = h.idOf(SQL"SELECT b.id FROM service_bays b JOIN dealerships d ON d.id = b.dealership_id WHERE d.code = 'HCM-TD' LIMIT 1")
      h.api("PATCH", s"/api/admin/bays/$tdBay", adminD1, Json.obj("isActive" -> false)).status shouldBe 404
    }
  }

  describe("observability and contract") {
    it("echoes a caller-supplied X-Request-Id and puts it in error bodies") {
      val res = h.api("GET", "/api/me/vehicles", headers = Seq("X-Request-Id" -> "trace-abc-123456"))
      res.header("X-Request-Id") shouldBe Some("trace-abc-123456")
      res.str("error.requestId") shouldBe "trace-abc-123456"
    }

    it("answers unknown routes in the API's error format") {
      val res = get("/api/nope")
      res.status shouldBe 404
      res.errorCode shouldBe "ROUTE_NOT_FOUND"
      res.header("X-Request-Id") should not be empty
    }

    it("exposes health and business metrics") {
      get("/health").json shouldBe Json.obj("status" -> "ok", "db" -> "ok")
      val metrics = get("/metrics").body
      metrics should include("bookings_created_total")
      metrics should include("""booking_conflicts_total{reason="unavailable"}""")
      metrics should include("http_request_duration_seconds_bucket")
    }

    it("documents every route in openapi.yaml") {
      val spec = scala.io.Source.fromFile("conf/openapi.yaml", "UTF-8").getLines().toSeq
      // Path keys sit at two-space indent under `paths:`, methods at four.
      val documented = spec.foldLeft((Option.empty[String], Set.empty[String])) { case ((path, acc), line) =>
        val PathLine = """  (/\S*):\s*""".r
        val MethodLine = """    (get|post|put|patch|delete):.*""".r
        line match
          case PathLine(p)   => (Some(p), acc)
          case MethodLine(m) => (path, acc ++ path.map(p => s"${m.toUpperCase} $p"))
          case _             => (path, acc)
      }._2
      val served = h.components.router.documentation
        .collect { case (method, path, _) if !path.startsWith("/docs") => s"$method ${path.replaceAll("""\$(\w+)<[^>]+>""", "{$1}")}" }
      served.filterNot(documented) shouldBe empty
    }
  }
