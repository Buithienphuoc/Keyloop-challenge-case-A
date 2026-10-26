package scheduler.modules.scheduling.application

import java.time.Instant

import scheduler.shared.{Db, Log, Metrics}

/**
 * Releases holds that ran out and were never confirmed. Booking already releases the holds
 * in its way before it allocates, so this only keeps the data tidy (and reopens consultation
 * requests whose hold lapsed). Safe to run on every API instance at once: rows another
 * instance is releasing are skipped, not waited for.
 */
final class HoldSweeper(db: Db, writer: AppointmentWriter, consultations: ConsultationBookingPort, clock: () => Instant, log: Log):

  /** Returns how many holds were released. */
  def sweep(): Int =
    val expired = db.tx { s =>
      val released = writer.expireHolds(s, clock(), HoldScope.Everywhere)
      released.flatMap(_.consultationRequestId).foreach(consultations.reopen(s, _))
      released
    }
    if expired.nonEmpty then
      Metrics.holds.labelValues("expired").inc(expired.size.toDouble)
      log.info("holds.expired", "count" -> expired.size, "appointmentIds" -> expired.map(_.appointmentId))
    expired.size
