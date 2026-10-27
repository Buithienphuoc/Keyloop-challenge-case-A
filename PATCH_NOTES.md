# Patch notes

One entry per pull request, newest first. Every PR adds its own entry at the top of
**Unreleased** in the same PR. When it merges, the entry moves under the date it was merged.

Entry format:

```
### #<PR> <title> (<branch>)
- **Added / Changed / Fixed / Docs / Tests:** one line per user-visible change.
- **Upgrade notes:** migrations, new env variables, anything a deployer must do (omit if none).
```

## Unreleased

### My prompts (`docs/myprompts`)
- **Docs:** `myprompts.md` at the repository root collects the prompts that shaped this project
  most (scope, security, testing, domain model, team setup), translated to English, each with a
  note on its impact.

### Diagrams: what is not in this version is grey, not gone (`docs/grey-not-in-version`)
- **Docs:** use case, activity, state and data flow diagrams keep every role and flow; what is not
  in this version of the web app (service advisor, dealership admin, workshop controller, parts
  staff, the consult-first flow) is drawn solid grey, with a legend. Dashed grey stays future
  work. The README and `docs/e2e-traceability.md` list those features again under "Not in this
  version". `service-scheduler-docs/diagrams` is kept in step.

### Every demo technician can sign in (`feat/demo-tech-logins`)
- **Changed:** the seed gives each technician a `@demo.vn` login (`tech.minh`, `tech.lan`,
  `tech.khoa`, `tech.quan`, `tech.bao`, next to `tech.hung` and `tech.tam`), so every name on
  screen belongs to a real account. Capacity and skills are unchanged.
- **Upgrade notes:** the seed only runs on an empty database. Locally, delete `.data/pg-54330`
  to re-seed. Production data is not touched; in production these accounts are created locked
  unless listed in `SEED_ACCOUNT_PASSWORDS`.

### Three roles, the brief's main feature first (`feat/simplify-by-role`)
- **Changed:** the web app has three roles, as the brief needs: the customer books, the
  technician carries out the job, the system manager sees everything. Advisor, dealership admin,
  workshop controller and parts pages are gone from the web app; those accounts and all data
  stay in the database, and signing in with one shows a short "not used in this version" note.
- **Changed:** a booking assigns the qualified, free technician it reserves (brief requirement
  3: the confirmed appointment names customer, vehicle, technician and bay). Customers see the
  technician on the confirmation and in My appointments.
- **Changed:** customer menu is Home, Book a service, My appointments, My vehicles. Home no
  longer shows advice-request cards or the green reply notice, and asks "what does your vehicle
  need?".
- **Changed:** the technician has one page, Service requests (their jobs by day), and closes a
  job once the customer has paid (AWAITING_PAYMENT → COMPLETED is now allowed for technicians).
- **Added:** "All data" for the system manager: appointments of every dealership, bays and
  technicians of each one, every customer's vehicle. API: `GET /api/appointments` and
  `GET /api/admin/bays|technicians?dealershipId=` are open to the system manager (read only),
  and `GET /api/system/vehicles` is new.
- **Tests:** Selenium cut from 69 to about 30 tests: removed the advisor, admin, controller and
  parts suites (BoardUpcoming, ConsultFirst, RolePermissions, WorkshopDay, BackOffice) and the
  slower DoubleSubmit cases already covered by the backend concurrency tests; a new
  BookingJourneySpec walks customer → technician → system manager.

### #24 Who sees what: every workshop for advisors, technicians and admins (`feat/role-permissions`)
- **Changed:** service advisors, technicians and dealership admins read every appointment and
  advice request of every workshop, and may move its status within the lifecycle rules (a
  technician starts and finishes jobs, also ones assigned to someone else; only an admin records
  the payment). Nothing can be deleted: cancelling is a status. Workshop controllers and parts
  staff still see only their own workshop. Customers still see only what they created, and
  choose the workshop when they book.
- **Added:** "All appointments" (`GET /api/appointments?dealershipId=all|<id>&status=`) for
  advisors, technicians and admins: their own workshop by default, another one, or all.
- **Added:** Advice requests has a workshop filter (`GET /api/staff/consultations?dealershipId=`)
  and is open to technicians, who may also review a request.
- **Added:** "People" for the system manager (`GET /api/system/users?role=`): every customer,
  advisor, technician and admin; workshops stay on "Dealerships".
- **Tests:** unit and API tests for the new rules; Selenium suite `RolePermissionsSpec`.

### #23 Separate service jobs from advisor appointments (`feature/services-and-advisor-appointments`)
- **Added:** an **appointment** is now a meeting with a service advisor: customer, time,
  workshop, the message to be advised and the advisor. Customers book one under "Meet an
  advisor"; it starts open, and an advisor takes it ("Take this appointment") and marks it done.
  API: `/api/advisor-appointments` (book, take, complete, cancel) and
  `GET /api/staff/advisor-appointments?open=true|date=…`.
- **Changed:** a booked job is a **service** (customer, time, workshop, technician). Booking still
  reserves a qualified, free technician so nothing can be double-booked, but the job stays
  **open** until a technician takes it for themselves or an advisor assigns one
  (`POST /api/appointments/{id}/technician`, `GET /api/staff/open-services`). An advisor cannot
  assign themselves or another advisor; a technician can only take open jobs, and only for
  themselves. Jobs have `technicianAssigned` in the API.
- **Changed:** after signing in, a technician lands on "New services" (open jobs) and an advisor
  on "New appointments" (open advisor appointments).
- **Tests:** assignment rules (unit), open services and advisor appointments end to end, and V4
  on a database holding V3 data.
- **Upgrade notes:** migration V4 is additive. It adds `technician_assigned_at` /
  `technician_assigned_by` to `appointments` and marks every existing job as assigned (so prod
  jobs keep their technician and do not reappear as open), and creates `advisor_appointments`.

### #22 New bookings show on the advisor's board (`fix/advisor-board-upcoming`)
- **Fixed:** a booking made by a customer seemed missing for the advisor. The board opens on
  today, and customers can only book from a little ahead (usually the next working day), so the
  new job sat on a day the advisor had not opened. The board now has a "Coming up" list with the
  dealership's next 20 confirmed bookings; one click opens a booking on its day.
- **Added:** `GET /api/staff/appointments/upcoming` (advisor, admin, workshop controller).
- **Changed:** the board's side list says "Jobs this day" when it is not showing today.
- **Tests:** a unit and an API test for the new list, and the Selenium suite `BoardUpcomingSpec`.

### #21 Pressing Book many times never books twice (`fix/double-booking`)
- **Fixed:** several bookings or holds for the same vehicle at once (a double tap, two tabs, a
  retry without an Idempotency-Key) waited on each other inside the database and took one to
  two minutes to answer while PostgreSQL broke the deadlocks one by one. They now queue on a
  per-vehicle lock: the first is booked and every other one is answered at once.
- **Changed:** a vehicle has at most one live appointment (on hold, booked or in the
  workshop). Booking it again is refused with `409 VEHICLE_HAS_APPOINTMENT`, which names that
  appointment, until it is cancelled or done. My vehicles, the vehicle step, Advice requests
  and Home lock "Book a service" / "Pick a time" for such a vehicle and say which booking to
  cancel in My appointments.
- **Fixed:** changing the time while the first hold was still on its way left that hold
  behind (an orphan HELD for ten minutes); a new hold now waits until the earlier one is given
  back. Confirm is sent once however often it is pressed.
- **Changed:** the web client sends an identical change (POST, PATCH, PUT, DELETE) only once
  while it is on its way, so no button can write twice from clicks inside one frame; the admin
  and package on/off toggles are also disabled while saving.
- **Tests:** backend: 50 concurrent presses per case (new key per press, no key, holds from
  several tabs, hold and booking together, overlapping times, 50 Confirms on one hold), each
  answered in under 15 s with one appointment; FeaturesSpec checks the one-live-appointment rule
  and that a lapsed hold frees the vehicle. e2e: `DoubleSubmitSpec` (9 tests, slowed network,
  5 clicks at once, double-clicks, two tabs, locked buttons). WorkshopDaySpec books three
  vehicles and moves to days no other suite uses (it shared BookingJourneySpec's day when
  days 4 and 5 were a weekend).

### #20 Awaiting payment step and the admin's job list (`feature/awaiting-payment`)
- **Added:** a job now goes IN_PROGRESS → AWAITING_PAYMENT → COMPLETED. The technician or
  advisor finishes it once the customer has checked the vehicle; only the dealership admin
  closes it after the customer has paid ("Payment received"). There is no bank integration, so
  the payment is recorded by hand.
- **Added:** "Jobs & payments" for the dealership admin (`GET /api/admin/jobs`): every job,
  newest first, with tabs for New, In progress, Awaiting payment, Finished and Cancelled &
  missed.
- **Changed:** customers see "Awaiting payment" with a note that the vehicle is ready, then
  "Finished" (was "Done"). Staff may still start a job before its booked time (Q1, decided
  27 October 2026).
- **Upgrade notes:** migration V3 adds the status, keeps one live appointment per approved
  request while it awaits payment, and adds an index on (dealership, status).

### #18 Azure setup uses a standard Container Apps environment (`fix/azure-standard-env`)
- **Fixed:** the apps ran on an "express" Container Apps environment, which has no internal
  service discovery (the web app's nginx could not reach `scheduler-api` by name) and kept
  failing image pulls with "Authentication failed" after app updates. `deploy/azure/setup.sh`
  now creates the environment with `--environment-mode ConsumptionOnly`, and replaces an express
  environment left by an earlier run (its two apps are deleted and made again; the database is
  not touched).
- **Upgrade notes:** run `setup.sh` again in Cloud Shell, then re-run CI/CD on master. The web
  address changes; the script prints the new one.

### #16 Deploy re-applies the registry sign-in before pulling images (`fix/deploy-registry-auth`)
- **Fixed:** the deploy failed with "Authentication failed when pulling container image" although
  the previous rollout had pulled from the same registry. Before updating the images, the job now
  sets both apps' registry user name and password again (taken from the registry itself).
- **Changed:** the failure report shows each app's port, registries and secret names instead of
  log lines, which express environments do not stream.

### #15 Deploy sets the app ports before the new images (`fix/deploy-ingress-order`)
- **Fixed:** the first deploy to Azure failed at "Roll out the new images": the new API revision
  started while ingress still pointed at the placeholder's port 80, so it never became ready.
  Ingress now moves to 8081/8080 first, then the images are updated.
- **Changed:** when a deploy fails, the job adds the Azure error, each app's revisions and its
  latest system and console logs to the run summary as annotations.

### #14 GitHub Actions can sign in to Azure (`fix/azure-oidc-subject`)
- **Fixed:** the CI/CD "Sign in to Azure" step failed with AADSTS700213 because GitHub names this
  repository in its OpenID Connect token by numeric ids (`Buithienphuoc@37607399/Keyloop-challenge-case-A@1432964156`).
  `deploy/azure/setup.sh` now registers federated credentials for that form as well as `owner/repo`.
- **Upgrade notes:** run the script again (or add the two credentials by hand), then re-run CI/CD on master.

### #13 Azure setup re-creates apps that still have a managed identity (`fix/azure-express-identity`)
- **Fixed:** re-running `deploy/azure/setup.sh` no longer stops at "Allow the web app's origin on
  the API" with "ExpressEnvironmentFeatureNotSupported": an app the first version of the script
  gave a system-assigned identity (still on the placeholder image) is deleted and made again.
- **Upgrade notes:** run the script again.

### #12 Azure setup works on express Container Apps environments (`fix/azure-express-env`)
- **Fixed:** `deploy/azure/setup.sh` no longer stops at the web app with
  "ExpressEnvironmentFeatureNotSupported": both apps now pull images with the registry's admin
  user instead of a system-assigned managed identity, and an app created by an earlier run is
  switched over.
- **Upgrade notes:** if a run stopped at "Web app scheduler-web", run the script again.

### #11 Azure setup works with the current Azure CLI (`fix/azure-setup-cli`)
- **Fixed:** `deploy/azure/setup.sh` no longer stops at PostgreSQL ("--database-name ... only
  applies to elastic clusters"); the `scheduler` database is created with its own command.
- **Fixed:** re-running the script in an ephemeral Cloud Shell reuses the existing resources
  instead of creating a second set, and applies a fresh database password to the server and API.
- **Upgrade notes:** if a run stopped at the PostgreSQL step, run the script again.

### #9 Patch notes moved to their merge date (`docs/patch-notes-2026-10-26`)
- **Docs:** the entries for #3–#8 and #10 move from Unreleased to 26 October 2026, the day they merged.

## 2026-10-26

### #10 E2E traceability after the diagram redraw (`docs/e2e-gaps-after-3`)
- **Docs:** `docs/e2e-traceability.md` marks the diagram gaps fixed in #3; starting a job
  before its day stays open as question Q1.

### #8 End-to-end tests for every use case and activity branch (`e2e/use-case-coverage`)
- **Tests:** 31 new Selenium tests (57 in total): `ConsultFirstSpec`, `WorkshopDaySpec`,
  `BookingRulesSpec` and `AdminSpec` walk every drawn use case and every branch of the three
  activity diagrams in a real browser.
- **Docs:** `docs/e2e-traceability.md` maps each use case and branch to its test and lists
  where the code and the diagrams disagreed (fixed in #3).

### #7 Backend readability and SOLID refactor (`refactor/backend-review-findings`)
- **Changed:** `BookingService.book` is split into small steps. A `UserAccounts` port replaces
  the concrete `IdentityService` in the admin and tenant services. Shared helpers replace
  duplicated code: `requireDealership`, `assertCanSee`, `Holds.releaseExpired` and
  `LocalDay.bounds`. `PartsRepo.getPart` reads one row.
- **Fixed:** holds released by a job move are now counted in `holds{event="expired"}`.
- **Fixed:** moving only the bay of a job whose technician has since been deactivated no
  longer fails with `UNKNOWN_TECHNICIAN`.
- **Changed:** a user that no longer exists answers `404 NOT_FOUND` instead of
  `400 USER_NOT_FOUND`.

### #6 Booking page split into steps (`refactor/book-page-steps`)
- **Changed:** `BookPage.tsx` (594 → 244 lines) is split into `VehicleStep`, `ServiceStep`,
  `TimeStep`, `ConfirmStep` and `BookedPanel`, plus a wizard reducer and a `useHold` hook.
  The screens and texts are unchanged.

### #5 Vehicle model-year limit after New Year (`fix/vehicle-year-limit`)
- **Fixed:** the newest accepted model year (this year + 1) is worked out on every request.
  Before, a server that kept running past New Year refused the new year's models until it
  was restarted.

### #4 Backend unit tests (`test/backend-unit-tests`)
- **Tests:** 10 new specs on in-memory fakes of the ports, raising the unit tests from 67
  to 222. Covers every application service, the request validation and the domain rules.
- **Fixed:** a stored password hash with invalid base64 or 0 iterations made sign-in answer
  500. It now answers 401.

### #3 Docs and diagrams in sync with the code (`docs/sync-docs-with-code`)
- **Docs:** all diagrams are redrawn for holds, the workshop controller, parts, opening hours
  and the package catalogue. The Technical Document is v2.2, the SRS v1.1, and the three PDFs
  are re-exported.
- **Docs:** a second readability and SOLID review is in `docs/code-review-2026-10-26.md`.
  It has findings R1–R13 and the open questions Q1 (starting a job before its day) and Q2
  (the advice form's default dealership).
- **Docs:** this patch notes file.

### #2 Remaining features, scale-out proof and CI/CD to Azure (`feature/scalable-extras`)
- **Added:** a hold step before confirming (10 minutes, released by a sweeper), the workshop
  controller (workload, moving jobs), parts staff and parts orders, opening hours editing,
  the service package catalogue and OpenTelemetry tracing.
- **Added:** Dockerfiles, `docker compose --profile full` (3 API instances behind nginx), a
  load test, CI/CD to Azure Container Apps and the first 26 Selenium tests.
- **Fixed:** behind nginx, the CORS filter refused the browser's sign-in (`CORS_ORIGIN`).
- **Upgrade notes:** migration V2. The deploy job runs only once the `AZURE_*` repository
  variables are set.

## 2026-10-25

### #1 Colour-coded ERD, local timestamps, port 8081 (`update/erd-local-time-port-8081`)
- **Docs:** a colour-coded ERD covers every table. The README and dev scripts say port 8081.
- **Changed:** "created" times show in the dealership's time zone instead of UTC.
- **Changed:** `npm run dev` says to run `npm run setup` first when the frontend
  dependencies are missing.
