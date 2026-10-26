package scheduler.unit

import java.time.{LocalTime, ZoneId}

import scala.collection.mutable

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scheduler.modules.admin.*
import scheduler.modules.admin.application.AdminRequests.*
import scheduler.modules.admin.application.WorkshopAdminService
import scheduler.modules.identity.*
import scheduler.modules.identity.application.IdentityService
import scheduler.modules.scheduling.domain.VehicleType
import scheduler.modules.system.*
import scheduler.modules.system.application.{NewAdmin, NewDealership, PackageAdminService, TenantService}
import scheduler.modules.system.application.PackageRequests.NewPackage
import scheduler.modules.vehicles.{NewVehicle, Vehicle, VehicleRepository}
import scheduler.modules.vehicles.application.VehicleService
import scheduler.shared.{AppError, DbSession, Log}
import scheduler.support.Fakes.*

/** Dealership admin, platform admin and vehicles: the rules their services add on top of the repositories. */
class AdminServicesSpec extends AnyFunSpec with Matchers:
  private val admin = auth(Role.ADMIN)

  /** Users kept in memory; passwords are hashed for real, so keep the number of accounts small. */
  private final class FakeUsers(db: FakeDb) extends UserRepository:
    val inserted = mutable.Buffer.empty[NewUserRecord]
    var inTransaction = 0
    def insert(s: DbSession, u: NewUserRecord): PublicUser =
      if db.inTransaction then inTransaction += 1
      inserted += u
      PublicUser(s"u${inserted.size}", u.email, u.fullName, u.phone, u.role, u.dealershipId, None)
    def findByEmail(email: String): Option[StoredUser] = None
    def findById(id: String): Option[PublicUser] = None
    def listByDealership(dealershipId: String): Seq[StaffMember] = Nil

  private object NoSessions extends SessionRepository:
    def create(userId: String, tokenHash: String, expiresAt: java.time.Instant): Unit = ()
    def revoke(tokenHash: String): Boolean = false
    def findCaller(tokenHash: String): Option[AuthContext] = None

  private final class FakeWorkshop extends WorkshopRepository:
    var technician: Option[NewTechnicianRecord] = None
    var hours: Option[WorkshopHours] = None
    val activeBays = Set("b1")
    def listBays(dealershipId: String, onlyActive: Boolean): Seq[BayRecord] = Seq(BayRecord("b1", "S", "MOTORBIKE_STAND", "Stand", true))
    def addBay(dealershipId: String, name: String, bayType: String): String = "b2"
    def setBayActive(dealershipId: String, bayId: String, isActive: Boolean): Boolean = activeBays(bayId)
    def listTechnicians(dealershipId: String, onlyActive: Boolean): Seq[TechnicianRecord] = Nil
    def addTechnician(s: DbSession, t: NewTechnicianRecord): String =
      technician = Some(t)
      "t1"
    def setTechnicianActive(dealershipId: String, technicianId: String, isActive: Boolean): Boolean = technicianId == "t1"
    def updateHours(dealershipId: String, h: WorkshopHours): Int =
      hours = Some(h)
      3

  private final class Fixture:
    val db = FakeDb()
    val users = FakeUsers(db)
    val workshop = FakeWorkshop()
    val identity = IdentityService(db, users, NoSessions, 1)
    val service = WorkshopAdminService(db, workshop, identity)

  private def codeOf(f: => Any): String = (the[AppError] thrownBy f).code
  private val eight = LocalTime.of(8, 0)
  private val five = LocalTime.of(17, 0)

  describe("WorkshopAdminService") {
    it("adds a technician with a login in one transaction") {
      val f = Fixture()
      f.service.addTechnician(admin, NewTechnician("Minh", List("ENGINE"), eight, five, List(1, 2), Some(Login("m@x.io", "secret123"))),
        Log.silent) shouldBe "t1"
      f.db.transactions shouldBe 1
      f.users.inTransaction shouldBe 1
      f.users.inserted.head.role shouldBe Role.TECHNICIAN
      f.users.inserted.head.dealershipId shouldBe Some("d1")
      f.workshop.technician.flatMap(_.userId) shouldBe Some("u1")
    }

    it("adds a technician without a login") {
      val f = Fixture()
      f.service.addTechnician(admin, NewTechnician("Lan", List("ENGINE"), eight, five, List(1), None), Log.silent)
      f.users.inserted shouldBe empty
      f.workshop.technician.flatMap(_.userId) shouldBe None
    }

    it("refuses a shift that does not end after it starts") {
      val f = Fixture()
      codeOf(f.service.addTechnician(admin, NewTechnician("Lan", List("ENGINE"), five, eight, List(1), None), Log.silent)) shouldBe "INVALID_SHIFT"
      codeOf(f.service.addTechnician(admin, NewTechnician("Lan", List("ENGINE"), eight, eight, List(1), None), Log.silent)) shouldBe "INVALID_SHIFT"
      f.workshop.technician shouldBe empty
    }

    it("saves opening hours with weekdays de-duplicated and sorted, and reports bookings now outside them") {
      val f = Fixture()
      val r = f.service.updateHours(admin, Hours(eight, five, List(6, 1, 1, 3), 30, 60, 30), Log.silent)
      r.hours.openDays shouldBe Seq(1, 3, 6)
      r.upcomingOutsideHours shouldBe 3
      codeOf(f.service.updateHours(admin, Hours(five, eight, List(1), 30, 60, 30), Log.silent)) shouldBe "INVALID_HOURS"
    }

    it("answers 404 when a bay or technician is not in the dealership") {
      val f = Fixture()
      (the[AppError] thrownBy f.service.setBayActive(admin, "zz", false, Log.silent)).status shouldBe 404
      (the[AppError] thrownBy f.service.setTechnicianActive(admin, "zz", false, Log.silent)).status shouldBe 404
      noException should be thrownBy f.service.setBayActive(admin, "b1", false, Log.silent)
    }

    it("creates staff in the admin's own dealership only") {
      val f = Fixture()
      f.service.addStaff(admin, NewStaff("Lan", "lan@x.io", "secret123", None, Role.SERVICE_ADVISOR), Log.silent).dealershipId shouldBe Some("d1")
      f.users.inTransaction shouldBe 0
    }

    it("needs an account attached to a dealership") {
      (the[AppError] thrownBy Fixture().service.listBays(auth(Role.SYSTEM_MANAGER, dealershipId = None))).status shouldBe 403
    }
  }

  describe("TenantService") {
    final class FakeTenants extends TenantRepository:
      var inserted: Option[NewDealershipRecord] = None
      def listWithCapacity(): Seq[DealershipSummary] = Nil
      def insert(s: DbSession, d: NewDealershipRecord): String =
        inserted = Some(d)
        "d9"

    def onboarding(opening: LocalTime = eight, closing: LocalTime = five) =
      NewDealership("HN-01", "Ha Noi", "1 Street", None, ZoneId.of("Asia/Ho_Chi_Minh"), opening, closing, List(1, 1, 2), 30, 60, 60,
        NewAdmin("Admin", "a@x.io", "secret123"))

    it("creates the dealership and its first admin together") {
      val db = FakeDb()
      val users = FakeUsers(db)
      val tenants = FakeTenants()
      val r = TenantService(db, tenants, IdentityService(db, users, NoSessions, 1)).onboard(onboarding(), Log.silent)
      r shouldBe scheduler.modules.system.application.Onboarded("d9", "u1")
      db.transactions shouldBe 1
      users.inTransaction shouldBe 1
      users.inserted.head.role shouldBe Role.ADMIN
      users.inserted.head.dealershipId shouldBe Some("d9")
      tenants.inserted.get.openDays shouldBe Seq(1, 2)
    }

    it("refuses opening hours that close before they open") {
      val tenants = FakeTenants()
      val db = FakeDb()
      codeOf(TenantService(db, tenants, IdentityService(db, FakeUsers(db), NoSessions, 1)).onboard(onboarding(five, eight), Log.silent)) shouldBe
        "INVALID_HOURS"
      tenants.inserted shouldBe empty
    }
  }

  describe("PackageAdminService") {
    final class FakePackages extends PackageRepository:
      val rows = mutable.Buffer.empty[PackageRecord]
      def listAll(): Seq[PackageRecord] = rows.toSeq
      def insert(p: NewPackageRecord): String =
        rows += PackageRecord(s"k${rows.size + 1}", p.code, p.name, p.description, p.vehicleType, p.durationMinutes, p.requiredSkill, p.bayType,
          p.priceAmount, "VND", p.checklist, p.isRecommended, isActive = true)
        rows.last.id
      def update(id: String, changes: PackageChanges): Option[PackageRecord] =
        rows.find(_.id == id).map(r => r.copy(isActive = changes.isActive.getOrElse(r.isActive)))

    def pkg(vehicle: VehicleType, bayType: String) =
      NewPackage("CAR-OIL", "Oil change", "Change the oil", vehicle, 60, "GENERAL_SERVICE", bayType, 100, List("Oil"), isRecommended = false)

    it("creates a package whose bay type fits the vehicle") {
      val service = PackageAdminService(FakePackages())
      service.create(pkg(VehicleType.CAR, "CAR_LIFT"), Log.silent).vehicleType shouldBe "CAR"
      service.create(pkg(VehicleType.CAR, "DIAGNOSTIC_BAY"), Log.silent).bayType shouldBe "DIAGNOSTIC_BAY"
    }

    it("refuses a bay type that cannot take the vehicle") {
      val packages = FakePackages()
      codeOf(PackageAdminService(packages).create(pkg(VehicleType.CAR, "MOTORBIKE_STAND"), Log.silent)) shouldBe "BAY_NOT_FOR_VEHICLE"
      packages.rows shouldBe empty
    }

    it("answers 404 when updating an unknown package") {
      val noChange = PackageChanges(None, None, None, None, None, None, Some(false))
      (the[AppError] thrownBy PackageAdminService(FakePackages()).update("zz", noChange, Log.silent)).status shouldBe 404
    }
  }

  describe("VehicleService") {
    val bike = Vehicle("v1", VehicleType.MOTORBIKE, "Honda", "Wave", None, "59A-123", None, None)
    object Repo extends VehicleRepository:
      def listByOwner(ownerId: String): Seq[Vehicle] = if ownerId == "u1" then Seq(bike) else Nil
      def findOwned(ownerId: String, vehicleId: String): Option[Vehicle] = Option.when(ownerId == "u1" && vehicleId == "v1")(bike)
      def add(ownerId: String, v: NewVehicle): Vehicle = bike.copy(id = "v2", plate = v.plate)

    it("returns a customer's own vehicle and 404 for anyone else's") {
      val service = VehicleService(Repo)
      service.getOwned("u1", "v1") shouldBe bike
      (the[AppError] thrownBy service.getOwned("u2", "v1")).status shouldBe 404
      service.listMine("u2") shouldBe empty
    }

    it("adds a vehicle") {
      VehicleService(Repo).add("u1", NewVehicle(VehicleType.CAR, "Kia", "Morning", None, "30A-999", None, None), Log.silent).plate shouldBe "30A-999"
    }
  }
