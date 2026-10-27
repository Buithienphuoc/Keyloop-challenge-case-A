# Use cases → end-to-end tests

The app follows the brief with three roles: the customer books, the technician carries out the
job, the system manager sees everything. Each use case below is walked in a real browser by a
Selenium test in `e2e/`; the backend tests (`backend/test/…`) prove the rules.

Run: `docker compose --profile full up -d --build`, then `cd e2e && sbt test`. Against
`npm run dev` instead: `BASE_URL=http://localhost:5173 sbt test`.

| Use case | e2e test (`e2e/src/test/scala/scheduler/e2e/…`) |
| --- | --- |
| UC01 Register an account | `BookingJourneySpec` › creates an account; every spec that books registers its own customer |
| UC02 Sign in / sign out | `AuthSpec` (wrong password, demo buttons, sign-out, start page per role) |
| UC03 Manage my vehicles | `BookingRulesSpec` › adds a motorbike and a car and lists both with their type |
| UC04 Book a maintenance package | `BookingJourneySpec` › holds a time, changes it, and confirms |
| UC08 View my appointments | `BookingJourneySpec` › sees the confirmed appointment with its technician; sees the job as finished |
| UC09 Cancel an appointment | `DoubleSubmitSpec` and `BookingRulesSpec` clean up by cancelling from My appointments |
| UC11 Add to my calendar | `BookingRulesSpec` › downloads the booking as a calendar file |
| UC20 Real-time availability | `BookingRulesSpec` › only packages for the vehicle type; cannot do the job here |
| UC21 Allocate bay and technician | `BookingJourneySpec` › the booking names its technician; All data shows customer, vehicle, technician and bay |
| UC22 Nearest free alternatives | `BookingRulesSpec` › offers the nearest free times when someone takes the chosen time first |
| UC23 No double booking | `DoubleSubmitSpec` (UI); `integration/BookingConcurrencySpec` in the backend (50 concurrent requests) |
| UC40 Technician's jobs | `BookingJourneySpec` › lands on Service requests and finds the job with the customer's note |
| UC41 Start, finish and close a job | `BookingJourneySpec` › starts the job, finishes it, and closes it once paid |
| UC60 Onboard a dealership | `OnboardingSpec` › onboards a dealership together with its first admin |
| UC61 See every dealership and its data | `SystemManagerSpec`; `OnboardingSpec` › sees its capacity |

## Not in this version

Built and tested in the backend before the scope-down to three roles; drawn solid grey in
[docs/diagrams](diagrams/README.md). Their pages and the Selenium tests that walked them
(`ConsultFirstSpec`, `WorkshopDaySpec`, `RolePermissionsSpec`, `BoardUpcomingSpec`, the admin part
of `AdminSpec`) were removed with the pages; the backend tests in the last column still run.

| Use case | Backend test (`backend/test/scheduler/…`) |
| --- | --- |
| UC05 Ask for an inspection | `ApiSpec` › inspection on request |
| UC06 Ask for a customisation | `ApiSpec` › customisation request |
| UC07 Book an approved request | `ApiSpec` › the customer books it |
| UC10 Withdraw a request | `ApiSpec` › UC10 |
| UC30 Workshop day board | `ApiSpec` › shows it everywhere it should |
| UC31 Review an advice request | `ApiSpec` › advisor turns it into a schedulable job |
| UC32 Record start, finish, no-show (advisor) | `ApiSpec` › no-show only after start |
| UC33 Cancel a booking (advisor) | `ApiSpec` › UC33 |
| UC34 See the day's workload | `FeaturesSpec`, `WorkloadSpec` |
| UC35 Move a job | `FeaturesSpec` › moves a job to a free, qualified technician |
| UC36 Jobs by status, record payment (admin) | `ApiSpec` |
| UC42 Order parts for a job | `FeaturesSpec` › lets a technician order parts for a job |
| UC50 Manage service bays | `ApiSpec` › admin adds a bay |
| UC51 Manage technicians | `ApiSpec` › admin adds a technician |
| UC52 Manage staff accounts | none |
| UC53 Opening hours and booking rules | `FeaturesSpec` › applies new hours to availability at once |
| UC70 Manage parts stock | `FeaturesSpec` › keeps the shelf to parts staff and admins |
| UC71 Fulfil parts orders | `FeaturesSpec` › never issues more than is on the shelf |
