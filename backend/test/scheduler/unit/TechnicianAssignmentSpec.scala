package scheduler.unit

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scheduler.modules.identity.Role
import scheduler.modules.scheduling.domain.TechnicianAssignment
import scheduler.modules.scheduling.domain.TechnicianAssignment.{Account, Refusal}

/** Who may put which technician on a service job. */
class TechnicianAssignmentSpec extends AnyFunSpec with Matchers:
  private val techAccount = Some(Account("u-tech", Role.TECHNICIAN))

  describe("a technician") {
    it("may take a job for themselves") {
      TechnicianAssignment.check(Role.TECHNICIAN, "u-tech", Some("t1"), "t1", techAccount) shouldBe None
    }

    it("may not put someone else on a job") {
      TechnicianAssignment.check(Role.TECHNICIAN, "u-tech", Some("t1"), "t2", Some(Account("u2", Role.TECHNICIAN))) shouldBe
        Some(Refusal.ONLY_SELF)
    }

    it("needs a technician profile") {
      TechnicianAssignment.check(Role.TECHNICIAN, "u-tech", None, "t1", None) shouldBe Some(Refusal.NOT_LINKED_TO_TECHNICIAN)
    }
  }

  describe("a service advisor") {
    it("may assign a technician") {
      TechnicianAssignment.check(Role.SERVICE_ADVISOR, "u-adv", None, "t1", techAccount) shouldBe None
    }

    it("may assign a technician profile that has no account") {
      TechnicianAssignment.check(Role.SERVICE_ADVISOR, "u-adv", None, "t1", None) shouldBe None
    }

    it("may not assign themselves") {
      TechnicianAssignment.check(Role.SERVICE_ADVISOR, "u-adv", Some("t9"), "t9", Some(Account("u-adv", Role.SERVICE_ADVISOR))) shouldBe
        Some(Refusal.CANNOT_ASSIGN_SELF)
    }

    it("may not assign another advisor (or any account that is not a technician)") {
      TechnicianAssignment.check(Role.SERVICE_ADVISOR, "u-adv", None, "t9", Some(Account("u-adv2", Role.SERVICE_ADVISOR))) shouldBe
        Some(Refusal.NOT_A_TECHNICIAN)
      TechnicianAssignment.check(Role.SERVICE_ADVISOR, "u-adv", None, "t9", Some(Account("u-admin", Role.ADMIN))) shouldBe
        Some(Refusal.NOT_A_TECHNICIAN)
    }
  }

  it("is not how other roles move jobs") {
    for role <- Seq(Role.CUSTOMER, Role.ADMIN, Role.WORKSHOP_CONTROLLER, Role.PARTS_STAFF, Role.SYSTEM_MANAGER) do
      TechnicianAssignment.check(role, "u", None, "t1", techAccount) shouldBe Some(Refusal.ROLE_NOT_ALLOWED)
  }
