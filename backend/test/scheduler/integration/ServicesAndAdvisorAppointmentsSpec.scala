package scheduler.integration

import anorm.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.*

import scheduler.modules.identity.Passwords
import scheduler.support.{Harness, Response}

/**
 * Service jobs (customer, time, workshop, technician) and advisor appointments (customer,
 * time, workshop, message to be advised, advisor), end to end on the seeded demo data. The
 * clock starts on Monday 2 Nov 2026, 07:00 in Ho Chi Minh City; the seed has six open service
 * jobs on Tuesday at District 1 and two open advisor appointments (Tuesday 15:00 and 16:00).
 */
class ServicesAndAdvisorAppointmentsSpec extends AnyFunSpec with Matchers with BeforeAndAfterAll:
  private val Now = "2026-11-02T07:00:00+07:00"
  private val Wed = "2026-11-04"

  private lazy val h = Harness(Now)
  override def afterAll(): Unit = h.close()

  private lazy val d1 = h.idOf(SQL"SELECT id FROM dealerships WHERE code = 'HCM-D1'")
  private def technician(name: String) = h.idOf(SQL"SELECT id FROM technicians WHERE display_name = $name")
  private def userId(email: String) = h.idOf(SQL"SELECT id FROM users WHERE email = $email")

  private lazy val customer = h.login("customer@demo.vn")
  private lazy val walkin = h.login("walkin1@demo.vn")
  private lazy val advisorD1 = h.login("advisor.d1@demo.vn")
  private lazy val advisorTD = h.login("advisor.td@demo.vn")
  private lazy val techHung = h.login("tech.hung@demo.vn")
  private lazy val controller = h.login("controller.d1@demo.vn")

  /** A second District 1 advisor who also (wrongly) has a technician profile, signed in directly. */
  private lazy val (secondAdvisor, secondAdvisorTechnician) =
    h.sql {
      val u = SQL"""INSERT INTO users (email, password_hash, full_name, role, dealership_id)
                    VALUES ('advisor2.d1@test.local', 'x', 'Second Advisor', 'SERVICE_ADVISOR', $d1::uuid) RETURNING id"""
        .as(SqlParser.scalar[java.util.UUID].single)
      val t = SQL"""INSERT INTO technicians (dealership_id, user_id, display_name, shift_start, shift_end, work_days)
                    VALUES ($d1::uuid, $u, 'Second Advisor', '08:00', '17:30', '{1,2,3,4,5,6}') RETURNING id"""
        .as(SqlParser.scalar[java.util.UUID].single)
      SQL"INSERT INTO technician_skills VALUES ($t, 'GENERAL_SERVICE')".execute()
      val (token, tokenHash) = Passwords.newSessionToken()
      SQL"INSERT INTO sessions (token_hash, user_id, expires_at) VALUES ($tokenHash, $u, now() + interval '1 day')".execute()
      (token, t.toString)
    }

  private def get(url: String, token: String = null) = h.api("GET", url, token)
  private def post(url: String, token: String = null, body: JsValue = null) = h.api("POST", url, token, body)

  extension (r: Response)
    def errorCode: String = (r.json \ "error" \ "code").as[String]
    def str(path: String): String = path.split('.').foldLeft(JsDefined(r.json): JsLookupResult)(_ \ _).as[String]
    def items: Seq[JsObject] = r.json.as[Seq[JsObject]]

  private def openServices(token: String = techHung) = get("/api/staff/open-services", token).items
  private def startTime(j: JsObject) = (j \ "start").as[String].slice(11, 16)
  private def idOf(j: JsObject) = (j \ "id").as[String]

  describe("service jobs: technician assignment") {
    it("starts with the seeded bookings open, and shows them to technicians and advisors") {
      val open = openServices()
      open.map(startTime) shouldBe Seq("09:00", "09:00", "10:00", "10:00", "10:00", "14:00")
      open.map(j => (j \ "technicianAssigned").as[Boolean]).distinct shouldBe Seq(false)
      openServices(advisorD1).map(idOf) shouldBe open.map(idOf)
    }

    it("keeps the list away from customers") {
      get("/api/staff/open-services", customer).status shouldBe 403
    }

    it("lets a technician open and take an open job, which then leaves the open list") {
      val job = openServices().find(startTime(_) == "14:00").get
      get(s"/api/appointments/${idOf(job)}", techHung).status shouldBe 200

      val res = post(s"/api/appointments/${idOf(job)}/technician", techHung)
      res.status shouldBe 200
      res.str("technician.name") shouldBe "Trần Quốc Hùng"
      (res.json \ "technicianAssigned").as[Boolean] shouldBe true
      openServices().map(idOf) should not contain idOf(job)

      val events = h.sql(SQL"SELECT note FROM appointment_events WHERE appointment_id = ${idOf(job)}::uuid ORDER BY id"
        .as(SqlParser.scalar[String].*))
      events.last should startWith("Assigned: technician")
    }

    it("does not let a technician put someone else on a job") {
      val job = openServices().head
      val res = post(s"/api/appointments/${idOf(job)}/technician", techHung, Json.obj("technicianId" -> technician("Nguyễn Văn Minh")))
      res.status shouldBe 403
    }

    it("lets an advisor assign a technician, but never themselves or another advisor") {
      val job = openServices().find(startTime(_) == "09:00").get
      val self = post(s"/api/appointments/${idOf(job)}/technician", secondAdvisor, Json.obj("technicianId" -> secondAdvisorTechnician))
      self.status shouldBe 422
      self.errorCode shouldBe "CANNOT_ASSIGN_SELF"
      val other = post(s"/api/appointments/${idOf(job)}/technician", advisorD1, Json.obj("technicianId" -> secondAdvisorTechnician))
      other.status shouldBe 422
      other.errorCode shouldBe "NOT_A_TECHNICIAN"

      val provisional = (job \ "technician" \ "id").as[String]
      val ok = post(s"/api/appointments/${idOf(job)}/technician", advisorD1, Json.obj("technicianId" -> provisional))
      ok.status shouldBe 200
      (ok.json \ "technicianAssigned").as[Boolean] shouldBe true
      h.sql(SQL"SELECT technician_assigned_by FROM appointments WHERE id = ${idOf(job)}::uuid".as(SqlParser.scalar[java.util.UUID].single))
        .toString shouldBe userId("advisor.d1@demo.vn")
    }

    it("needs the advisor to name the technician") {
      val job = openServices().head
      post(s"/api/appointments/${idOf(job)}/technician", advisorD1).errorCode shouldBe "TECHNICIAN_REQUIRED"
    }

    it("does not let a technician take a job someone else already has") {
      val taken = h.idOf(SQL"""SELECT id FROM appointments
        WHERE technician_assigned_at IS NOT NULL AND technician_id <> ${technician("Trần Quốc Hùng")}::uuid LIMIT 1""")
      post(s"/api/appointments/$taken/technician", techHung).errorCode shouldBe "ALREADY_TAKEN"
    }

    it("is for technicians and advisors; the workshop controller keeps using reassign") {
      post(s"/api/appointments/${idOf(openServices().head)}/technician", controller).status shouldBe 403
    }

    it("books new customer jobs as open") {
      val essential = h.idOf(SQL"SELECT id FROM service_packages WHERE code = 'MB-ESSENTIAL'")
      val bike = h.idOf(SQL"SELECT id FROM vehicles WHERE plate = '59-X1 234.56'")
      val res = post("/api/appointments", customer,
        Json.obj("dealershipId" -> d1, "vehicleId" -> bike, "start" -> s"${Wed}T08:00:00+07:00", "packageId" -> essential))
      res.status shouldBe 201
      (res.json \ "technicianAssigned").as[Boolean] shouldBe false
      openServices().map(idOf) should contain(res.str("id"))
    }
  }

  describe("advisor appointments") {
    def book(start: String, token: String = customer, message: String = "Please advise me on brake pads for city riding.") =
      post("/api/advisor-appointments", token, Json.obj("dealershipId" -> d1, "start" -> start, "message" -> message))
    def openAppointments(token: String = advisorD1) = get("/api/staff/advisor-appointments?open=true", token).items

    it("shows the advisor the open appointments first, soonest first") {
      val open = openAppointments()
      open.map(startTime) shouldBe Seq("15:00", "16:00")
      open.map(j => (j \ "advisor").toOption.flatMap(_.asOpt[JsObject])).distinct shouldBe Seq(None)
      (open.head \ "message").as[String] should include("chain")
    }

    it("keeps them away from technicians and customers") {
      get("/api/staff/advisor-appointments?open=true", techHung).status shouldBe 403
      get("/api/staff/advisor-appointments?open=true", customer).status shouldBe 403
    }

    it("lets a customer book one with a message to be advised; it starts open and is not a service job") {
      val res = book(s"${Wed}T09:00:00+07:00")
      res.status shouldBe 201
      res.str("status") shouldBe "SCHEDULED"
      res.str("message") shouldBe "Please advise me on brake pads for city riding."
      res.str("dealership.id") shouldBe d1
      (res.json \ "advisor").toOption.flatMap(_.asOpt[JsObject]) shouldBe None
      res.str("reference") should startWith("AP-")
      get(s"/api/appointments/${res.str("id")}", customer).status shouldBe 404
      get("/api/me/advisor-appointments", customer).items.map(idOf) should contain(res.str("id"))
      openAppointments().map(idOf) should contain(res.str("id"))
    }

    it("checks the time and the message") {
      book(s"${Wed}T07:00:00+07:00").errorCode shouldBe "OUTSIDE_OPENING_HOURS"
      book("2026-11-02T07:30:00+07:00").errorCode shouldBe "PAST_OR_TOO_SOON"
      book(s"${Wed}T09:10:00+07:00").errorCode shouldBe "NOT_ON_SLOT_GRID"
      book("2026-11-08T09:00:00+07:00").errorCode shouldBe "OUTSIDE_OPENING_HOURS" // Sunday
      book(s"${Wed}T10:00:00+07:00", message = "hi").status shouldBe 400
    }

    it("lets an advisor take an open appointment, once, inside their own dealership") {
      val id = idOf(openAppointments().head)
      post(s"/api/advisor-appointments/$id/take", advisorTD).status shouldBe 403

      val taken = post(s"/api/advisor-appointments/$id/take", advisorD1)
      taken.status shouldBe 200
      taken.str("advisor.name") shouldBe "Bùi Ngọc Anh"
      post(s"/api/advisor-appointments/$id/take", advisorD1).status shouldBe 200 // taking it again changes nothing
      post(s"/api/advisor-appointments/$id/take", secondAdvisor).errorCode shouldBe "ALREADY_TAKEN"
      openAppointments().map(idOf) should not contain id
    }

    it("refuses an advisor two meetings at the same moment") {
      val first = book(s"${Wed}T11:00:00+07:00").str("id")
      val second = book(s"${Wed}T11:00:00+07:00", token = walkin).str("id")
      post(s"/api/advisor-appointments/$first/take", advisorD1).status shouldBe 200
      post(s"/api/advisor-appointments/$second/take", advisorD1).errorCode shouldBe "ADVISOR_BUSY"
      post(s"/api/advisor-appointments/$second/take", secondAdvisor).status shouldBe 200
    }

    it("is closed by the advisor holding it") {
      val id = book(s"${Wed}T13:00:00+07:00").str("id")
      post(s"/api/advisor-appointments/$id/take", advisorD1)
      post(s"/api/advisor-appointments/$id/complete", secondAdvisor).status shouldBe 403
      val done = post(s"/api/advisor-appointments/$id/complete", advisorD1)
      done.status shouldBe 200
      done.str("status") shouldBe "DONE"
      post(s"/api/advisor-appointments/$id/cancel", customer).errorCode shouldBe "NOT_OPEN"
    }

    it("can be cancelled by its customer, and stays hidden from other customers") {
      val id = book(s"${Wed}T14:00:00+07:00").str("id")
      get(s"/api/advisor-appointments/$id", walkin).status shouldBe 404
      post(s"/api/advisor-appointments/$id/cancel", walkin).status shouldBe 404
      val cancelled = post(s"/api/advisor-appointments/$id/cancel", customer, Json.obj("reason" -> "Sorted it out by phone"))
      cancelled.status shouldBe 200
      cancelled.str("status") shouldBe "CANCELLED"
      cancelled.str("cancelReason") shouldBe "Sorted it out by phone"
    }

    it("lists one local day for the advisor board") {
      val day = get(s"/api/staff/advisor-appointments?date=$Wed", advisorD1).items
      day.map(startTime) shouldBe day.map(startTime).sorted
      day.map(startTime) should contain allOf ("09:00", "11:00", "13:00", "14:00")
      get("/api/staff/advisor-appointments", advisorD1).errorCode shouldBe "DATE_REQUIRED"
    }
  }
