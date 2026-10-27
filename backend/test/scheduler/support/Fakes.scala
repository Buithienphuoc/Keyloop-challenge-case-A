package scheduler.support

import java.time.{Instant, LocalTime, ZoneId, ZonedDateTime}

import scala.collection.mutable

import scheduler.modules.catalog.{BayTypeInfo, CatalogReader, Dealership, Money, ServicePackage, SkillInfo}
import scheduler.modules.consultation.BookableConsultation
import scheduler.modules.identity.{AuthContext, Role}
import scheduler.modules.scheduling.application.*
import scheduler.modules.scheduling.domain.*
import scheduler.shared.{Db, DbSession}

/**
 * In-memory stand-ins for the persistence ports, shared by the unit specs. They keep just
 * enough state to observe what a use case did (status changes, events, reopened requests)
 * and let each test choose what the "database" answers.
 */
object Fakes:
  object Session extends DbSession

  /** Runs every unit of work at once; counts transactions so tests can see where one was opened. */
  final class FakeDb extends Db:
    var transactions = 0
    var inTransaction = false
    def run[T](f: DbSession => T): T = f(Session)
    def tx[T](f: DbSession => T): T =
      transactions += 1
      inTransaction = true
      try f(Session)
      finally inTransaction = false

  val Zone: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")

  val dealership: Dealership = Dealership("d1", "D1", "Dealer One", "1 Street", None, Zone, LocalTime.of(8, 0), LocalTime.of(17, 30),
    Seq(1, 2, 3, 4, 5, 6), 30, 60, 60)

  val bikePackage: ServicePackage = ServicePackage("p1", "MB-ESSENTIAL", "Essential", "", VehicleType.MOTORBIKE, 60, "GENERAL_SERVICE",
    "MOTORBIKE_STAND", Money(1, "VND"), Nil, isRecommended = true)

  def auth(role: Role, userId: String = "u1", dealershipId: Option[String] = Some("d1")): AuthContext =
    AuthContext(userId, role, if role == Role.CUSTOMER then None else dealershipId, "Name", "x@y.z")

  val customer: AuthContext = auth(Role.CUSTOMER)

  final class FakeCatalog(
      dealerships: Seq[Dealership] = Seq(dealership),
      packages: Seq[ServicePackage] = Seq(bikePackage),
      skills: Set[String] = Set("GENERAL_SERVICE", "ENGINE")
  ) extends CatalogReader:
    def listDealerships(): Seq[Dealership] = dealerships
    def getDealership(id: String): Option[Dealership] = dealerships.find(_.id == id)
    def listPackages(vehicleType: Option[VehicleType]): Seq[ServicePackage] = packages.filter(p => vehicleType.forall(_ == p.vehicleType))
    def getPackage(id: String): Option[ServicePackage] = packages.find(_.id == id)
    def listSkills(): Seq[SkillInfo] = skills.toSeq.map(c => SkillInfo(c, c))
    def hasSkill(code: String): Boolean = skills.contains(code)
    def listBayTypes(): Seq[BayTypeInfo] = Nil

  /** Remembers which consultation requests the booking side reopened or marked booked. */
  final class FakeConsultations(requests: Map[String, BookableConsultation] = Map.empty, bookable: Boolean = true)
      extends ConsultationBookingPort:
    val reopened = mutable.Buffer.empty[String]
    val booked = mutable.Buffer.empty[String]
    def forBooking(s: DbSession, id: String): Option[BookableConsultation] = requests.get(id)
    def markBooked(s: DbSession, id: String): Boolean =
      booked += id
      bookable
    def reopen(s: DbSession, id: String): Unit = reopened += id

  def view(id: String, status: AppointmentStatus, start: Instant, customerId: String = "u1", dealershipId: String = "d1",
      technicianId: String = "t1", bayId: String = "b1"): AppointmentView =
    val zoned: ZonedDateTime = start.atZone(Zone)
    AppointmentView(id, "SV-1", status, zoned, zoned.plusHours(1),
      AppointmentView.DealershipRef(dealershipId, "D1", "x", None, Zone.getId),
      AppointmentView.VehicleRef("v1", VehicleType.MOTORBIKE, "Honda", "Wave", "X"),
      AppointmentView.CustomerRef(customerId, "C", None, "c@x"),
      AppointmentView.ServiceInfo("PACKAGE", "Essential", Some("p1"), None, Nil), None,
      AppointmentView.Ref(bayId, ""), AppointmentView.Ref(technicianId, ""), None, None, start)

  /**
   * One appointment table in memory. `lockForUpdate`, `setStatus` and `reassign` act on
   * `rows`; everything written is recorded so specs can assert on it.
   */
  final class FakeStore(
      var bays: Seq[Bay] = Nil,
      var technicians: Seq[Technician] = Nil,
      var busy: Seq[BusyPeriod] = Nil,
      technicianUsers: Map[String, String] = Map.empty, // user id -> technician id
      var expiredHolds: Seq[ExpiredHold] = Nil,
      var reassignFails: Boolean = false
  ) extends ResourceCalendar, AppointmentReader, AppointmentWriter:
    val rows = mutable.Map.empty[String, LockedAppointment]
    val jobs = mutable.Map.empty[String, JobDetails]
    val statusChanges = mutable.Buffer.empty[(String, AppointmentStatus, Option[String])]
    val events = mutable.Buffer.empty[AppointmentEvent]
    val reassigned = mutable.Buffer.empty[(String, String, String)]
    val holdScopes = mutable.Buffer.empty[HoldScope]
    val busyQueries = mutable.Buffer.empty[(Instant, Instant)]
    var lastFilter: Option[AppointmentFilter] = None

    def add(a: LockedAppointment): FakeStore =
      rows(a.id) = a
      this

    // ResourceCalendar
    def activeResources(s: DbSession, dealershipId: String): WorkshopResources = WorkshopResources(bays, technicians)
    def busyPeriods(s: DbSession, dealershipId: String, from: Instant, to: Instant, asOf: Instant): Seq[BusyPeriod] =
      busyQueries += (from -> to)
      busy

    // AppointmentReader
    def get(s: DbSession, id: String): Option[AppointmentView] =
      rows.get(id).map(a => view(a.id, a.status, a.start, a.customerId, a.dealershipId, a.technicianId))
    def list(s: DbSession, filter: AppointmentFilter): Seq[AppointmentView] =
      lastFilter = Some(filter)
      Nil
    def technicianIdForUser(s: DbSession, userId: String): Option[String] = technicianUsers.get(userId)
    def jobDetails(s: DbSession, id: String): Option[JobDetails] = jobs.get(id)

    // AppointmentWriter
    def insert(s: DbSession, a: NewAppointment): String = throw UnsupportedOperationException("not used here")
    def findIdByIdempotencyKey(s: DbSession, customerId: String, key: String): Option[String] = None
    def lockIdempotencyKey(s: DbSession, customerId: String, key: String): Unit = ()
    def lockVehicle(s: DbSession, vehicleId: String): Unit = ()
    def liveAppointmentOf(s: DbSession, vehicleId: String, asOf: Instant): Option[LiveAppointment] = None
    def lockForUpdate(s: DbSession, id: String): Option[LockedAppointment] = rows.get(id)
    def setStatus(s: DbSession, id: String, status: AppointmentStatus, cancelReason: Option[String]): Unit =
      statusChanges += ((id, status, cancelReason))
      rows.updateWith(id)(_.map(_.copy(status = status)))
    def recordEvent(s: DbSession, e: AppointmentEvent): Unit = events += e
    def expireHolds(s: DbSession, asOf: Instant, scope: HoldScope): Seq[ExpiredHold] =
      holdScopes += scope
      expiredHolds
    def reassign(s: DbSession, id: String, bayId: String, technicianId: String): Unit =
      if reassignFails then throw SlotTakenError()
      reassigned += ((id, bayId, technicianId))
      jobs.updateWith(id)(_.map(_.copy(bayId = bayId, technicianId = technicianId)))
