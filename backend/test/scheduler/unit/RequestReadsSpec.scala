package scheduler.unit

import java.time.Year

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.*

import scheduler.modules.admin.application.AdminRequests
import scheduler.modules.consultation.application.{Review, SubmitRequest}
import scheduler.modules.identity.application.IdentityRequests
import scheduler.modules.parts.application.PartsRequests
import scheduler.modules.scheduling.application.*
import scheduler.modules.scheduling.domain.AppointmentStatus
import scheduler.modules.system.application.{NewDealership, PackageRequests}
import scheduler.modules.vehicles.NewVehicle

/** The request shapes each use case accepts, checked at the HTTP edge. */
class RequestReadsSpec extends AnyFunSpec with Matchers:
  private val Id1 = "11111111-1111-1111-1111-111111111111"
  private val Id2 = "22222222-2222-2222-2222-222222222222"

  private def ok[T](json: JsValue)(using r: Reads[T]): T = json.validate[T] match
    case JsSuccess(v, _) => v
    case e: JsError      => fail(s"expected success, got $e")

  private def messages[T](json: JsValue)(using r: Reads[T]): Seq[String] = json.validate[T] match
    case JsSuccess(v, _) => fail(s"expected an error, got $v")
    case JsError(errs)   => errs.toSeq.flatMap(_._2).flatMap(_.messages)

  describe("scheduling") {
    val booking = Json.obj("dealershipId" -> Id1, "vehicleId" -> Id2, "start" -> "2026-11-04T09:00:00+07:00", "packageId" -> Id1)

    it("books either a package or a consultation, never both or neither") {
      ok[BookRequest](booking).packageId shouldBe Some(Id1)
      messages[BookRequest](booking + ("consultationRequestId" -> JsString(Id2))) should contain(
        "Give exactly one of packageId or consultationRequestId")
      messages[BookRequest](booking - "packageId") should contain("Give exactly one of packageId or consultationRequestId")
    }

    it("drops an empty customer note") {
      ok[BookRequest](booking + ("customerNote" -> JsString("   "))).customerNote shouldBe None
      ok[BookRequest](booking + ("customerNote" -> JsString(" Call me "))).customerNote shouldBe Some("Call me")
    }

    it("asks for availability by date with the same one-of rule") {
      ok[AvailabilityQuery](Json.obj("dealershipId" -> Id1, "date" -> "2026-11-04", "consultationRequestId" -> Id2)).packageId shouldBe None
      messages[AvailabilityQuery](Json.obj("dealershipId" -> Id1, "date" -> "2026-11-04")) should not be empty
    }

    it("lets staff set only the statuses that have no endpoint of their own") {
      ok[StatusChange](Json.obj("status" -> "IN_PROGRESS")).status shouldBe AppointmentStatus.IN_PROGRESS
      for status <- Seq("CONFIRMED", "HELD", "EXPIRED") do
        messages[StatusChange](Json.obj("status" -> status)).head should startWith("Must be one of")
    }

    it("moves a job to a bay, a technician or both") {
      ok[Reassignment](Json.obj("bayId" -> Id1)).technicianId shouldBe None
      messages[Reassignment](Json.obj("reason" -> "sick")) should contain("Give a bayId, a technicianId or both")
    }

    it("checks the shape of an Idempotency-Key") {
      IdempotencyKey.isValid("abc-123_XYZ") shouldBe true
      IdempotencyKey.isValid("short") shouldBe false
      IdempotencyKey.isValid("has space 1234") shouldBe false
      IdempotencyKey.isValid("x" * 101) shouldBe false
    }
  }

  describe("vehicles") {
    val car = Json.obj("vehicleType" -> "CAR", "make" -> "Kia", "model" -> "Morning", "plate" -> "30A-12345")

    it("normalises a VIN, and treats an empty one as none") {
      ok[NewVehicle](car + ("vin" -> JsString(" 1hgcm82633a004352 "))).vin shouldBe Some("1HGCM82633A004352")
      ok[NewVehicle](car + ("vin" -> JsString(""))).vin shouldBe None
      ok[NewVehicle](car).vin shouldBe None
    }

    it("rejects VINs with I, O or Q or the wrong length") {
      messages[NewVehicle](car + ("vin" -> JsString("1HGCM82633A00435O"))) should contain("A VIN has 17 characters and never uses I, O or Q")
      messages[NewVehicle](car + ("vin" -> JsString("123"))) should contain("A VIN has 17 characters and never uses I, O or Q")
    }

    it("accepts model years up to next year") {
      ok[NewVehicle](car + ("year" -> JsNumber(Year.now().getValue + 1))).year shouldBe Some(Year.now().getValue + 1)
      messages[NewVehicle](car + ("year" -> JsNumber(1949))) should contain("Must be at least 1950")
      messages[NewVehicle](car + ("year" -> JsNumber(Year.now().getValue + 2))).head should startWith("Must be at most")
    }
  }

  describe("identity") {
    import IdentityRequests.given

    it("requires a password of 8+ characters with a letter and a digit") {
      def register(password: String) = Json.obj("email" -> "a@b.co", "password" -> password, "fullName" -> "Lan")
      ok[IdentityRequests.Register](register("abcdefg1")).password shouldBe "abcdefg1"
      messages[IdentityRequests.Register](register("abc1")) should contain("Password must be at least 8 characters")
      messages[IdentityRequests.Register](register("abcdefgh")) should contain("Password must contain a digit")
      messages[IdentityRequests.Register](register("12345678")) should contain("Password must contain a letter")
    }

    it("checks the phone number when one is given") {
      val base = Json.obj("email" -> "a@b.co", "password" -> "abcdefg1", "fullName" -> "Lan")
      ok[IdentityRequests.Register](base + ("phone" -> JsString("+84 912 345 678"))).phone shouldBe Some("+84 912 345 678")
      messages[IdentityRequests.Register](base + ("phone" -> JsString("call me"))) should contain("Phone number looks invalid")
    }
  }

  describe("consultations") {
    it("needs at least one known area and a real description") {
      val req = Json.obj("kind" -> "INSPECTION", "vehicleId" -> Id1, "dealershipId" -> Id2, "areas" -> Json.arr("BRAKES"),
        "description" -> "Brakes squeak when wet")
      ok[SubmitRequest](req).areas shouldBe List("BRAKES")
      messages[SubmitRequest](req + ("areas" -> Json.arr())) should contain("Pick at least one area")
      messages[SubmitRequest](req + ("areas" -> Json.arr("WINGS"))).head should startWith("Must be one of")
      messages[SubmitRequest](req + ("description" -> JsString("bad"))) should contain("Tell us a bit more (at least 10 characters)")
    }

    it("reads the advisor's decision by its kind") {
      ok[Review](Json.obj("decision" -> "APPROVE", "durationMinutes" -> 45, "requiredSkill" -> "ENGINE", "bayType" -> "DIAGNOSTIC_BAY")) shouldBe
        Review.Approve(45, "ENGINE", "DIAGNOSTIC_BAY", None, None)
      ok[Review](Json.obj("decision" -> "REJECT", "note" -> "Not our job")) shouldBe Review.Reject("Not our job")
      messages[Review](Json.obj("decision" -> "MAYBE")) should contain("Must be APPROVE or REJECT")
      messages[Review](Json.obj("decision" -> "REJECT", "note" -> "no")) should contain("Explain to the customer why")
    }

    it("approves durations on the 15-minute grid, up to a working day") {
      def approve(minutes: Int) = Json.obj("decision" -> "APPROVE", "durationMinutes" -> minutes, "requiredSkill" -> "E", "bayType" -> "B")
      messages[Review](approve(50)) should contain("Use 15-minute steps")
      messages[Review](approve(495)) should contain("Must be at most 480")
      messages[Review](approve(0)) should contain("Must be at least 15")
    }
  }

  describe("parts") {
    import PartsRequests.given

    it("upper-cases SKUs and defaults stock to zero") {
      val p = ok[PartsRequests.AddPart](Json.obj("sku" -> "oil-5w30", "name" -> "Engine oil", "unitPrice" -> 120000))
      (p.sku, p.stockQty) shouldBe ("OIL-5W30", 0)
    }

    it("needs at least one line with a quantity between 1 and 100") {
      def order(lines: JsArray) = Json.obj("lines" -> lines)
      ok[PartsRequests.NewOrder](order(Json.arr(Json.obj("partId" -> Id1, "quantity" -> 2)))).lines.head.quantity shouldBe 2
      messages[PartsRequests.NewOrder](order(Json.arr())) should contain("Add at least one part")
      messages[PartsRequests.NewOrder](order(Json.arr(Json.obj("partId" -> Id1, "quantity" -> 0)))) should contain("Must be at least 1")
    }
  }

  describe("admin and platform") {
    import AdminRequests.given
    import PackageRequests.given

    it("allows only 15, 30 or 60 minute slot grids") {
      def hours(slot: Int) = Json.obj("openingTime" -> "08:00", "closingTime" -> "17:00", "openDays" -> Json.arr(1, 2),
        "slotMinutes" -> slot, "minLeadMinutes" -> 60, "maxDaysAhead" -> 30)
      ok[AdminRequests.Hours](hours(15)).slotMinutes shouldBe 15
      messages[AdminRequests.Hours](hours(20)) should contain("Must be 15, 30 or 60")
    }

    it("lets an admin create only dealership staff roles") {
      def staff(role: String) = Json.obj("fullName" -> "Lan", "email" -> "l@x.io", "password" -> "secret123", "role" -> role)
      ok[AdminRequests.NewStaff](staff("PARTS_STAFF")).role.toString shouldBe "PARTS_STAFF"
      for role <- Seq("SYSTEM_MANAGER", "CUSTOMER", "TECHNICIAN") do
        messages[AdminRequests.NewStaff](staff(role)).head should startWith("Must be one of")
    }

    it("reads a service package with durations on the 15-minute grid") {
      val pkg = Json.obj("code" -> "car-oil", "name" -> "Oil change", "description" -> "Fresh oil", "vehicleType" -> "CAR",
        "durationMinutes" -> 45, "requiredSkill" -> "GENERAL_SERVICE", "bayType" -> "CAR_LIFT", "priceAmount" -> 100,
        "checklist" -> Json.arr("Drain", "Refill"))
      val p = ok[PackageRequests.NewPackage](pkg)
      (p.code, p.isRecommended) shouldBe ("CAR-OIL", false)
      messages[PackageRequests.NewPackage](pkg + ("durationMinutes" -> JsNumber(50))) should contain("Must be a multiple of 15 minutes")
    }

    it("onboards a dealership with sensible booking defaults") {
      val d = ok[NewDealership](Json.obj("code" -> "hn-01", "name" -> "Ha Noi", "address" -> "1 Trang Tien", "timezone" -> "Asia/Bangkok",
        "openingTime" -> "08:00", "closingTime" -> "17:00", "openDays" -> Json.arr(1, 2, 3),
        "admin" -> Json.obj("fullName" -> "Admin", "email" -> "a@x.io", "password" -> "secret123")))
      (d.code, d.slotMinutes, d.minLeadMinutes, d.maxDaysAhead) shouldBe ("HN-01", 30, 60, 60)
    }
  }
