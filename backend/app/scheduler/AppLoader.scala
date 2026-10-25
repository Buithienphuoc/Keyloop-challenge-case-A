package scheduler

import java.time.Instant

import scala.concurrent.Future

import play.api.*
import play.api.ApplicationLoader.Context
import play.api.http.HttpErrorHandler
import play.api.mvc.EssentialFilter
import play.api.routing.Router
import play.filters.cors.{CORSConfig, CORSFilter}

import router.Routes
import scheduler.http.*
import scheduler.modules.admin.adapters.{AdminController, PgWorkshopRepo}
import scheduler.modules.admin.application.WorkshopAdminService
import scheduler.modules.catalog.adapters.{CatalogController, PgCatalogRepo}
import scheduler.modules.consultation.adapters.{ConsultationController, PgConsultationRepo}
import scheduler.modules.consultation.application.ConsultationService
import scheduler.modules.identity.adapters.{IdentityController, PgIdentityRepo}
import scheduler.modules.identity.application.IdentityService
import scheduler.modules.scheduling.adapters.{PgSchedulingStore, SchedulingController}
import scheduler.modules.scheduling.application.*
import scheduler.modules.system.adapters.{PgTenantRepo, SystemController}
import scheduler.modules.system.application.TenantService
import scheduler.modules.vehicles.adapters.{PgVehicleRepo, VehicleController}
import scheduler.modules.vehicles.application.VehicleService
import scheduler.shared.*

/** Play entry point (play.application.loader): compile-time wiring, no runtime DI container. */
final class AppLoader extends ApplicationLoader:
  def load(context: Context): Application =
    LoggerConfigurator(context.environment.classLoader).foreach(_.configure(context.environment, context.initialConfiguration, Map.empty))
    AppComponents(context).application

/**
 * Composition root: the only place that knows which adapter implements which port.
 * Application services receive traits; swapping PostgreSQL for something else (or for
 * in-memory fakes in tests) changes this file only.
 *
 * @param clock injectable "now" so tests can pin time. Sessions always use the real clock,
 *              because PostgreSQL checks their expiry against its own now().
 */
class AppComponents(context: Context, clock: () => Instant = () => Instant.now())
    extends BuiltInComponentsFromContext(context):

  val log: Log = Log()
  val config: Config = Config.from(configuration, environment.rootPath)

  // ---- infrastructure ------------------------------------------------------------------------
  private val embedded: Option[EmbeddedDatabase] =
    if config.databaseUrl.isDefined then None
    else
      log.info("embedded_pg.starting", "reason" -> "DATABASE_URL not set: starting an embedded PostgreSQL for local development")
      Some(EmbeddedDb.start(config.dataDir, config.embeddedPgPort, log))

  val db: PgDb = PgDb(config.databaseUrl.orElse(embedded.map(_.jdbcUrl)).get, log, config.slowQueryMs, config.dbPoolSize)
  Migrations.run(db.dataSource, log)
  if config.seedDemoData then Seed.demoData(db, log, clock())
  Metrics.registerJvmMetrics()

  applicationLifecycle.addStopHook { () =>
    log.info("server.shutting_down")
    db.close()
    embedded.foreach(_.stop())
    Future.unit
  }

  // ---- driven adapters -----------------------------------------------------------------------
  private val catalog = PgCatalogRepo(db)
  private val identityRepo = PgIdentityRepo(db)
  private val store = PgSchedulingStore()

  // ---- application services ------------------------------------------------------------------
  val identity = IdentityService(db, identityRepo, identityRepo, config.sessionTtlHours)
  val vehicles = VehicleService(PgVehicleRepo(db))
  val consultations = ConsultationService(db, PgConsultationRepo(db), vehicles, catalog)
  private val workshop = WorkshopContext(catalog, consultations)
  val availability = AvailabilityService(db, store, workshop, clock)
  val booking = BookingService(db, store, store, store, consultations, vehicles, workshop, availability, clock)
  val appointments = AppointmentService(db, store, store, consultations, workshop, clock)
  val admin = WorkshopAdminService(db, PgWorkshopRepo(db), identity)
  val tenants = TenantService(db, PgTenantRepo(db), identity)

  // ---- driving adapters (HTTP) ---------------------------------------------------------------
  private val guard = Guard(identity, controllerComponents.parsers.defaultBodyParser)

  override lazy val httpErrorHandler: HttpErrorHandler = JsonErrorHandler()

  lazy val router: Router = Routes(
    httpErrorHandler,
    IdentityController(controllerComponents, guard, identity),
    CatalogController(controllerComponents, catalog),
    VehicleController(controllerComponents, guard, vehicles),
    SchedulingController(controllerComponents, guard, availability, booking, appointments),
    ConsultationController(controllerComponents, guard, consultations),
    AdminController(controllerComponents, guard, admin),
    SystemController(controllerComponents, guard, tenants),
    OpsController(controllerComponents, environment, () => db.isUp())
  )

  private val cors = CORSFilter(
    CORSConfig(
      allowedOrigins = CORSConfig.Origins.Matching(config.corsOrigins.toSet),
      exposedHeaders = Seq("X-Request-Id", "Idempotent-Replayed"),
      supportsCredentials = false
    ),
    httpErrorHandler
  )

  // The API filter is outermost so CORS rejections and every other response are logged and tagged.
  lazy val httpFilters: Seq[EssentialFilter] = Seq(ApiFilter(log)(using executionContext), cors)

  log.info("server.ready", "docs" -> "/docs", "embeddedDb" -> embedded.isDefined)
