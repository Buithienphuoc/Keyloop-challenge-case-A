package scheduler.unit

import java.time.{LocalDate, LocalTime, ZoneId}

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.functional.syntax.*
import play.api.libs.json.*

import scheduler.modules.scheduling.domain.VehicleType
import scheduler.shared.{FieldError, Validate, ValidationFailed}

/** The request-validation building blocks and how their failures are reported. */
class ValidateSpec extends AnyFunSpec with Matchers:

  private def read[T](r: Reads[T], value: JsValue): Either[String, T] = r.reads(value) match
    case JsSuccess(v, _) => Right(v)
    case JsError(errs)   => Left(errs.flatMap(_._2).flatMap(_.messages).last)

  describe("text") {
    it("trims and checks the length") {
      read(Validate.text(2, 5), JsString("  ab  ")) shouldBe Right("ab")
      read(Validate.text(), JsString("   ")) shouldBe Left("Required")
      read(Validate.text(3, 5), JsString("ab")) shouldBe Left("Must be at least 3 characters")
      read(Validate.text(1, 3), JsString("abcd")) shouldBe Left("Must be at most 3 characters")
      read(Validate.text(10, 20, tooShort = "Tell us more"), JsString("short")) shouldBe Left("Tell us more")
    }
  }

  describe("uuid") {
    it("accepts canonical UUIDs only, lower-cased") {
      read(Validate.uuid, JsString("3F2504E0-4F89-11D3-9A0C-0305E82C3301")) shouldBe Right("3f2504e0-4f89-11d3-9a0c-0305e82c3301")
      read(Validate.uuid, JsString("1-1-1-1-1")) shouldBe Left("Must be a UUID")
      read(Validate.uuid, JsString("zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz")) shouldBe Left("Must be a UUID")
    }
  }

  describe("dates and times") {
    it("reads YYYY-MM-DD and rejects impossible dates") {
      read(Validate.isoDate, JsString("2026-02-28")) shouldBe Right(LocalDate.of(2026, 2, 28))
      read(Validate.isoDate, JsString("2026-02-30")) shouldBe Left("Not a valid date")
      read(Validate.isoDate, JsString("2026-2-3")) shouldBe Left("Use the format YYYY-MM-DD")
      read(Validate.isoDate, JsString("2026-02-28T00:00")) shouldBe Left("Use the format YYYY-MM-DD")
    }

    it("needs an explicit offset on date-times") {
      read(Validate.isoDateTimeWithOffset, JsString("2026-11-04T09:00:00+07:00")) shouldBe Right("2026-11-04T09:00:00+07:00")
      read(Validate.isoDateTimeWithOffset, JsString("2026-11-04T02:00:00Z")).isRight shouldBe true
      read(Validate.isoDateTimeWithOffset, JsString("2026-11-04T09:00:00")).isLeft shouldBe true
    }

    it("reads HH:mm on a 24-hour clock") {
      read(Validate.hhmm, JsString("08:30")) shouldBe Right(LocalTime.of(8, 30))
      read(Validate.hhmm, JsString("23:59")) shouldBe Right(LocalTime.of(23, 59))
      read(Validate.hhmm, JsString("24:00")) shouldBe Left("Use HH:mm")
      read(Validate.hhmm, JsString("8:30")) shouldBe Left("Use HH:mm")
    }

    it("knows real time zones only") {
      read(Validate.timeZone, JsString("Asia/Ho_Chi_Minh")) shouldBe Right(ZoneId.of("Asia/Ho_Chi_Minh"))
      read(Validate.timeZone, JsString("Mars/Olympus")) shouldBe Left("Unknown time zone")
    }
  }

  describe("email, numbers, enums and lists") {
    it("checks the shape of an email address") {
      read(Validate.email, JsString(" a@b.co ")) shouldBe Right("a@b.co")
      read(Validate.email, JsString("a@b")) shouldBe Left("Not a valid email address")
      read(Validate.email, JsString("a b@c.de")) shouldBe Left("Not a valid email address")
      read(Validate.email, JsString("a@" + "b" * 200 + ".de")) shouldBe Left("Must be at most 200 characters")
    }

    it("keeps whole numbers inside their range") {
      read(Validate.int(1, 7), JsNumber(7)) shouldBe Right(7)
      read(Validate.int(1, 7), JsNumber(0)) shouldBe Left("Must be at least 1")
      read(Validate.int(1, 7), JsNumber(8)) shouldBe Left("Must be at most 7")
    }

    it("reads an enum case by its exact name") {
      read(Validate.oneOf(VehicleType.values), JsString("CAR")) shouldBe Right(VehicleType.CAR)
      read(Validate.oneOf(VehicleType.values), JsString("car")) shouldBe Left("Must be one of MOTORBIKE, CAR")
    }

    it("counts list items") {
      read(Validate.listOf(Validate.int(0, 9), min = 1, max = 2), Json.arr(1, 2)) shouldBe Right(List(1, 2))
      read(Validate.listOf(Validate.int(0, 9), min = 1, max = 2), Json.arr()) shouldBe Left("Give at least 1")
      read(Validate.listOf(Validate.int(0, 9), min = 1, max = 2, tooFew = "Pick one"), Json.arr()) shouldBe Left("Pick one")
      read(Validate.listOf(Validate.int(0, 9), min = 1, max = 2), Json.arr(1, 2, 3)) shouldBe Left("Give at most 2")
    }
  }

  private final case class Form(name: String, days: List[Int])
  private given Reads[Form] = ((__ \ "name").read(Validate.text(2, 10)) and (__ \ "days").read(Validate.listOf(Validate.int(1, 7))))(Form.apply)

  describe("body and query") {
    it("returns the typed value when the body is valid") {
      Validate.body[Form](Json.obj("name" -> "Lan", "days" -> Json.arr(1, 2))) shouldBe Form("Lan", List(1, 2))
    }

    it("reports one friendly message per field, with list indexes in the path") {
      val e = the[ValidationFailed] thrownBy Validate.body[Form](Json.obj("days" -> Json.arr(1, 9, "x")))
      e.errors should contain theSameElementsAs Seq(
        FieldError("name", "Required"),
        FieldError("days.1", "Must be at most 7"),
        FieldError("days.2", "Expected a number")
      )
    }

    it("validates query strings with the same Reads, using the first value of each key") {
      final case class Q(date: LocalDate)
      given Reads[Q] = (__ \ "date").read(Validate.isoDate).map(Q.apply)
      Validate.query[Q](Map("date" -> Seq("2026-11-04", "ignored"))) shouldBe Q(LocalDate.of(2026, 11, 4))
      (the[ValidationFailed] thrownBy Validate.query[Q](Map("date" -> Nil))).errors shouldBe Seq(FieldError("date", "Required"))
    }
  }
