package scheduler.integration

import java.sql.{Connection, DriverManager}

import scala.util.Using

import org.flywaydb.core.Flyway
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scheduler.support.TestDatabase

/**
 * V4 on a database that already holds V3 data, as production does: existing service jobs keep
 * every column and count as already assigned to their technician (they are not suddenly open).
 */
class MigrationV4Spec extends AnyFunSpec with Matchers:
  private def flyway(url: String, target: String) =
    Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").target(target).load()

  private def exec(c: Connection, sql: String): Unit = Using.resource(c.createStatement())(_.execute(sql))
  private def one(c: Connection, sql: String): String =
    Using.resource(c.createStatement().executeQuery(sql)) { rs => rs.next(); rs.getString(1) }

  it("keeps existing jobs and marks them assigned, and adds an empty advisor_appointments table") {
    val url = TestDatabase.fresh()
    flyway(url, "3").migrate()
    Using.resource(DriverManager.getConnection(url)) { c =>
      exec(c, """
        INSERT INTO skills VALUES ('GENERAL_SERVICE', 'General');
        INSERT INTO bay_types VALUES ('MOTORBIKE_STAND', 'Stand', '{MOTORBIKE}');
        INSERT INTO dealerships (id, code, name, address, timezone, opening_time, closing_time, open_days)
          VALUES ('00000000-0000-0000-0000-00000000000d', 'D', 'D', 'x', 'Asia/Ho_Chi_Minh', '08:00', '17:00', '{1,2,3,4,5,6}');
        INSERT INTO users (id, email, password_hash, full_name, role)
          VALUES ('00000000-0000-0000-0000-00000000000c', 'c@x.vn', 'x', 'C', 'CUSTOMER');
        INSERT INTO vehicles (id, owner_id, vehicle_type, make, model, plate)
          VALUES ('00000000-0000-0000-0000-00000000000e', '00000000-0000-0000-0000-00000000000c', 'MOTORBIKE', 'Honda', 'Wave', 'P1');
        INSERT INTO service_bays (id, dealership_id, name, bay_type)
          VALUES ('00000000-0000-0000-0000-00000000000b', '00000000-0000-0000-0000-00000000000d', 'S1', 'MOTORBIKE_STAND');
        INSERT INTO technicians (id, dealership_id, display_name, shift_start, shift_end, work_days)
          VALUES ('00000000-0000-0000-0000-00000000000f', '00000000-0000-0000-0000-00000000000d', 'T', '08:00', '17:00', '{1,2,3,4,5,6}');
        INSERT INTO service_packages (id, code, name, description, vehicle_type, duration_minutes, required_skill, bay_type,
            price_amount, checklist)
          VALUES ('00000000-0000-0000-0000-0000000000aa', 'P', 'P', 'p', 'MOTORBIKE', 60, 'GENERAL_SERVICE', 'MOTORBIKE_STAND', 1, '{}');
        INSERT INTO appointments (id, reference, dealership_id, customer_id, vehicle_id, service_package_id, bay_id, technician_id,
            period, status, created_by, created_at)
          VALUES ('00000000-0000-0000-0000-000000000001', 'SV-OLD', '00000000-0000-0000-0000-00000000000d',
            '00000000-0000-0000-0000-00000000000c', '00000000-0000-0000-0000-00000000000e', '00000000-0000-0000-0000-0000000000aa',
            '00000000-0000-0000-0000-00000000000b', '00000000-0000-0000-0000-00000000000f',
            tstzrange('2026-11-03 02:00+00', '2026-11-03 03:00+00', '[)'), 'CONFIRMED',
            '00000000-0000-0000-0000-00000000000c', '2026-10-01 00:00+00');
      """)
    }

    flyway(url, "latest").migrate().migrationsExecuted shouldBe 1

    Using.resource(DriverManager.getConnection(url)) { c =>
      one(c, "SELECT count(*) FROM appointments") shouldBe "1"
      one(c, "SELECT reference || ' ' || status || ' ' || technician_id FROM appointments") shouldBe
        "SV-OLD CONFIRMED 00000000-0000-0000-0000-00000000000f"
      one(c, "SELECT technician_assigned_at = created_at FROM appointments") shouldBe "t"
      one(c, "SELECT count(*) FROM advisor_appointments") shouldBe "0"
    }
  }
