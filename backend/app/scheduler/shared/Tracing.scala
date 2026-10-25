package scheduler.shared

import scala.jdk.CollectionConverters.*

import io.opentelemetry.api.trace.{Span, StatusCode, Tracer}
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk

/**
 * OpenTelemetry tracing. Spans are always created (cheap); they are only exported when the
 * standard OTEL_* environment variables say where, for example
 * OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4317. Without them nothing leaves the process.
 *
 * One SDK per JVM: the test suites start several applications in the same process.
 */
object Tracing:
  private lazy val sdk: OpenTelemetrySdk =
    val exporting = sys.env.contains("OTEL_EXPORTER_OTLP_ENDPOINT") || sys.env.contains("OTEL_TRACES_EXPORTER")
    AutoConfiguredOpenTelemetrySdk.builder()
      // Defaults only: OTEL_* environment variables and system properties override them.
      .addPropertiesSupplier(() =>
        Map(
          "otel.service.name" -> "service-scheduler",
          "otel.traces.exporter" -> (if exporting then "otlp" else "none"),
          "otel.metrics.exporter" -> "none", // metrics are served to Prometheus on /metrics
          "otel.logs.exporter" -> "none" // logs are JSON lines on stdout
        ).asJava
      )
      .build()
      .getOpenTelemetrySdk

  lazy val tracer: Tracer = sdk.getTracer("scheduler")
  def propagator: TextMapPropagator = sdk.getPropagators.getTextMapPropagator

  /** The trace ID of the current span, when there is one (for log lines and response headers). */
  def currentTraceId: Option[String] = Option(Span.current().getSpanContext).filter(_.isValid).map(_.getTraceId)

  /**
   * Runs `f` in a child span of the current one. Outside a traced request it just runs `f`,
   * so background work and tests do not create orphan traces.
   */
  def span[T](name: String, attributes: (String, String)*)(f: => T): T =
    if !Span.current().getSpanContext.isValid then f
    else
      val span = tracer.spanBuilder(name).setParent(Context.current()).startSpan()
      attributes.foreach((k, v) => span.setAttribute(k, v))
      val scope = span.makeCurrent()
      try f
      catch
        case e: Throwable =>
          span.recordException(e)
          span.setStatus(StatusCode.ERROR)
          throw e
      finally
        scope.close()
        span.end()
