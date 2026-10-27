# Unified Service Scheduler

Keyloop technical assessment, **Scenario A**. Customers book a service appointment for a
vehicle at a dealership; the booking is confirmed only if a **service bay of the right type
and a technician with the right skill, on shift, are both free for the whole job**. Double
booking is impossible, even when many people press "Book" at the same moment.

Both layers are implemented. The backend (**Scala 3 + Play 3**, PostgreSQL) is the "fully
implemented" layer the brief asks for: REST API, tests, logging and metrics. The frontend is a
working React app rather than a stub, so the whole journey can be demonstrated.

What changed in each pull request: [PATCH_NOTES.md](PATCH_NOTES.md).

## Quick start

Requirements: **JDK 21+**, **sbt 1.10+** and **Node.js 22+** (for the web app). No Docker needed:
when `DATABASE_URL` is not set, the API starts a real embedded PostgreSQL 17 (binaries from
Maven Central) and loads demo data.

```bash
npm run setup     # install the web app's dependencies
npm run dev       # API on :8081 (sbt run + embedded PostgreSQL), web app on :5173
```

Open <http://localhost:5173>. API documentation (Swagger UI): <http://localhost:8081/docs>.
The first start downloads dependencies and compiles the Scala code, so give it a minute or two.

Scala version: 3.3.6 (LTS) by default. The build and all tests also pass on 3.9.0 and 3.10.0;
to switch, change `scalaVersion` in `backend/build.sbt` (sbt downloads the compiler itself).

Running only the API: `cd backend && sbt run`. Production-style package: `sbt stage`, then
`backend/target/universal/stage/bin/service-scheduler`.

All demo accounts use the password **`Demo@2026`** in the dev profile (the login page of the
`npm run dev` server on localhost has one-tap buttons):

| Account | Role | Try this |
| --- | --- | --- |
| `customer@demo.vn` | Customer | Book a service: vehicle, package, dealership and a free time; see it in My appointments |
| `tech.hung@demo.vn` | Technician, District 1 | Service requests: start, finish and close your jobs |
| `tech.minh`, `tech.lan`, `tech.khoa` (District 1), `tech.tam`, `tech.quan`, `tech.bao` (Thủ Đức) `@demo.vn` | Technician | Every seeded technician has a sign-in, so no name on screen lacks an account |
| `sysadmin@demo.vn` | System manager | Sees everything: dealerships, All data (appointments, bays, technicians, vehicles), people, the package catalogue; onboard a dealership |

The app has these three roles only, as the brief needs. Older staff accounts in the seed
(`advisor.d1`, `admin.d1`, `controller.d1`, `parts.d1`) still exist in the database but have no
pages: they see a short "not used in this version" note after signing in.

The seed fills part of the next working day at District 1 (09:00 has one stand left, 10:00 is
full) so availability looks real. To start again from an empty database, stop the API and delete
`.data/pg-54330`; the next start recreates and re-seeds it.

Using your own PostgreSQL instead: `docker compose up -d`, then
`DATABASE_URL=postgres://scheduler:scheduler@localhost:5432/scheduler npm run dev`.

| Setting (env var) | Default | Meaning |
| --- | --- | --- |
| `DATABASE_URL` | empty → embedded PostgreSQL | `postgres://…` or `jdbc:postgresql://…` |
| `PORT` | 8081 | HTTP port (packaged server) |
| `CORS_ORIGIN` | `http://localhost:5173` | Comma-separated allowed browser origins |
| `APP_PROFILE` | dev | `dev` or `prod`, see [Profiles](#profiles-dev-and-prod) |
| `SEED_DEMO_DATA` | true | Load demo data into an empty database |
| `SEED_ACCOUNT_PASSWORDS` | empty | prod only: `email=password` (or `email=<pbkdf2 hash>`) per demo account, `;`-separated |
| `LOG_FORMAT` / `LOG_LEVEL` | json / INFO | `pretty` for readable local lines |
| `SLOW_QUERY_MS` | 200 | Log queries slower than this |
| `SESSION_TTL_HOURS` | 12 | Sign-in lifetime |
| `APPLICATION_SECRET` | dev value | Required by Play in production |
| `HOLD_MINUTES` / `HOLD_SWEEP_SECONDS` | 10 / 30 | How long a held slot is kept; how often expired holds are released |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | empty → no export | Send traces over OTLP, e.g. `http://jaeger:4318` |

### Profiles: dev and prod

| | dev (default) | prod (the Azure site) |
| --- | --- | --- |
| Where | your computer: `npm run dev`, `sbt run`, docker-compose, CI | Azure Container Apps only |
| Database | embedded or local PostgreSQL | its own database, `scheduler_prod` |
| Demo account passwords | all `Demo@2026` | one strong password each, from `SEED_ACCOUNT_PASSWORDS`; an account left out is locked, and `Demo@2026` is refused |
| Login page | one-tap demo accounts (dev server on localhost only) | no accounts shown: the production build of the web app does not contain them |
| Start-up checks | refuses to run on Azure Container Apps | needs `DATABASE_URL` and `APPLICATION_SECRET` |

The production passwords are made by `python3 deploy/azure/prod-accounts.py <folder>`, which
writes the accounts with their passwords (`prod-accounts.txt`, keep it private) and the same list
with only PBKDF2 hashes (`PROD_SEED_ACCOUNTS.secret.txt`). The hashes go into the GitHub secret
`PROD_SEED_ACCOUNTS`; the deploy job hands them to the API, so no password is in the repository,
the web app or the Azure configuration. Run the script on your own computer, so the plain
passwords exist nowhere else.

Changing the production passwords: run the script again, replace the `PROD_SEED_ACCOUNTS` secret
with the new hash file, then run the CI/CD workflow on `master` (Actions → CI/CD → Run workflow).
On start-up the prod API sets each listed account to its new password and signs it out everywhere;
the old passwords stop working.

## Tests

```bash
npm test          # = cd backend && sbt test: 274 tests (unit, property-based, architecture, API, concurrency)
npm run test:load # = sbt loadTest: three bursts of 1000 simultaneous HTTP requests
```

| Suite (`backend/test/scheduler/…`) | What it proves |
| --- | --- |
| `unit/AvailabilitySpec` | Slot grid, opening hours, shifts, lead time, best-fit choice, daylight-saving days |
| `unit/AvailabilityPropertySpec` | ScalaCheck, for thousands of random workshops and booking streams: no bay or technician is ever double-booked; every assignment has the right skill, bay type and shift; the advertised capacity is exactly what fits; no slot shown as free is refused at booking |
| `unit/LifecycleSpec` | Appointment state machine and who may do what, when |
| `unit/BookingServiceFakesSpec` | Booking retry and error mapping on in-memory fakes of every port (no database): lost race → next free pair, deadlock → retry, give up after 5, vehicle double-booking, job not offered |
| `unit/AppointmentServiceSpec` | Who may see and move which appointment (customer, staff, technician, system manager), every lifecycle error mapped to its API code, hold confirm / expiry / double confirm |
| `unit/DispatchServiceSpec` | Moving a job: bay type, technician skill and shift, nothing-to-change, overlap → `RESOURCE_BUSY`, expired holds released first; the day's workload |
| `unit/SchedulingSupportSpec` | What a package or approved consultation requires (ownership, dealership, vehicle), nearest free slots, a day's availability, the hold sweeper |
| `unit/PartsServiceSpec` | Parts shelf and orders on a stock-keeping fake: lines merged, stock never below zero, receive → issue, invalid transitions |
| `unit/ConsultationServiceSpec` | Submit, approve / reject once, unknown skill, bay that cannot take the vehicle, visibility and cancel rules |
| `unit/IdentitySpec` | Password hashing and verify (incl. corrupted hashes), session tokens, register / login / logout, one 401 for every login failure |
| `unit/AdminServicesSpec` | Technicians with a login in one transaction, shifts and opening hours, dealership onboarding, package bay/vehicle fit, vehicles |
| `unit/ValidateSpec`, `unit/RequestReadsSpec` | Every validation building block and the request shapes of each module (one-of rules, VIN, passwords, 15-minute grid, allowed roles) |
| `unit/DomainRulesSpec` | Bay compatibility, both lifecycles checked exhaustively, workload figures, references |
| `unit/ArchitectureSpec` | The dependency rule: no domain, application or port file imports an adapter, the HTTP layer, `play.api.mvc`, Anorm, JDBC, the driver or SQL helpers |
| `integration/BookingConcurrencySpec` | 50 simultaneous requests for one slot: exactly as many succeed as there are free bay/technician pairs; 50 retries with one `Idempotency-Key` create one booking (ten rounds, never a 500); each database constraint rejects overlaps on its own |
| `integration/ApiSpec` | Auth and logout revocation, role and dealership isolation, the booking and consultation journeys, alternatives on conflict, technician workflow, admin onboarding, request-ID propagation, metrics, and that every route is in the OpenAPI contract |

Integration tests start their own throw-away embedded PostgreSQL (or use `TEST_DATABASE_URL`, for
example a CI service container); every suite gets a fresh database.

### Load test: 1000 requests at the same instant

`sbt loadTest` starts the API on a real socket (Play's Pekko HTTP server) and fires 1000 requests
at once over HTTP, three times. Measured in the 2-vCPU build sandbox, with the API, PostgreSQL and
the load generator all sharing those two cores:

| Burst of 1000 | Result | Wall time | p50 / p99 |
| --- | --- | --- | --- |
| Bookings for the **same slot** (4 bay + technician pairs) | exactly 4 × 201, 996 × 409 with alternatives, 0 errors | 5.5 s | 4.4 s / 5.3 s |
| Bookings **spread** over 54 slots (20 pairs) | 1000 × 201, 0 overlaps in the database | 3.2 s | 2.2 s / 3.0 s |
| Availability reads | 1000 × 200 | 2.1 s | 1.6 s / 2.0 s |

Every request is answered and none is wrong. Latency here is queueing on two shared cores, not a
limit of the design. The load test found two real problems in the Scala version, both fixed (see
`docs/ai-log.md`, entries 11 and 12).

**How concurrency works.** Play's HTTP threads never block. Every use case runs on its own **Java
21 virtual thread** (`Blocking` in `http/Http.scala`): JDBC and password hashing block that
virtual thread, which costs a few hundred bytes, not an OS thread, so 1000 waiting requests cost
memory, not 1000 threads. Database work runs in parallel on up to 20 pooled connections (HikariCP)
per API instance, and nested calls on one thread reuse its connection, so a burst cannot starve
the pool. To scale out, run more instances behind a load balancer: the API keeps no state in
memory (sessions are in the database), and the no-double-booking rule is enforced by PostgreSQL,
so it holds across any number of instances.

### End-to-end tests in a real browser (Selenium, Scala)

`e2e/` is a separate sbt project: ScalaTest drives Chrome through the running web app, the way a
person would (by visible text, labels and roles; it waits for the page and never sleeps).

```bash
docker compose --profile full up -d --build   # the system under test: 3 API instances behind nginx
cd e2e && sbt test                            # BASE_URL=http://localhost:8080 by default; HEADLESS=false to watch
# or against `npm run dev`: BASE_URL=http://localhost:5173 sbt test
```

| Suite (`e2e/src/test/scala/scheduler/e2e/…`) | What it does |
| --- | --- |
| `BookingJourneySpec` | The brief's main feature with the three roles: a new customer adds a car, picks a package, **holds** a time, gives it back, holds it again and confirms; the booking names its technician. That technician finds it under Service requests with the customer's note, **starts**, **finishes** and **closes** it once paid; the customer sees it finished; the system manager sees it in All data with customer, vehicle, technician and bay |
| `BookingRulesSpec` | The checks in the booking diagram: My vehicles; only packages for the vehicle's type; "cannot do this job" at a dealership without a car lift; another customer takes the chosen time first (through the API), so the page offers the nearest free times and one is booked; the `.ics` calendar file |
| `DoubleSubmitSpec` | The main feature's worst case on a slowed-down network: Hold and Confirm each clicked five times at once end with one appointment and no false error; a booked vehicle's buttons are locked |
| `AuthSpec` | Wrong password, demo-account buttons, sign-out, a customer kept out of system pages, each role's start page |
| `SystemManagerSpec` | Dealerships, All data (appointments, bays and technicians, vehicles), People and the package catalogue |
| `OnboardingSpec` | The system manager onboards a dealership and sees its zero capacity; customers are told it cannot do the job yet. Adds rows, tagged with the run, that only a database reset removes |

Each use case is mapped to its test in [docs/e2e-traceability.md](docs/e2e-traceability.md).
CI runs them on every pull request against the freshly built images; a failure uploads a
screenshot of each failing step and the API logs. Writing them found a real bug: behind nginx,
Play's CORS filter refused the browser's sign-in (403), now fixed by setting `CORS_ORIGIN` to the
web app's address in docker-compose and on Azure.

## Scale out and deploy

The API keeps nothing that matters in memory: sessions, holds and the no-double-booking rule all
live in PostgreSQL, so more instances need no code change.

```bash
docker compose --profile full up -d --build      # 3 API instances + nginx + PostgreSQL + Jaeger + Prometheus
node scripts/load-test.mjs http://localhost:8080 # reads spread over instances; a same-slot booking race
```

Web app <http://localhost:8080>, Jaeger <http://localhost:16686>, Prometheus <http://localhost:9090>.
A run on one 2-vCPU machine:

```
1) 1000 availability reads, 50 at a time
  status   200: 1000
  served by 09d8dee07fc3: 332, 3e09cbee84f7: 335, 3eaa4fc9a1fb: 333
2) 15 vehicles book 2026-10-26T08:00 at the same instant (capacity 3)
  status   201: 3, 409: 12
  served by 09d8dee07fc3: 5, 3e09cbee84f7: 5, 3eaa4fc9a1fb: 5
  ✔ exactly 3 bookings succeeded for 3 free bay+technician pairs; the rest got 409
```

**CI/CD** (`.github/workflows/ci-cd.yml`): every pull request runs the backend tests, the web
app's lint and build, and builds both Docker images. A push to `master` also pushes the images to
Azure Container Registry, rolls them out to **Azure Container Apps** (API: internal, 1-5 replicas
scaled on concurrent requests; web: public HTTPS) and smoke-tests the live site. GitHub signs in
to Azure with OpenID Connect, so no password is stored. One-off Azure set-up, in Cloud Shell:

```bash
curl -fsSL https://raw.githubusercontent.com/Buithienphuoc/Keyloop-challenge-case-A/master/deploy/azure/setup.sh -o setup.sh
bash setup.sh   # then add the 5 printed values as GitHub repository variables
                # and the PROD_SEED_ACCOUNTS secret (see Profiles above)
```

## What it does

**Customers** (the main feature, so their flow comes first)
- *Book a service*: pick a vehicle, a fixed-price package with a visible checklist, a
  dealership and a free time; confirm. The system only offers a time when a bay of the right
  type and a qualified technician on shift are both free for the whole job, and the confirmed
  appointment names the customer, vehicle, technician and bay. A service ticket fills in beside
  the steps.
- Availability refreshes every 30 s and opens on the first day that still has room.
- If someone takes the slot during checkout, the page offers the three nearest free times.
- The chosen time is **held for 10 minutes** while the customer reviews the booking (a countdown
  shows it); confirming turns the hold into a booking, going back releases it.
- Cancel before the start; add to calendar (.ics); manage vehicles.

**Technician**: Service requests, the jobs booked with them for a day (checklist and the
customer's note), with large buttons to start, finish and close a job once the customer has paid.

**System manager**: sees everything. All dealerships with capacity figures; All data
(appointments of every dealership, bays and technicians, every customer's vehicle); people; the
service package catalogue; onboarding a dealership.

**Not in this version** (built and tested in the backend before the scope-down to three roles,
no page in the web app; drawn solid grey in [the diagrams](docs/diagrams/README.md))
- *Check something specific* and *Customise my vehicle*: the customer describes the problem or
  idea; an advisor turns it into a schedulable job (duration, skill, bay type, estimate); the
  customer then picks a time. Both use one "consult first, then book" flow.
- *Service advisor*: a day board (bays × hours) with start / done / no-show / cancel; the
  advice-request queue.
- *Dealership admin*: bays (in or out of service), technicians with skills, shifts and working
  days, staff accounts, opening hours (with a count of bookings that fall outside new hours).
- *Workshop controller*: the day's load per bay and technician; move a job to another bay or
  technician (the same database constraints refuse a clash).
- *Parts staff*: stock list and parts orders (requested → ordered → received → issued), which an
  advisor or technician can raise from a job; issuing takes stock and can never go below zero.

## Diagrams

Use case, activity, state, data flow (context and level 1), class (domain model and application
layer) and hexagonal architecture diagrams, with a table tracing every use case to its API, screen
and test: [docs/diagrams](docs/diagrams/README.md).

## Architecture

```mermaid
flowchart LR
  subgraph Browser
    UI[React web app<br/>customer · advisor · technician · admin]
  end
  subgraph API[Scala 3 API – Play 3]
    direction TB
    HTTP[HTTP adapters<br/>controllers · validation · auth guard]
    APP[Application services<br/>Availability · Booking · Appointment<br/>Consultation · Vehicle · Identity · Admin · Tenant]
    DOM[Domain – pure functions<br/>availability · allocation · lifecycle]
    PG[PostgreSQL adapters<br/>Anorm, one per port]
    OBS[Logging · metrics · request IDs]
    HTTP --> APP --> DOM
    APP -- ports --> PG
  end
  DB[(PostgreSQL<br/>EXCLUDE constraints)]
  UI -- "JSON over HTTPS<br/>Bearer token · X-Request-Id · Idempotency-Key" --> HTTP
  PG --> DB
  OBS -. "/metrics · JSON logs" .-> MON[Prometheus / log platform]
```

Hexagonal (ports and adapters) inside a modular monolith. Every module has the same shape:

```
backend/
  app/scheduler/
    AppLoader.scala       composition root: the only place that picks which adapter implements which port
    Seed.scala            demo data
    http/                 request IDs, error format, request logging, auth guard, /health /metrics /docs
    modules/<module>/
      Domain.scala | domain/   pure types and rules (no I/O)
      Ports.scala              traits the use cases need (repositories, narrow cross-module ports)
      application/             use cases (services) and their input shapes; depend on ports only
      adapters/Pg*.scala       PostgreSQL implementations of the ports (Anorm)
      adapters/*Controller.scala  HTTP adapter: parse, call a service, set the status code
    modules: scheduling (availability, booking, appointments), consultation, vehicles,
             catalog, identity, admin (workshop resources), system (tenants)
    shared/               config, logging, errors, Db port + PostgreSQL pool, metrics, migrations
  conf/routes             every endpoint
  conf/db/migration/V1__init.sql   the schema, including the no-double-booking constraints
  conf/openapi.yaml       the hand-written API contract served at /docs
frontend/src/             React + TanStack Query
```

The SOLID review, what it changed and the trade-offs kept on purpose:
[docs/solid-review.md](docs/solid-review.md). `ArchitectureSpec` keeps the dependency rule from
eroding.

### How a booking stays correct

1. `GET /api/availability` computes, for each start time on the dealership's grid, how many
   (bay, technician) pairs are free for the whole duration: bay of the required type, technician
   with the required skill and on shift. Pure functions (`domain/Availability.scala`), exhaustively tested.
2. `POST /api/appointments` re-checks inside a transaction, picks a pair and inserts.
   **Best fit**: the technician with the fewest skills is chosen first, so specialists stay free
   for work only they can do; ties go to whoever has the least booked that day.
3. PostgreSQL is the final judge. Three `EXCLUDE USING gist` constraints on
   `tstzrange` periods make overlapping bookings of the same **bay**, **technician** or
   **vehicle** impossible. Ranges are half-open, so 09:00–10:00 and 10:00–11:00 do not clash.
4. If a concurrent request won the same pair (`23P01`), the service retries with fresh data
   and picks the next free pair. If nothing is left, it answers `409 SLOT_UNAVAILABLE` with the
   nearest alternatives.
5. An optional `Idempotency-Key` makes retries and double taps safe: the same key returns the
   original appointment (`200`, `Idempotent-Replayed: true`).
6. **One live appointment per vehicle.** Requests for the same vehicle queue on a per-vehicle
   lock, and a vehicle that is already on hold, booked or in the workshop is refused with
   `409 VEHICLE_HAS_APPOINTMENT` (naming that appointment) until it is cancelled or done. So
   pressing Book several times, from one tab or several, without an idempotency key, still
   books once, and answers at once instead of waiting on each other. The pages lock booking for
   such a vehicle and say which appointment to cancel; the web client also sends an identical
   change only once while it is on its way, for every button.

### Technology choices

| Choice | Why |
| --- | --- |
| PostgreSQL | Range types and exclusion constraints enforce the core invariant in the database, not just in code |
| Scala 3 + Play 3 | Strong types and enums for the domain; Play gives routing, JSON and a non-blocking HTTP server; compile-time wiring (no runtime DI container) keeps the composition root plain code |
| Java 21 virtual threads | Blocking JDBC code stays simple and still handles bursts of concurrent requests |
| Anorm + HikariCP | Plain SQL with typed parameters (the constraints and range queries are PostgreSQL-specific anyway), a fast connection pool |
| Flyway | Versioned migrations, locked so two instances never migrate at once |
| play-json | Request validation with readable, per-field error messages |
| java.time | Explicit time-zone arithmetic; every dealership has its own IANA zone |
| Embedded PostgreSQL (Zonky) | `sbt run` works with no Docker; tests run against the real database engine |
| ScalaTest + ScalaCheck | Readable specs; property-based testing for the scheduling invariants |
| Logback + Logstash encoder, Prometheus client | Structured JSON logs and standard metrics |
| React + Vite + TanStack Query | Simple SPA; query caching and refetching keep availability fresh |

The first version was written in TypeScript because the first build environment could not reach
Maven Central. Once it could, the backend was ported to Scala with the same structure and every
test ported with it, after which the TypeScript version was removed (it remains in the git
history of the earlier build).

## Observability

- **Logs**: one JSON line per event (Logback + Logstash encoder), with `requestId`, `userId`,
  `role` and `dealershipId` on every line of a request. Domain events are named, for example
  `booking.created`, `booking.race_lost_retrying`, `booking.slot_unavailable`,
  `booking.idempotent_replay`, `appointment.status_changed`, `consultation.reviewed`,
  `auth.login_failed` (email masked). Passwords and tokens are never logged.
  `LOG_FORMAT=pretty` for readable local output (the default in `npm run dev`).
- **Correlation**: the web app sends an `X-Request-Id` on every call; the API reuses it (or
  creates one), returns it in the response header and in every error body, and the UI shows it
  for unexpected errors, so a user's report leads straight to the log lines.
- **Metrics** at `GET /metrics` (Prometheus): request latency by route and status, database
  query latency, `bookings_created_total`, `booking_conflicts_total{reason="race"|"deadlock"|"unavailable"}`,
  `booking_idempotent_replays_total`, `appointment_status_changes_total`,
  `consultation_requests_total`, `auth_login_failures_total`, plus JVM metrics.
- **Slow queries** over `SLOW_QUERY_MS` (200 ms) are logged with the statement shape, never the
  parameter values.
- **Health**: `GET /health` checks the database. **Audit trail**: every status change is stored in
  `appointment_events` (who, when, from, to).

- **Tracing** (OpenTelemetry): one span per request (continuing a W3C `traceparent`), one per
  booking attempt and one per SQL statement. Set `OTEL_EXPORTER_OTLP_ENDPOINT` to export them;
  the `full` docker-compose profile sends them to Jaeger. Responses carry `X-Trace-Id` and
  `X-Served-By` (which instance answered).

## Assumptions

- A job needs exactly one bay and one technician for its whole duration; jobs do not span days.
- Start times sit on a 30-minute grid inside opening hours; the whole job must end by closing.
- Bookings open at least 60 minutes ahead and up to 60 days ahead (configurable per dealership).
- Duration, skill and bay type come from the package; for inspections and customisations the
  advisor sets them when approving.
- Bay types limit vehicle types: motorbike stand, car lift, diagnostic bay (both).
- Customers book for themselves; staff manage bookings but do not create them in this version.
- "Sales" from the brainstorm is covered by the service advisor role.
- Prices are shown in VND; all times are shown in the dealership's time zone.

## Not built yet

Rescheduling (cancel and rebook today), staff booking on behalf of a customer, notifications
(SMS/Zalo/email), breaks and holidays in shifts, multi-technician jobs, rate limiting on login.

## AI collaboration narrative

> Draft from the build so far. Rewrite it in your own words; the reviewers want *your* strategy
> and judgement. Keep adding real entries to `docs/ai-log.md`.

**Strategy.** Requirements, assumptions and invariants were written first (shared design notes),
then the AI implemented against them in layers: schema and constraints, pure domain logic, use
cases, HTTP, then UI. The riskiest rule (no double booking) was placed in the database so that it
holds even if application code is wrong.

**Verification.**
1. Every layer has tests that run against real PostgreSQL, not mocks.
2. Property-based tests check invariants over thousands of generated cases instead of trusting a
   handful of examples.
3. **Mutation checks**: when all tests passed first time, parts of the code were deliberately
   broken to confirm a test would fail. This exposed a concurrency test that did not test what
   it claimed (see `docs/ai-log.md`, entry 2).
4. When the backend moved from TypeScript to Scala, the whole test suite was ported case by
   case with the same expected values, so the port had to prove it behaves the same.
5. The UI was driven in a headless browser at desktop and phone sizes and reviewed from
   screenshots, which found a mobile overflow and a low-contrast text bug.

**Issues caught in AI-written code** (details in `docs/ai-log.md`): slot times off by an hour on
daylight-saving days; a test that passed for the wrong reason; date auto-advance that stopped
after one day; mobile layout overflow; unreadable text on the main home card; and, in the Scala
port, a Jackson version clash, connection-pool starvation under a burst and dropped connections
from a too-small accept backlog (entries 10 to 12).
