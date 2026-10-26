# Readability and SOLID review (26 October 2026)

Scope: the Scala backend as merged on `master` (commit `6613203`, after PR #2 "scalable extras":
holds, workshop controller, parts, opening hours, package catalogue, tracing) and the largest
React pages. The first SOLID review (`docs/solid-review.md`) was done on the TypeScript version
before these features existed; this review checks that the new code kept the same structure.

**Verdict.** The structure holds. Every new feature follows the module layout (controller →
service → port → `Pg*` adapter), `ArchitectureSpec` still guards the dependency rule, and the
new rules are data where they vary (`Lifecycle.Transitions`, `PartOrderLifecycle`). No SOLID
violation of the kind fixed in the first review came back. The findings below are smaller:
duplicated helpers, two places that depend on a concrete service, one long method, and one
two-way dependency between modules. None changes behaviour; one is a small metrics gap.

Severity: **M** = worth fixing before the demo, **L** = nice to have.

## Findings

| # | Sev | Principle | Where | What | Suggested fix |
| --- | --- | --- | --- | --- | --- |
| R1 | M | SRP / readability | `scheduling/application/BookingService.scala:72-179` | `book` is about 110 lines with two nested closures (`attempt`, `loop`) and a six-way `try/catch`. It is correct and well commented, but it reads as one block: validation, the transaction body, the retry loop, metrics/logging and the 409 fallback all live in one method. | Lift `attempt` and `loop` into private methods that take a small `BookingPlan` case class (dealership, vehicle, requirement, rules, start, end, status, holdExpiresAt). Same behaviour, each step readable on its own. |
| R2 | M | DRY / readability | `DispatchService:97`, `WorkshopAdminService:138`, `PartsService:101`, plus inline in `AppointmentService:45`, `ConsultationService:84` | "the caller's dealership or 403" is written five times, with two different messages. | One extension in `identity/Domain.scala`, next to `assertSameDealership`: `extension (a: AuthContext) def requireDealership: String`. |
| R3 | M | DRY / OCP | `BookingService:105`, `DispatchService:77`, `HoldSweeper:18` | Releasing expired holds is done three times: call `expireHolds`, reopen each consultation, count the metric. `DispatchService` forgets the `holds{event="expired"}` metric, so holds released by a job move are not counted. | One `releaseExpiredHolds(s, scope)` (in `HoldSweeper` or a small `Holds` helper) used by all three. Fixes the metric gap. |
| R4 | L | DRY / readability | `AppointmentService`, `AvailabilityService`, `BookingService:100-101`, `DispatchService:45-46` | "start of a local day as an Instant" (`atStartOfDay(zone).toInstant`) is written six times across four application services, two of them as private `dayStart` helpers. | `WorkshopRules.dayRange(date): (Instant, Instant)` in `domain/Types.scala`. |
| R5 | M | DIP / ISP | `WorkshopAdminService:79`, `TenantService:55` | Both take the concrete `IdentityService` but only call `createUser` (and `listStaff`). Every other cross-module dependency goes through a narrow trait (`OwnedVehicles`, `ConsultationBookingPort`, `Authenticator`); these two do not, so they cannot be unit-tested with a fake. | A `UserAccounts` trait in `identity/Ports.scala` (`createUser(input, s?)`, `listStaff`), implemented by `IdentityService`. |
| R6 | L | Module boundaries | `consultation` ↔ `scheduling` | `ConsultationService` implements `scheduling.application.ConsultationBookingPort` and uses `scheduling.domain.Compatibility`; `scheduling` imports `consultation.BookableConsultation` and `ConsultationStatus`. The two modules depend on each other. This is how the port was designed (the consumer owns the port), but `BookableConsultation` then belongs to scheduling too. | Move `BookableConsultation` next to the port in `scheduling/application/Ports.scala` and let `forBooking` return `Option[BookableConsultation]` already checked for `APPROVED`. Then only `consultation → scheduling` remains. Document it if left as is. |
| R7 | L | Readability | `AppointmentService:58-66, 116-122`, `Workshop.scala:50-53`, `ConsultationService:74-78` | The visibility rule "customer sees own, staff see own dealership, technician sees own jobs" is re-written in four places, each slightly differently (404 vs 403). | One `Visibility` helper in identity (`assertCanSee(auth, ownerId, dealershipId)`) and keep the technician check in scheduling. |
| R8 | L | Readability | `AvailabilityService:37` (`21`), `AvailabilityService:57` (`14`) | Magic numbers: how many bookable dates the UI gets, how many days `nearest` searches. | Named constants (`BookableDatesShown`, `AlternativeSearchDays`) with a one-line comment. |
| R9 | L | Readability | `ConsultationService:89-105` | The decision is matched twice (once to write, once to name it for metrics and logs). | Return the label from the first `match`. |
| R10 | L | Correct status code | `IdentityService:82-83` | `getUser` answers `400 USER_NOT_FOUND` for a user that no longer exists; elsewhere a missing entity is `404`. | `AppError.notFound("User")`, or document why 400. |
| R11 | L | Efficiency / readability | `PartsService:55, 63` | After adding a part or adjusting stock the service reloads the whole dealership's part list to return one part. | `parts.getPart(dealershipId, id)` on the port. |
| R12 | L | Readability | `reader.get(...).get` after every write (11 places in `application/`) | A bare `.get` hides the assumption "the row we just wrote exists". | A `reload(id)` helper per service that throws a clear internal error, or keep and comment once. |
| R13 | M | SRP / readability (frontend) | `frontend/src/pages/customer/BookPage.tsx` (594 lines, 13 `useState`) | One component holds the four-step wizard, the hold timer, alternatives after a lost race and the confirmation screen. Hard to follow which state belongs to which step. | Split into `VehicleStep`, `ServiceStep`, `TimeStep`, `ConfirmStep` and drive the wizard with one `useReducer`. Same for `ResourcesPage.tsx` (451 lines), which already has sub-components and is fine to leave. |

## Kept on purpose (agree with the first review)

- Request shapes (play-json `Reads`) sit next to the use cases (`AdminRequests`, `PartsRequests`,
  `PackageRequests`, …). They are the use-case input contract and play-json has no HTTP in it.
- Services use `Metrics` and receive `Log` directly. A metrics port would add noise.
- Ports take a `DbSession` so a use case controls its own transaction.

## What is good and should stay

- `AppComponents` is the only place that picks implementations; the same `PgSchedulingStore` is
  handed out as three narrow ports.
- Errors from the database are translated in adapters (`SlotTakenError`, `VehicleBusyError`,
  `TransientStorageError`, `OutOfStockError`); no SQL state reaches a service.
- The lifecycle and role permissions are tables (`Lifecycle.Transitions`, `AllowedByRole`);
  error messages are tables (`StartErrors`, `TransitionErrors`).
- Comments explain *why* (real clock for sessions, `SKIP LOCKED` in the sweeper, jitter in
  retries), not *what*.

## Docs and diagrams that no longer matched the code (fixed in the same PR)

| Document | Was | Now |
| --- | --- | --- |
| `state-appointment` | No HELD or EXPIRED; said the constraints cover CONFIRMED and IN_PROGRESS only | HELD → CONFIRMED / CANCELLED / EXPIRED added; constraints cover HELD too (V2 migration) |
| `use-case-staff` | Workshop controller, parts staff, packages, opening hours drawn as future work | Drawn as built (UC34, UC35, UC42, UC53, UC62, UC70, UC71) |
| `use-case-customer` | Parts ordering drawn as a customer use case (future); no hold | Hold and confirm (UC12) added; parts moved to staff, where the code puts it |
| `activity-book-package`, `activity-consult-first` | Booked straight to CONFIRMED | Hold, countdown, confirm, hold-expired path |
| `activity-workshop-day` | Advisor and technician only | Workshop controller re-plans the day first; technician can order parts |
| `dfd-context`, `dfd-level1` | No controller or parts staff; monitoring drawn as future | Processes 8 (dispatch) and 9 (parts), store D9, Prometheus and Jaeger |
| `class-application` | No `DispatchService`, `HoldSweeper`, `PartsService`, `PackageAdminService` | All services and their ports |
| `erd`, `class-domain-model` | No `parts`, `part_orders`, `part_order_lines`, `part_order_events`, `hold_expires_at` | Added |
| `hexagonal-architecture` | No parts / dispatch / package admin services and adapters, no tracing | Added |
| `docs/diagrams/README.md` traceability | Controller, parts, packages, opening hours marked Future | Marked Done with API and tests |
| Technical document (`01-…md`) | Five roles, 95 tests, booking went straight to CONFIRMED | Seven roles, hold → confirm in 4.4, tracing and Selenium in the stack, this review as 6.4 |
| SRS (`02-…md`) | Holds, parts, two roles, package admin listed as future work; BR-04 pointed at `BookingService.validateStart` | Requirements, rules, API table and scope updated; BR-04 → `Availability.validateStart` |

Not changed: the manual Test Specification (`03-…md`) has no step-by-step cases yet for holds,
the workshop controller, parts or the package catalogue. These flows are covered by
`FeaturesSpec` and the Selenium suites in `e2e/`; manual cases with screenshots are a follow-up.
The three PDFs in `service-scheduler-docs/` were re-exported from the Markdown (pandoc + headless Chromium), and `service-scheduler-docs/diagrams/` now matches `docs/diagrams/`.
