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
