package scheduler.shared

/**
 * Ports for persistence. Application services depend on these two types only, never on
 * JDBC or PostgreSQL.
 *
 * A [[DbSession]] is an open unit of work (a connection, possibly inside a transaction).
 * Use cases only pass it along to the ports they call, so the use case decides where a
 * transaction starts and ends while the adapters decide how SQL is run.
 *
 * Calls nested on the same thread reuse the open session: a `run` inside a `tx` reads
 * inside that transaction, and a `tx` inside a `tx` joins it.
 */
trait DbSession

trait Db:
  /** Runs `f` on a pooled connection in auto-commit mode (plain reads and single writes). */
  def run[T](f: DbSession => T): T

  /**
   * Runs `f` in a transaction: commit on success, rollback on any exception. A deadlock or
   * serialisation failure surfaces as [[TransientStorageError]] (safe to retry: nothing was committed).
   */
  def tx[T](f: DbSession => T): T
