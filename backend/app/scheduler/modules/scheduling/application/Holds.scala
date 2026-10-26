package scheduler.modules.scheduling.application

import java.time.Instant

import scheduler.shared.{DbSession, Metrics}

/**
 * Releasing holds that ran out, the same way wherever it happens (before booking, before a job
 * move, in the background sweeper): mark them EXPIRED, reopen their consultation requests and
 * count them. Runs in the caller's transaction.
 */
object Holds:
  def releaseExpired(
      s: DbSession,
      writer: AppointmentWriter,
      consultations: ConsultationBookingPort,
      asOf: Instant,
      scope: HoldScope
  ): Seq[ExpiredHold] =
    val released = writer.expireHolds(s, asOf, scope)
    released.flatMap(_.consultationRequestId).foreach(consultations.reopen(s, _))
    if released.nonEmpty then Metrics.holds.labelValues("expired").inc(released.size.toDouble)
    released
