package scheduler.unit

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scheduler.modules.identity.Role
import scheduler.modules.scheduling.domain.AppointmentStatus.*
import scheduler.modules.scheduling.domain.Lifecycle.*
import scheduler.modules.scheduling.domain.Lifecycle.TransitionError.*

class LifecycleSpec extends AnyFunSpec with Matchers:
  private val Start = 1_000_000L

  describe("appointment lifecycle") {
    it("follows CONFIRMED → IN_PROGRESS → AWAITING_PAYMENT → COMPLETED and allows no way back") {
      canTransition(CONFIRMED, IN_PROGRESS) shouldBe true
      canTransition(IN_PROGRESS, AWAITING_PAYMENT) shouldBe true
      canTransition(AWAITING_PAYMENT, COMPLETED) shouldBe true
      canTransition(IN_PROGRESS, COMPLETED) shouldBe false // payment comes first
      canTransition(AWAITING_PAYMENT, IN_PROGRESS) shouldBe false
      canTransition(AWAITING_PAYMENT, CANCELLED) shouldBe false
      canTransition(COMPLETED, IN_PROGRESS) shouldBe false
      canTransition(CANCELLED, CONFIRMED) shouldBe false
      canTransition(IN_PROGRESS, CANCELLED) shouldBe false // work has started
    }

    it("lets a customer cancel only before the start time") {
      checkTransition(CONFIRMED, CANCELLED, Role.CUSTOMER, Start, Start - 1) shouldBe None
      checkTransition(CONFIRMED, CANCELLED, Role.CUSTOMER, Start, Start) shouldBe Some(ALREADY_STARTED)
    }

    it("only allows a no-show once the start time has passed") {
      checkTransition(CONFIRMED, NO_SHOW, Role.SERVICE_ADVISOR, Start, Start - 1) shouldBe Some(NOT_STARTED_YET)
      checkTransition(CONFIRMED, NO_SHOW, Role.SERVICE_ADVISOR, Start, Start + 1) shouldBe None
    }

    it("keeps each role to its own actions") {
      checkTransition(CONFIRMED, IN_PROGRESS, Role.CUSTOMER, Start, Start) shouldBe Some(ROLE_NOT_ALLOWED)
      checkTransition(CONFIRMED, CANCELLED, Role.TECHNICIAN, Start, Start) shouldBe Some(ROLE_NOT_ALLOWED)
      checkTransition(CONFIRMED, IN_PROGRESS, Role.TECHNICIAN, Start, Start) shouldBe None
    }

    it("lets staff start a job before its booked time (the vehicle may arrive early)") {
      checkTransition(CONFIRMED, IN_PROGRESS, Role.TECHNICIAN, Start, Start - 3 * 24 * 3600 * 1000L) shouldBe None
      checkTransition(CONFIRMED, IN_PROGRESS, Role.SERVICE_ADVISOR, Start, Start - 1) shouldBe None
    }

    it("lets the people who did the work finish it, and the technician or the admin mark it paid") {
      for role <- Seq(Role.TECHNICIAN, Role.SERVICE_ADVISOR, Role.ADMIN) do
        checkTransition(IN_PROGRESS, AWAITING_PAYMENT, role, Start, Start) shouldBe None
      for role <- Seq(Role.TECHNICIAN, Role.ADMIN) do
        checkTransition(AWAITING_PAYMENT, COMPLETED, role, Start, Start) shouldBe None
      for role <- Seq(Role.SERVICE_ADVISOR, Role.CUSTOMER, Role.WORKSHOP_CONTROLLER, Role.SYSTEM_MANAGER) do
        checkTransition(AWAITING_PAYMENT, COMPLETED, role, Start, Start) shouldBe Some(ROLE_NOT_ALLOWED)
    }

    it("lets a hold be confirmed, released or time out, and nothing else") {
      canTransition(HELD, CONFIRMED) shouldBe true
      canTransition(HELD, CANCELLED) shouldBe true
      canTransition(HELD, EXPIRED) shouldBe true
      canTransition(HELD, IN_PROGRESS) shouldBe false
      canTransition(EXPIRED, CONFIRMED) shouldBe false
      checkTransition(HELD, CONFIRMED, Role.CUSTOMER, Start, Start - 1) shouldBe None
      checkTransition(HELD, CONFIRMED, Role.SERVICE_ADVISOR, Start, Start - 1) shouldBe Some(ROLE_NOT_ALLOWED)
    }
  }
