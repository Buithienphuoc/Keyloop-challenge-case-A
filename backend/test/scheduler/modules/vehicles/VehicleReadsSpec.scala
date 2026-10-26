package scheduler.modules.vehicles

import java.time.Year

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{JsNumber, Json}

class VehicleReadsSpec extends AnyFunSpec with Matchers:
  describe("model year") {
    it("accepts up to next year and nothing later") {
      val reads = NewVehicle.modelYear(() => Year.of(2026))
      reads.reads(JsNumber(2027)).isSuccess shouldBe true
      reads.reads(JsNumber(2028)).isError shouldBe true
      reads.reads(JsNumber(1949)).isError shouldBe true
    }

    it("works the limit out on each read, so it moves on at New Year without a restart") {
      var now = Year.of(2026)
      val reads = NewVehicle.modelYear(() => now)
      reads.reads(JsNumber(2028)).isError shouldBe true
      now = Year.of(2027) // the server kept running past New Year
      reads.reads(JsNumber(2028)).isSuccess shouldBe true
    }

    it("is what the vehicle form's Reads uses") {
      val next = Year.now().getValue + 1
      val form = Json.obj("vehicleType" -> "MOTORBIKE", "make" -> "Honda", "model" -> "Vision", "plate" -> "59-X1 111.11")
      (form + ("year" -> JsNumber(next))).validate[NewVehicle].isSuccess shouldBe true
      (form + ("year" -> JsNumber(next + 1))).validate[NewVehicle].isError shouldBe true
    }
  }
