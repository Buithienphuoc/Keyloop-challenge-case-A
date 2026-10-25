package scheduler.shared

import java.sql.{Connection, PreparedStatement}
import java.time.{Instant, LocalTime}
import java.util.UUID

import anorm.{Column, MetaDataItem, Row, RowParser, Success, ToStatement, TypeDoesNotMatch}

/**
 * Small helpers shared by the PostgreSQL adapters (Anorm). Only adapters import this file.
 *
 * Rows are mapped with `Sql.rows { r => ... }`, reading columns by name, which keeps each
 * mapping next to the SELECT it belongs to and readable without Anorm's parser combinators.
 */
object Sql:
  def connection(s: DbSession): Connection = s match
    case j: JdbcSession => j.connection
    case other          => throw IllegalArgumentException(s"Not a JDBC session: $other")

  def rows[T](f: Row => T): RowParser[T] = RowParser(row => Success(f(row)))

  extension (r: Row)
    def str(col: String): String = r[String](col)
    def strOpt(col: String): Option[String] = r[Option[String]](col)
    def uuid(col: String): String = r[UUID](col).toString
    def uuidOpt(col: String): Option[String] = r[Option[UUID]](col).map(_.toString)
    def int(col: String): Int = r[Int](col)
    def intOpt(col: String): Option[Int] = r[Option[Int]](col)
    def bool(col: String): Boolean = r[Boolean](col)
    def instant(col: String): Instant = r[Instant](col)
    def instantOpt(col: String): Option[Instant] = r[Option[Instant]](col)
    def time(col: String): LocalTime = r[LocalTime](col)
    def strings(col: String): List[String] = r[Option[List[String]]](col).getOrElse(Nil)
    def ints(col: String): List[Int] = r[List[Int]](col)

  /** PostgreSQL `time` columns as LocalTime (Anorm has no built-in mapping for them). */
  given Column[LocalTime] = Column.nonNull { (value, meta) =>
    val MetaDataItem(qualified, _, _) = meta
    value match
      case t: java.sql.Time => Right(t.toLocalTime)
      case t: LocalTime     => Right(t)
      case s: String        => Right(LocalTime.parse(s))
      case other            => Left(TypeDoesNotMatch(s"Cannot convert $other: ${other.getClass} to LocalTime for column $qualified"))
  }

  /** java.sql.Timestamp parameter for an instant, so `tstzrange(?, ?)` receives a proper timestamp. */
  def ts(i: Instant): java.sql.Timestamp = java.sql.Timestamp.from(i)

  /** A PostgreSQL `text[]` parameter. */
  final case class TextArray(values: Seq[String])

  /** A PostgreSQL integer array parameter (also fits `smallint[]` columns). */
  final case class IntArray(values: Seq[Int])

  def textArray(values: Seq[String]): TextArray = TextArray(values)
  def intArray(values: Seq[Int]): IntArray = IntArray(values)

  given ToStatement[TextArray] = (ps: PreparedStatement, i: Int, v: TextArray) =>
    ps.setArray(i, ps.getConnection.createArrayOf("text", v.values.toArray[AnyRef]))

  given ToStatement[IntArray] = (ps: PreparedStatement, i: Int, v: IntArray) =>
    ps.setArray(i, ps.getConnection.createArrayOf("int4", v.values.map(Int.box).toArray[AnyRef]))
