package scheduler.unit

import java.time.{Instant, LocalTime, OffsetDateTime, ZoneId}

import scala.collection.mutable

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scheduler.modules.catalog.*
import scheduler.modules.consultation.BookableConsultation
import scheduler.modules.identity.{AuthContext, Role}
import scheduler.modules.scheduling.application.*
import scheduler.modules.scheduling.domain.*
import scheduler.modules.vehicles.{OwnedVehicles, Vehicle}
import scheduler.shared.{AppError, Db, DbSession, Log, TransientStorageError}

/**
 * BookingService against in-memory fakes of its ports: no database, no HTTP.
 * This is what depending on traits buys (DIP): the retry and error-mapping rules are
 * tested in milliseconds, and any adapter that honours the port contract can replace
 * PostgreSQL (LSP). The PostgreSQL adapter is covered by the integration tests.
 */
class BookingServiceFakesSpec extends AnyFunSpec with Matchers:
  private val Now = OffsetDateTime.parse("2026-11-02T07:00:00+07:00").toInstant // Monday
  private val Start = "2026-11-03T09:00:00+07:00"
  private val customer = AuthContext("u1", Role.CUSTOMER, None, "C", "c@x")
  private val request = BookRequest("d1", "v1", Start, Some("p1"), None, None)

  private val dealership = Dealership("d1", "D1", "D1", "x", None, ZoneId.of("Asia/Ho_Chi_Minh"), LocalTime.of(8, 0),
    LocalTime.of(17, 30), Seq(1, 2, 3, 4, 5, 6), 30, 60, 60)
  private val pkg = ServicePackage("p1", "P1", "Essential", "", VehicleType.MOTORBIKE, 60, "GENERAL_SERVICE", "MOTORBIKE_STAND",
    Money(1, "VND"), Nil, isRecommended = true)
  private val bike = Vehicle("v1", VehicleType.MOTORBIKE, "Honda", "Wave", None, "X", None, None)

  private def stand(i: Int) = Bay(s"b$i", s"Stand $i", "MOTORBIKE_STAND")
  private def tech(id: String, name: String) =
    Technician(id, name, Seq("GENERAL_SERVICE"), LocalTime.of(8, 0), LocalTime.of(17, 30), Set(1, 2, 3, 4, 5, 6))

  private object Session extends DbSession

  private final class Harness(bays: Seq[Bay], technicians: Seq[Technician], insertFailures: Seq[Throwable], txFailures: Seq[Throwable]):
    val inserted = mutable.Buffer.empty[NewAppointment]
    private val busy = mutable.Buffer.empty[BusyPeriod]
    private val pendingInsertFailures = mutable.Queue.from(insertFailures)
    private val pendingTxFailures = mutable.Queue.from(txFailures)

    private val db = new Db:
      def run[T](f: DbSession => T): T = f(Session)
      def tx[T](f: DbSession => T): T =
        if pendingTxFailures.nonEmpty then throw pendingTxFailures.dequeue()
        f(Session)

    private val calendar = new ResourceCalendar:
      def activeResources(s: DbSession, dealershipId: String) = WorkshopResources(bays, technicians)
      def busyPeriods(s: DbSession, dealershipId: String, from: Instant, to: Instant, asOf: Instant) = busy.toSeq

    private val writer = new AppointmentWriter:
      def insert(s: DbSession, a: NewAppointment): String =
        if pendingInsertFailures.nonEmpty then
          val failure = pendingInsertFailures.dequeue()
          // A "winner" took exactly the pair we chose, as a concurrent request would.
          if failure.isInstanceOf[SlotTakenError] then
            busy += BusyPeriod(a.bayId, a.technicianId, a.start.toEpochMilli, a.end.toEpochMilli)
          throw failure
        inserted += a
        s"a${inserted.size}"
      def findIdByIdempotencyKey(s: DbSession, customerId: String, key: String) = None
      def lockIdempotencyKey(s: DbSession, customerId: String, key: String) = ()
      def lockVehicle(s: DbSession, vehicleId: String) = ()
      def liveAppointmentOf(s: DbSession, vehicleId: String, asOf: Instant) = None
      def lockForUpdate(s: DbSession, id: String) = None
      def setStatus(s: DbSession, id: String, status: AppointmentStatus, cancelReason: Option[String]) = ()
      def recordEvent(s: DbSession, e: AppointmentEvent) = ()
      def expireHolds(s: DbSession, asOf: Instant, scope: HoldScope) = Nil
      def reassign(s: DbSession, id: String, bayId: String, technicianId: String) = ()

    private val reader = new AppointmentReader:
      def get(s: DbSession, id: String) =
        val last = inserted.last
        Some(AppointmentView(id, "SV-1", AppointmentStatus.CONFIRMED, last.start.atZone(dealership.timezone),
          last.end.atZone(dealership.timezone),
          AppointmentView.DealershipRef("d1", "D1", "x", None, "Asia/Ho_Chi_Minh"),
          AppointmentView.VehicleRef("v1", VehicleType.MOTORBIKE, "Honda", "Wave", "X"),
          AppointmentView.CustomerRef("u1", "C", None, "c@x"),
          AppointmentView.ServiceInfo("PACKAGE", "Essential", Some("p1"), None, Nil), None,
          AppointmentView.Ref(last.bayId, ""), AppointmentView.Ref(last.technicianId, ""), None, None, Now))
      def list(s: DbSession, filter: AppointmentFilter) = Nil
      def technicianIdForUser(s: DbSession, userId: String) = None
      def jobDetails(s: DbSession, id: String) = None

    private val catalog = new CatalogReader:
      def listDealerships() = Seq(dealership)
      def getDealership(id: String) = Option.when(id == dealership.id)(dealership)
      def listPackages(vehicleType: Option[VehicleType]) = Seq(pkg)
      def getPackage(id: String) = Option.when(id == pkg.id)(pkg)
      def listSkills() = Nil
      def hasSkill(code: String) = true
      def listBayTypes() = Nil

    private val consultations = new ConsultationBookingPort:
      def forBooking(s: DbSession, id: String): Option[BookableConsultation] = None
      def markBooked(s: DbSession, id: String) = true
      def reopen(s: DbSession, id: String) = ()

    private val vehicles = new OwnedVehicles:
      def getOwned(ownerId: String, vehicleId: String) = bike

    private val workshop = WorkshopContext(catalog, consultations)
    private val clock = () => Now
    private val availability = AvailabilityService(db, calendar, workshop, clock)
    val booking = BookingService(db, calendar, reader, writer, consultations, vehicles, workshop, availability, clock, backoff = _ => ())

    def book(): BookingResult = booking.book(customer, request, None, Log.silent)

  private def harness(
      bays: Seq[Bay] = Seq(stand(1), stand(2)),
      technicians: Seq[Technician] = Seq(tech("t1", "Minh"), tech("t2", "Lan")),
      insertFailures: Seq[Throwable] = Nil,
      txFailures: Seq[Throwable] = Nil
  ) = Harness(bays, technicians, insertFailures, txFailures)

  describe("BookingService with in-memory ports") {
    it("books on the first try when a pair is free") {
      val h = harness()
      h.book().replayed shouldBe false
      h.inserted should have size 1
    }

    it("after losing a race, retries with fresh data and takes the next free pair") {
      val h = harness(insertFailures = Seq(SlotTakenError()))
      h.book()
      h.inserted should have size 1
      h.inserted.head.bayId shouldBe "b2" // b1 + t1 went to the "winner"
    }

    it("retries a deadlock reported by the storage layer") {
      val h = harness(txFailures = Seq(TransientStorageError(RuntimeException("40P01"))))
      h.book()
      h.inserted should have size 1
    }

    it(s"gives up after ${BookingService.MaxAttempts} lost races and answers 409 SLOT_UNAVAILABLE") {
      val h = harness(
        insertFailures = Seq.fill(BookingService.MaxAttempts)(SlotTakenError()),
        bays = (0 until 9).map(stand),
        technicians = (0 until 9).map(i => tech(s"t$i", s"T$i"))
      )
      val e = the[AppError] thrownBy h.book()
      e.code shouldBe "SLOT_UNAVAILABLE"
      h.inserted shouldBe empty
    }

    it("maps an overlapping booking of the same vehicle to 409 VEHICLE_ALREADY_BOOKED without retrying") {
      val h = harness(insertFailures = Seq(VehicleBusyError(), SlotTakenError()))
      (the[AppError] thrownBy h.book()).code shouldBe "VEHICLE_ALREADY_BOOKED"
    }

    it("answers 422 NOT_OFFERED_HERE when the dealership has no bay of the needed type") {
      val h = harness(bays = Seq(Bay("b1", "Lift", "CAR_LIFT")))
      (the[AppError] thrownBy h.book()).code shouldBe "NOT_OFFERED_HERE"
    }

    it("only customers can book for themselves") {
      val h = harness()
      val advisor = customer.copy(role = Role.SERVICE_ADVISOR, dealershipId = Some("d1"))
      (the[AppError] thrownBy h.booking.book(advisor, request, None, Log.silent)).status shouldBe 403
    }
  }
