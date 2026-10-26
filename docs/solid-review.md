# SOLID review of the backend

The review was done on 25 Oct 2026 on the first (TypeScript) version of the backend. It found
eight problems; all eight were fixed there, and the Scala / Play backend was then written with the
same structure, class names and ports, so every fix below holds in the Scala code too. The
dependency rule is enforced by a test (`ArchitectureSpec`), not by review.

File names below are the Scala ones (`backend/app/scheduler/...`).

> **Follow-up, 26 October 2026:** the features added later (holds, workshop controller, parts,
> opening hours, package catalogue, tracing) were reviewed again. The structure below still
> holds; the smaller findings (duplicated helpers, two services that depend on the concrete
> `IdentityService`, one long method, a two-way module dependency) are listed in
> [`code-review-2026-10-26.md`](code-review-2026-10-26.md).

## What was wrong and what changed

| # | Principle | Before | After |
| --- | --- | --- | --- |
| 1 | SRP | `BookingService` (403 lines) did availability, booking, read views and the status lifecycle | Split into `AvailabilityService`, `BookingService`, `AppointmentService`; the rules they share (dealership lookup, package or consultation → job requirement) live in `WorkshopContext` |
| 2 | SRP | Route files for admin, vehicles and system ran SQL, mapped database errors and logged | Each module has a controller (`adapters/*Controller.scala`, HTTP only), `application/*Service.scala` (use cases), `adapters/Pg*.scala` (SQL) and `Ports.scala` / `Domain.scala` (traits and types) |
| 3 | DIP | `BookingService` created a concrete catalogue repository itself | It receives a `CatalogReader` trait; `AppComponents` (in `AppLoader.scala`) is the only place that picks implementations |
| 4 | DIP | Booking and consultation called a vehicle lookup that lived in the vehicles HTTP adapter | Both depend on the `OwnedVehicles` trait, implemented by `VehicleService` |
| 5 | DIP | `BookingService` inspected PostgreSQL error codes (`23P01`, `40P01`, constraint names) | The store translates them into `SlotTakenError` and `VehicleBusyError`, and `Db.tx` into `TransientStorageError`; SQL codes exist only in `shared/PgDb.scala` (`PgErrors`), used by adapters only |
| 6 | DIP | The database port exposed driver types | `Db` and `DbSession` are our own traits; application code never imports JDBC, Anorm or the driver |
| 7 | ISP | Booking depended on the whole `ConsultationService`; the auth guard on the whole `IdentityService`; every scheduling use case on the 11-method store | `ConsultationBookingPort` (3 methods, declared by scheduling), `Authenticator` (1 method), and the store split into `ResourceCalendar`, `AppointmentReader`, `AppointmentWriter` |
| 8 | OCP | Lifecycle errors mapped by an `if` chain in the service | A `TransitionErrors` table: a new rule adds one entry, the service does not change |

## How each principle shows in the code

- **S**: one reason to change per unit. HTTP shape → a controller; a business rule → a service or
  `domain/`; a query → a `Pg*` adapter; wiring → `AppComponents`.
- **O**: rules are data where they vary: state transitions and role permissions
  (`domain/Lifecycle.scala`), parts order transitions and their stock effect
  (`parts/Domain.scala`, `PartOrderLifecycle`), start-time errors (`BookingService.StartErrors`), transition errors
  (`AppointmentService.TransitionErrors`), bay compatibility (`domain/Compatibility.scala`).
- **L**: any implementation of a port can replace another. `BookingServiceFakesSpec` runs
  `BookingService` on in-memory fakes, and the integration specs run it on PostgreSQL, with the
  same expectations.
- **I**: consumers declare the narrow trait they need (`OwnedVehicles`, `Authenticator`,
  `ConsultationBookingPort`, `ResourceCalendar`), and one adapter may implement several
  (`PgIdentityRepo` implements `UserRepository` and `SessionRepository`).
- **D**: application services depend on traits; adapters implement them; `AppComponents` connects
  them. `ArchitectureSpec` fails if a domain, application or port file imports an adapter, the
  HTTP layer, `play.api.mvc`, Anorm, JDBC, the PostgreSQL driver or the SQL helpers.

## Accepted trade-offs (deliberate, listed so nobody has to find them)

1. **Metrics and the logger are cross-cutting.** Services use the `Metrics` registry directly and
   receive the request logger (`Log`) as a parameter. Injecting a metrics port everywhere would
   add noise without changing behaviour.
2. **Request shapes (play-json `Reads`) live in the application layer.** They are the use-case
   input contract; the controller only calls `body[T]` / `query[T]`. play-json is a pure library
   (no HTTP), which is why the architecture test allows it but forbids `play.api.mvc`.
3. **Ports take a `DbSession` for transactions.** It is a unit-of-work handle (our trait, not
   JDBC's), so a use case can make several writes atomic, such as a technician with a login, or
   a booking that also marks its consultation as booked.
4. **Password hashing is a plain object (`identity/Passwords.scala`, `javax.crypto`)**, not a port.
   It has no I/O and no alternative implementation to swap.
5. **`Seed.scala`, `Migrations.scala` and `/health` run SQL directly.** They are infrastructure
   tools, outside the use cases.
6. **The scheduling domain imports `identity.Role`.** The lifecycle rules say which role may make
   which move, so `Role` is shared vocabulary; it is a plain enum with no behaviour.

## Bugs found during the same review

| Bug | Fix | Proof |
| --- | --- | --- |
| The slot grid ignored `maxDaysAhead`: a far date looked free, and a "nearest alternative" could be beyond what the dealership accepts, so booking it failed with `TOO_FAR_AHEAD` | `daySlots` marks those starts `TOO_FAR_AHEAD`, sharing one `lastBookableDay` rule with `validateStart` | Property test: every slot shown as available passes `validateStart`, over random rules, dates and clocks; it fails without the fix |
| A deadlock outside booking would have returned a generic 500 | Error handler answers `503 TRY_AGAIN` with `Retry-After: 1` | Booking itself still retries deadlocks (load-test regression test) |
| A job no bay or technician at the dealership can do answered `409 SLOT_UNAVAILABLE` with an empty list | `422 NOT_OFFERED_HERE`, documented in `openapi.yaml` | Unit test with fakes |

## Checks run on the Scala backend

- `sbt compile` with `-Wunused:all -Werror`: clean.
- `sbt test`: 95 tests pass (58 unit, property and architecture checks; 37 integration tests on
  real PostgreSQL).
- `sbt loadTest`: 1000 simultaneous requests over HTTP, three bursts, no errors (see README).
- Packaged server (`sbt stage`) booted with embedded PostgreSQL; the unchanged React app was
  driven in headless Chromium through a full booking and every staff page, with no console
  errors and no 5xx responses.
