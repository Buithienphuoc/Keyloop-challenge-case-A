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
