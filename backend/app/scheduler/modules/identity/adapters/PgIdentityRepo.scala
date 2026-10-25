package scheduler.modules.identity.adapters

import java.sql.SQLException
import java.time.Instant

import anorm.*

import scheduler.modules.identity.*
import scheduler.shared.{AppError, Db, DbSession, PgErrors, Sql}
import scheduler.shared.Sql.*

/** PostgreSQL adapter for both identity ports (one class, two narrow interfaces). */
final class PgIdentityRepo(db: Db) extends UserRepository, SessionRepository:

  private val UserSelect = """
    SELECT u.id, u.email, u.password_hash, u.full_name, u.phone, u.role, u.dealership_id, u.is_active,
           d.name AS dealership_name
    FROM users u LEFT JOIN dealerships d ON d.id = u.dealership_id"""

  private val publicUser: RowParser[PublicUser] = Sql.rows { r =>
    PublicUser(
      id = r.uuid("id"),
      email = r.str("email"),
      fullName = r.str("full_name"),
      phone = r.strOpt("phone"),
      role = Role.valueOf(r.str("role")),
      dealershipId = r.uuidOpt("dealership_id"),
      dealershipName = r.strOpt("dealership_name")
    )
  }

  def insert(s: DbSession, u: NewUserRecord): PublicUser =
    given java.sql.Connection = Sql.connection(s)
    try
      val id = SQL"""INSERT INTO users (email, password_hash, full_name, phone, role, dealership_id)
                     VALUES (${u.email}, ${u.passwordHash}, ${u.fullName}, ${u.phone}, ${u.role.toString}, ${u.dealershipId}::uuid)
                     RETURNING id""".as(SqlParser.scalar[java.util.UUID].single).toString
      SQL(s"$UserSelect WHERE u.id = {id}::uuid").on("id" -> id).as(publicUser.single)
    catch
      case e: SQLException if PgErrors.sqlState(e).contains(PgErrors.UniqueViolation) =>
        throw AppError.conflict("EMAIL_TAKEN", "An account with this email already exists")

  def findByEmail(email: String): Option[StoredUser] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL(s"$UserSelect WHERE lower(u.email) = lower({email})").on("email" -> email)
      .as((publicUser ~ Sql.rows(r => (r.str("password_hash"), r.bool("is_active")))).singleOpt)
      .map { case user ~ ((hash, active)) => StoredUser(user, hash, active) }
  }

  def findById(id: String): Option[PublicUser] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL(s"$UserSelect WHERE u.id = {id}::uuid").on("id" -> id).as(publicUser.singleOpt)
  }

  def listByDealership(dealershipId: String): Seq[StaffMember] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL(s"$UserSelect WHERE u.dealership_id = {d}::uuid ORDER BY u.role, u.full_name").on("d" -> dealershipId)
      .as((publicUser ~ Sql.rows(_.bool("is_active"))).*)
      .map { case user ~ active => StaffMember(user, active) }
  }

  def create(userId: String, tokenHash: String, expiresAt: Instant): Unit = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"INSERT INTO sessions (token_hash, user_id, expires_at) VALUES ($tokenHash, $userId::uuid, ${ts(expiresAt)})".executeUpdate()
    ()
  }

  def revoke(tokenHash: String): Boolean = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL"UPDATE sessions SET revoked_at = now() WHERE token_hash = $tokenHash AND revoked_at IS NULL".executeUpdate() == 1
  }

  def findCaller(tokenHash: String): Option[AuthContext] = db.run { s =>
    given java.sql.Connection = Sql.connection(s)
    SQL(s"""$UserSelect
            JOIN sessions s ON s.user_id = u.id
            WHERE s.token_hash = {hash} AND s.revoked_at IS NULL AND s.expires_at > now() AND u.is_active""")
      .on("hash" -> tokenHash)
      .as(publicUser.singleOpt)
      .map(u => AuthContext(u.id, u.role, u.dealershipId, u.fullName, u.email))
  }
