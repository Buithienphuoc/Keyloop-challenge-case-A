package scheduler

import java.sql.Connection
import java.time.{DayOfWeek, Instant, ZoneId}
import java.util.UUID

import anorm.*

import scheduler.modules.identity.Passwords
import scheduler.modules.scheduling.adapters.PgSchedulingStore
import scheduler.modules.scheduling.application.{AppointmentEvent, NewAppointment}
import scheduler.modules.scheduling.domain.{AppointmentStatus, Availability, Requirement}
import scheduler.shared.{Config, Db, DbSession, Ids, Log, Profile, Sql}
import scheduler.shared.Sql.{*, given}

/**
 * Demo data so the app is usable straight after `sbt run`. In the dev profile every account uses
 * the password documented in the README; in prod each one gets its own from the configuration.
 */
object Seed:
  val DemoPassword = "Demo@2026"

  /** Stored for an account that has no password: not a valid hash, so no password signs in. */
  val Locked = "locked"

  /** Demo account email -> the password hash to store when the account is created. */
  def passwordsFor(config: Config): String => String = config.profile match
    case Profile.Dev =>
      val demo = Passwords.hash(DemoPassword)
      _ => demo
    case Profile.Prod =>
      val hashes = config.seedAccountPasswords.map { (email, password) =>
        require(password != DemoPassword, s"SEED_ACCOUNT_PASSWORDS: $email uses the public demo password")
        email -> (if password.startsWith("pbkdf2-sha512$") then password else Passwords.hash(password))
      }
      email => hashes.getOrElse(email.toLowerCase, Locked)
  private val Zone = ZoneId.of("Asia/Ho_Chi_Minh")
  private val MonSat = Seq(1, 2, 3, 4, 5, 6)

  private val Skills = Seq(
    "GENERAL_SERVICE" -> "General servicing",
    "ENGINE" -> "Engine & drivetrain",
    "ELECTRICAL" -> "Electrical & diagnostics",
    "BRAKES_SUSPENSION" -> "Brakes & suspension",
    "CUSTOMISATION" -> "Customisation & performance"
  )

  private val BayTypes = Seq(
    ("MOTORBIKE_STAND", "Motorbike stand", Seq("MOTORBIKE")),
    ("CAR_LIFT", "Car lift", Seq("CAR")),
    ("DIAGNOSTIC_BAY", "Diagnostic bay", Seq("MOTORBIKE", "CAR"))
  )

  private final case class PackageSeed(
      code: String,
      name: String,
      description: String,
      vehicleType: String,
      duration: Int,
      skill: String,
      bayType: String,
      price: Int,
      checklist: Seq[String],
      recommended: Boolean = false
  )

  private val EssentialMb = Seq(
    "Engine oil change",
    "Chain clean, lube and tension",
    "Tyre pressure and tread",
    "Front and rear brakes",
    "Lights, horn and indicators",
    "Battery voltage test"
  )
  private val MinorCar = Seq(
    "Engine oil and filter",
    "Fluid levels top-up",
    "Tyre rotation and pressure",
    "Brake inspection",
    "Lights and wipers",
    "Battery test",
    "OBD fault-code scan"
  )

  private val Packages = Seq(
    PackageSeed("MB-ESSENTIAL", "Essential maintenance",
      "Our most popular check-up. Ideal every 2,000–3,000 km or every 3 months.",
      "MOTORBIKE", 60, "GENERAL_SERVICE", "MOTORBIKE_STAND", 250_000, EssentialMb, recommended = true),
    PackageSeed("MB-FULL", "Full periodic service",
      "Everything in Essential, plus filters, spark plug and fluids. Ideal every 10,000 km or once a year.",
      "MOTORBIKE", 120, "GENERAL_SERVICE", "MOTORBIKE_STAND", 690_000,
      EssentialMb ++ Seq("Air filter replacement", "Spark plug check", "Brake fluid and coolant top-up",
        "Throttle and clutch cable adjustment", "Fuel injector cleaning", "Test ride")),
    PackageSeed("MB-TRIP", "Pre-trip safety check", "A quick 30-minute check before a long ride.",
      "MOTORBIKE", 30, "GENERAL_SERVICE", "MOTORBIKE_STAND", 100_000,
      Seq("Brakes", "Tyres", "Lights", "Chain", "Fluid levels", "Mirrors")),
    PackageSeed("MB-BRAKES", "Brake service", "Pads, fluid and discs checked by a brake specialist.",
      "MOTORBIKE", 60, "BRAKES_SUSPENSION", "MOTORBIKE_STAND", 350_000,
      Seq("Brake pads / shoes inspection", "Brake fluid replacement", "Disc and drum check", "Brake light switch test")),
    PackageSeed("CAR-MINOR", "Minor service", "The regular check-up for your car. Ideal every 10,000 km or 6 months.",
      "CAR", 90, "GENERAL_SERVICE", "CAR_LIFT", 1_200_000, MinorCar, recommended = true),
    PackageSeed("CAR-MAJOR", "Major service", "A thorough yearly service including filters, plugs and coolant.",
      "CAR", 180, "ENGINE", "CAR_LIFT", 3_500_000,
      MinorCar ++ Seq("Air and cabin filters", "Spark plugs", "Coolant flush", "Brake fluid replacement",
        "Belts and hoses check", "Road test")),
    PackageSeed("CAR-AIRCON", "Air-con check", "Cooling performance test, leak check and refill quote.",
      "CAR", 60, "ELECTRICAL", "DIAGNOSTIC_BAY", 450_000,
      Seq("Cooling performance test", "Refrigerant pressure", "Leak detection", "Cabin filter check"))
  )

  /**
   * prod: makes each account named in SEED_ACCOUNT_PASSWORDS use its configured password on every
   * start, so new passwords take effect on the next rollout without a new database. An account
   * whose password changes is signed out everywhere. Returns how many accounts changed.
   */
  def applyPasswords(db: Db, log: Log, passwords: Map[String, String]): Int =
    def isHash(value: String) = value.startsWith("pbkdf2-sha512$")
    val changed = db.tx { s =>
      given Connection = Sql.connection(s)
      passwords.toSeq.count { (email, password) =>
        SQL"SELECT id::text AS id, password_hash FROM users WHERE lower(email) = $email"
          .as((SqlParser.str("id") ~ SqlParser.str("password_hash")).singleOpt) match
          case Some(id ~ stored) if !(if isHash(password) then password == stored else Passwords.verify(password, stored)) =>
            val hash = if isHash(password) then password else Passwords.hash(password)
            SQL"UPDATE users SET password_hash = $hash WHERE id = $id::uuid".executeUpdate()
            SQL"UPDATE sessions SET revoked_at = now() WHERE user_id = $id::uuid AND revoked_at IS NULL".executeUpdate()
            true
          case _ => false
      }
    }
    if changed > 0 then log.info("seed.passwords_changed", "accounts" -> changed)
    changed

  /**
   * Idempotent: loads the demo data only if no dealership exists, then adds the demo data of
   * later releases (new roles, parts) where it is missing, so an existing database is upgraded
   * too. Several API instances may start at once: an advisory lock lets one seed at a time.
   * Returns whether the base data was loaded.
   */
  def demoData(db: Db, log: Log, pw: String => String, now: Instant = Instant.now()): Boolean =
    val (loaded, added) = db.tx { s =>
      given Connection = Sql.connection(s)
      SQL"SELECT pg_advisory_xact_lock(hashtextextended('scheduler:seed', 0))".execute()
      val empty = SQL"SELECT 1 FROM dealerships LIMIT 1".as(SqlParser.scalar[Int].singleOpt).isEmpty
      if empty then load(s, pw, now)
      (empty, topUp(s, pw))
    }
    if loaded then log.info("seed.demo_data_loaded")
    if added > 0 then log.info("seed.demo_data_topped_up", "rowsAdded" -> added)
    loaded

  /** Demo accounts and parts added after the first release. Inserts only what is missing. */
  private def topUp(s: DbSession, pw: String => String): Int =
    given Connection = Sql.connection(s)
    val dealerships = SQL"SELECT code, id FROM dealerships WHERE code IN ('HCM-D1', 'HCM-TD')"
      .as(Sql.rows(r => r.str("code") -> r.uuid("id")).*).toMap
    def user(email: String, fullName: String, role: String, dealershipId: String): Int =
      SQL"""INSERT INTO users (email, password_hash, full_name, role, dealership_id)
            VALUES ($email, ${pw(email)}, $fullName, $role, $dealershipId::uuid) ON CONFLICT ((lower(email))) DO NOTHING""".executeUpdate()
    def part(dealershipId: String, sku: String, name: String, price: Int, stock: Int): Int =
      SQL"""INSERT INTO parts (dealership_id, sku, name, unit_price, stock_qty)
            VALUES ($dealershipId::uuid, $sku, $name, $price, $stock) ON CONFLICT (dealership_id, sku) DO NOTHING""".executeUpdate()

    val counts = dealerships.toSeq.flatMap { (code, id) =>
      val suffix = code.stripPrefix("HCM-").toLowerCase
      Seq(
        user(s"controller.$suffix@demo.vn", if suffix == "d1" then "Hoàng Văn Nam" else "Trịnh Thu Trang", "WORKSHOP_CONTROLLER", id),
        user(s"parts.$suffix@demo.vn", if suffix == "d1" then "Mai Xuân Phúc" else "Đinh Thị Hoa", "PARTS_STAFF", id)
      ) ++ Parts.map((sku, name, price, stock) => part(id, sku, name, price, stock))
    }
    counts.sum

  private val Parts = Seq(
    ("OIL-10W40-1L", "Engine oil 10W-40, 1 L", 145_000, 40),
    ("OIL-FILTER-MB", "Oil filter (motorbike)", 65_000, 25),
    ("SPARK-NGK-CPR8", "Spark plug NGK CPR8EA-9", 85_000, 30),
    ("BRAKE-PAD-F-MB", "Front brake pads (motorbike)", 180_000, 12),
    ("CHAIN-KIT-428", "Chain and sprocket kit 428", 650_000, 3),
    ("AIR-FILTER-AB", "Air filter Honda Air Blade", 120_000, 8),
    ("BATTERY-12V5", "Battery 12 V 5 Ah", 520_000, 4),
    ("LED-HEAD-H4", "LED headlight bulb H4 (E-marked)", 390_000, 2),
    ("EXHAUST-SLIPON", "Slip-on exhaust, homologated", 3_200_000, 0),
    ("CAR-OIL-FILTER", "Oil filter (car)", 210_000, 10)
  )

  private def load(s: DbSession, pw: String => String, now: Instant): Unit =
    given Connection = Sql.connection(s)
    def id(sql: SimpleSql[Row]): String = sql.as(SqlParser.scalar[UUID].single).toString

    for (code, name) <- Skills do SQL"INSERT INTO skills VALUES ($code, $name)".execute()
    for (code, name, types) <- BayTypes do SQL"INSERT INTO bay_types VALUES ($code, $name, ${textArray(types)})".execute()

    for (p, i) <- Packages.zipWithIndex do
      SQL"""
        INSERT INTO service_packages (code, name, description, vehicle_type, duration_minutes, required_skill, bay_type,
          price_amount, checklist, is_recommended, sort_order)
        VALUES (${p.code}, ${p.name}, ${p.description}, ${p.vehicleType}, ${p.duration}, ${p.skill}, ${p.bayType},
          ${p.price}, ${textArray(p.checklist)}, ${p.recommended}, $i)""".execute()

    def dealership(code: String, name: String, address: String, phone: String, open: String, close: String, days: Seq[Int]) =
      id(SQL"""
        INSERT INTO dealerships (code, name, address, phone, timezone, opening_time, closing_time, open_days)
        VALUES ($code, $name, $address, $phone, 'Asia/Ho_Chi_Minh', $open::time, $close::time, ${intArray(days)})
        RETURNING id""")

    val d1 = dealership("HCM-D1", "Saigon Moto Care – District 1", "123 Nguyễn Thị Minh Khai, District 1, Ho Chi Minh City",
      "028 3822 1234", "08:00", "17:30", MonSat)
    val td = dealership("HCM-TD", "Riverside Auto & Moto – Thu Duc", "45 Võ Văn Ngân, Thu Duc City, Ho Chi Minh City",
      "028 3896 5678", "07:30", "18:00", 1 to 7)

    for (name, bayType) <- Seq("Stand A" -> "MOTORBIKE_STAND", "Stand B" -> "MOTORBIKE_STAND", "Stand C" -> "MOTORBIKE_STAND",
        "Diagnostic 1" -> "DIAGNOSTIC_BAY")
    do SQL"INSERT INTO service_bays (dealership_id, name, bay_type) VALUES ($d1::uuid, $name, $bayType)".execute()
    for (name, bayType) <- Seq("Lift 1" -> "CAR_LIFT", "Lift 2" -> "CAR_LIFT", "Stand 1" -> "MOTORBIKE_STAND",
        "Stand 2" -> "MOTORBIKE_STAND", "Diagnostic" -> "DIAGNOSTIC_BAY")
    do SQL"INSERT INTO service_bays (dealership_id, name, bay_type) VALUES ($td::uuid, $name, $bayType)".execute()

    def user(email: String, fullName: String, role: String, dealershipId: Option[String], phone: Option[String] = None) =
      id(SQL"""
        INSERT INTO users (email, password_hash, full_name, role, dealership_id, phone)
        VALUES ($email, ${pw(email)}, $fullName, $role, CAST(${dealershipId} AS uuid), $phone) RETURNING id""")

    user("sysadmin@demo.vn", "Platform Manager", "SYSTEM_MANAGER", None)
    user("admin.d1@demo.vn", "Đặng Thu Hà", "ADMIN", Some(d1))
    val advisorD1 = user("advisor.d1@demo.vn", "Bùi Ngọc Anh", "SERVICE_ADVISOR", Some(d1), Some("0908 111 222"))
    user("admin.td@demo.vn", "Lý Quốc Bình", "ADMIN", Some(td))
    user("advisor.td@demo.vn", "Châu Mỹ Linh", "SERVICE_ADVISOR", Some(td))
    val hungUser = user("tech.hung@demo.vn", "Trần Quốc Hùng", "TECHNICIAN", Some(d1))
    val tamUser = user("tech.tam@demo.vn", "Võ Thanh Tâm", "TECHNICIAN", Some(td))

    def tech(dealershipId: String, name: String, skills: Seq[String], start: String, end: String, days: Seq[Int],
        userId: Option[String] = None): Unit =
      val techId = id(SQL"""
        INSERT INTO technicians (dealership_id, user_id, display_name, shift_start, shift_end, work_days)
        VALUES ($dealershipId::uuid, CAST(${userId} AS uuid), $name, $start::time, $end::time, ${intArray(days)})
        RETURNING id""")
      for skill <- skills do SQL"INSERT INTO technician_skills VALUES ($techId::uuid, $skill)".execute()

    tech(d1, "Nguyễn Văn Minh", Seq("GENERAL_SERVICE"), "08:00", "17:30", MonSat)
    tech(d1, "Trần Quốc Hùng", Seq("GENERAL_SERVICE", "ENGINE", "ELECTRICAL"), "08:00", "17:30", MonSat, Some(hungUser))
    tech(d1, "Lê Thị Lan", Seq("GENERAL_SERVICE", "BRAKES_SUSPENSION"), "08:00", "17:30", 1 to 5)
    tech(d1, "Phạm Đức Khoa", Seq("CUSTOMISATION", "ELECTRICAL", "ENGINE"), "09:00", "17:30", 2 to 6)
    tech(td, "Võ Thanh Tâm", Seq("GENERAL_SERVICE", "BRAKES_SUSPENSION"), "07:30", "16:00", MonSat, Some(tamUser))
    tech(td, "Đỗ Minh Quân", Seq("GENERAL_SERVICE", "ENGINE"), "09:30", "18:00", 3 to 7)
    tech(td, "Huỳnh Gia Bảo", Seq("GENERAL_SERVICE", "ELECTRICAL"), "07:30", "18:00", 1 to 5)

    // The demo customer
    val tuan = user("customer@demo.vn", "Lê Minh Tuấn", "CUSTOMER", None, Some("0901 234 567"))
    def vehicle(owner: String, vehicleType: String, make: String, model: String, year: Int, plate: String, km: Option[Int] = None) =
      id(SQL"""
        INSERT INTO vehicles (owner_id, vehicle_type, make, model, year, plate, mileage_km)
        VALUES ($owner::uuid, $vehicleType, $make, $model, $year, $plate, $km) RETURNING id""")
    vehicle(tuan, "MOTORBIKE", "Honda", "Air Blade 160", 2023, "59-X1 234.56", Some(8_400))
    val exciter = vehicle(tuan, "MOTORBIKE", "Yamaha", "Exciter 155", 2021, "59-T2 678.90", Some(23_100))
    vehicle(tuan, "CAR", "Toyota", "Vios", 2020, "51H-123.45", Some(61_000))

    // One request waiting for an advisor, one already approved and ready to book.
    SQL"""
      INSERT INTO consultation_requests (reference, customer_id, vehicle_id, dealership_id, kind, areas, description, status, created_at)
      VALUES (${Ids.humanReference("CR")}, $tuan::uuid, $exciter::uuid, $d1::uuid, 'INSPECTION', ${textArray(Seq("ENGINE", "DRIVETRAIN"))},
        ${"There is a knocking noise from the engine when I accelerate above 60 km/h, and gear 3 sometimes slips."},
        'SUBMITTED', now() - interval '3 hours')""".execute()
    SQL"""
      INSERT INTO consultation_requests (reference, customer_id, vehicle_id, dealership_id, kind, areas, description, status,
        advisor_id, advisor_note, duration_minutes, required_skill, bay_type, estimated_price, created_at, reviewed_at)
      VALUES (${Ids.humanReference("CR")}, $tuan::uuid, $exciter::uuid, $d1::uuid, 'MODIFICATION', ${textArray(Seq("EXHAUST", "LIGHTS"))},
        ${"I would like a sport exhaust and LED headlight upgrade, street-legal please."}, 'APPROVED', $advisorD1::uuid,
        ${"We recommend a homologated slip-on exhaust and an E-marked LED projector. About 3 hours on a stand with Khoa, our customisation specialist."},
        180, 'CUSTOMISATION', 'MOTORBIKE_STAND', 4500000, now() - interval '2 days', now() - interval '1 day')""".execute()

    // A few other customers so the calendar is not empty.
    val others = Seq("Trần Thị Mai" -> "Vision", "Phan Văn Lộc" -> "Wave Alpha", "Hồ Ngọc Trâm" -> "SH Mode",
      "Vũ Đình Khang" -> "Winner X", "Ngô Bảo Châu" -> "Lead").zipWithIndex.map { case ((name, model), i) =>
      val u = user(s"walkin${i + 1}@demo.vn", name, "CUSTOMER", None)
      vehicle(u, "MOTORBIKE", "Honda", model, 2022, s"59-B${i + 1} 100.0$i")
    }

    bookSomeDemoAppointments(s, d1, others, now)

    // Two meetings with an advisor that no advisor has taken yet (the advisor's first screen).
    val nextDay = Iterator.iterate(now.atZone(Zone).toLocalDate.plusDays(1))(_.plusDays(1)).find(_.getDayOfWeek != DayOfWeek.SUNDAY).get
    for (hour, message) <- Seq(
        15 -> "My Exciter's chain is noisy. Is it time to replace the chain and sprockets, or just adjust them?",
        16 -> "I would like advice on which maintenance package fits a car that mostly drives in the city.")
    do
      SQL"""
        INSERT INTO advisor_appointments (reference, dealership_id, customer_id, starts_at, message, status, created_by)
        VALUES (${Ids.humanReference("AP")}, $d1::uuid, $tuan::uuid, ${ts(nextDay.atTime(hour, 0).atZone(Zone).toInstant)},
          $message, 'SCHEDULED', $tuan::uuid)""".execute()

  /** Fills part of the next open day at District 1 (one hour fully booked) so availability looks real. */
  private def bookSomeDemoAppointments(s: DbSession, dealershipId: String, vehicleIds: Seq[String], now: Instant): Unit =
    given Connection = Sql.connection(s)
    val store = PgSchedulingStore()
    val pkg = SQL"SELECT id FROM service_packages WHERE code = 'MB-ESSENTIAL'".as(SqlParser.scalar[UUID].single).toString
    def owner(vehicleId: String) =
      SQL"SELECT owner_id FROM vehicles WHERE id = $vehicleId::uuid".as(SqlParser.scalar[UUID].single).toString

    val day = Iterator.iterate(now.atZone(Zone).toLocalDate.plusDays(1))(_.plusDays(1)).find(_.getDayOfWeek != DayOfWeek.SUNDAY).get
    val dayStart = day.atStartOfDay(Zone)
    val requirement = Requirement(durationMinutes = 60, requiredSkill = "GENERAL_SERVICE", bayType = "MOTORBIKE_STAND")
    val plan = Seq(9 -> vehicleIds(0), 9 -> vehicleIds(1), 10 -> vehicleIds(2), 10 -> vehicleIds(3), 10 -> vehicleIds(4), 14 -> vehicleIds(0))

    for (hour, vehicleId) <- plan do
      val start = dayStart.withHour(hour)
      val resources = store.activeResources(s, dealershipId)
      val busy = store.busyPeriods(s, dealershipId, dayStart.toInstant, dayStart.plusDays(1).toInstant, now)
      Availability.allocate(requirement, start, resources.bays, resources.technicians, busy).allocation.foreach { a =>
        val customerId = owner(vehicleId)
        val appointmentId = store.insert(s, NewAppointment(
          reference = Ids.humanReference("SV"),
          dealershipId = dealershipId,
          customerId = customerId,
          vehicleId = vehicleId,
          servicePackageId = Some(pkg),
          consultationRequestId = None,
          bayId = a.bay.id,
          technicianId = a.technician.id,
          start = start.toInstant,
          end = start.plusMinutes(60).toInstant,
          customerNote = None,
          idempotencyKey = None,
          createdBy = customerId
        ))
        store.recordEvent(s, AppointmentEvent(appointmentId, None, AppointmentStatus.CONFIRMED, customerId, Some("seed")))
      }
