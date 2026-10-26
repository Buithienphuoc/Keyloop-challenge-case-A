package scheduler.shared

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

import io.prometheus.metrics.core.metrics.{Counter, Histogram}
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics
import io.prometheus.metrics.model.registry.PrometheusRegistry

/**
 * Prometheus metrics, exposed on GET /metrics.
 * Technical metrics tell us the service is healthy; business metrics tell us the
 * scheduler is doing its job (bookings made, conflicts hit, retries needed).
 */
object Metrics:
  val registry: PrometheusRegistry = PrometheusRegistry.defaultRegistry

  val httpDuration: Histogram = Histogram.builder()
    .name("http_request_duration_seconds")
    .help("HTTP request duration by route and status")
    .labelNames("method", "route", "status_code")
    .classicUpperBounds(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5)
    .register(registry)

  val dbQueryDuration: Histogram = Histogram.builder()
    .name("db_query_duration_seconds")
    .help("Database query duration")
    .classicUpperBounds(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1)
    .register(registry)

  val bookingsCreated: Counter = Counter.builder()
    .name("bookings_created_total")
    .help("Appointments successfully booked")
    .labelNames("dealership", "source")
    .register(registry)

  val bookingConflicts: Counter = Counter.builder()
    .name("booking_conflicts_total")
    .help(
      "Booking attempts that hit a conflict. reason=race: lost a concurrent race and retried; " +
        "reason=deadlock: PostgreSQL broke a lock cycle and the attempt was retried; reason=unavailable: no bay/technician left"
    )
    .labelNames("reason")
    .register(registry)

  val bookingIdempotentReplays: Counter = Counter.builder()
    .name("booking_idempotent_replays_total")
    .help("Repeated booking requests answered from an earlier result (same Idempotency-Key)")
    .register(registry)

  val holds: Counter = Counter.builder()
    .name("booking_holds_total")
    .help("Temporary holds by event: created, confirmed, released (cancelled by the customer), expired")
    .labelNames("event")
    .register(registry)

  val reassignments: Counter = Counter.builder()
    .name("appointment_reassignments_total")
    .help("Jobs moved to another bay or technician by a workshop controller")
    .register(registry)

  val partOrders: Counter = Counter.builder()
    .name("part_orders_total")
    .help("Parts order events: created, or the status an order moved to")
    .labelNames("event")
    .register(registry)

  val appointmentStatusChanges: Counter = Counter.builder()
    .name("appointment_status_changes_total")
    .help("Appointment lifecycle transitions")
    .labelNames("to")
    .register(registry)

  val consultations: Counter = Counter.builder()
    .name("consultation_requests_total")
    .help("Consultation requests by event")
    .labelNames("kind", "event")
    .register(registry)

  val authFailures: Counter = Counter.builder()
    .name("auth_login_failures_total")
    .help("Failed login attempts")
    .register(registry)

  private val jvmRegistered = AtomicBoolean(false)

  /** JVM metrics (memory, GC, threads) once per process. */
  def registerJvmMetrics(): Unit =
    if jvmRegistered.compareAndSet(false, true) then JvmMetrics.builder().register(registry)

  val contentType = "text/plain; version=0.0.4; charset=utf-8"

  def scrape(): String =
    val out = ByteArrayOutputStream()
    PrometheusTextFormatWriter.create().write(out, registry.scrape())
    out.toString("UTF-8")
