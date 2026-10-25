package scheduler.support

import anorm.*

import scheduler.modules.identity.Passwords

final case class TestCustomer(token: String, vehicleId: String)
final case class Workshop(dealershipId: String, packageId: String, customers: Seq[TestCustomer])

object Fixtures:
  /**
   * A dealership with exactly the bays and technicians a scenario needs (no booking lead
   * time), plus N customers with a bike and a signed-in session each.
   */
  def workshop(h: Harness, code: String, bays: Int, technicians: Int, customers: Int): Workshop =
    val dealershipId = h.idOf(SQL"""
      INSERT INTO dealerships (code, name, address, timezone, opening_time, closing_time, open_days, min_lead_minutes)
      VALUES ($code, $code, 'Somewhere', 'Asia/Ho_Chi_Minh', '08:00', '17:30', '{1,2,3,4,5,6}', 0) RETURNING id""")
    h.sql {
      for i <- 0 until bays do
        SQL"INSERT INTO service_bays (dealership_id, name, bay_type) VALUES ($dealershipId::uuid, ${s"Stand $i"}, 'MOTORBIKE_STAND')".execute()
      for i <- 0 until technicians do
        val t = SQL"""
          INSERT INTO technicians (dealership_id, display_name, shift_start, shift_end, work_days)
          VALUES ($dealershipId::uuid, ${s"Tech $i"}, '08:00', '17:30', '{1,2,3,4,5,6}') RETURNING id"""
          .as(SqlParser.scalar[java.util.UUID].single)
        SQL"INSERT INTO technician_skills VALUES ($t, 'GENERAL_SERVICE')".execute()
    }
    val packageId = h.idOf(SQL"SELECT id FROM service_packages WHERE code = 'MB-ESSENTIAL'")

    val list = h.sql {
      (0 until customers).map { i =>
        val userId = SQL"""
          INSERT INTO users (email, password_hash, full_name, role)
          VALUES (${s"${code.toLowerCase}-$i@test.local"}, 'x', ${s"Customer $i"}, 'CUSTOMER') RETURNING id"""
          .as(SqlParser.scalar[java.util.UUID].single)
        val vehicleId = SQL"""
          INSERT INTO vehicles (owner_id, vehicle_type, make, model, plate)
          VALUES ($userId, 'MOTORBIKE', 'Honda', 'Wave', ${s"$code-$i"}) RETURNING id"""
          .as(SqlParser.scalar[java.util.UUID].single)
        // Issue sessions directly: hundreds of password hashes would only slow the test down.
        val (token, tokenHash) = Passwords.newSessionToken()
        SQL"INSERT INTO sessions (token_hash, user_id, expires_at) VALUES ($tokenHash, $userId, now() + interval '1 hour')".execute()
        TestCustomer(token, vehicleId.toString)
      }
    }
    Workshop(dealershipId, packageId, list)
