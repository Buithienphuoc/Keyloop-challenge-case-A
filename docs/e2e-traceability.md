# Use cases and activity diagrams → end-to-end tests

Every drawn use case and every branch of the three activity diagrams in
[docs/diagrams](diagrams/README.md), mapped to the Selenium test in `e2e/` that walks it in a
real browser. The backend tests (`backend/test/…`) prove the rules; these prove that a person
can actually follow the drawn flow on screen.

Run: `docker compose --profile full up -d --build`, then `cd e2e && sbt test` (57 tests, about
two minutes). Against `npm run dev` instead: `BASE_URL=http://localhost:5173 sbt test`.

## Use cases

| Use case | e2e test (`e2e/src/test/scala/scheduler/e2e/…`) |
| --- | --- |
| UC01 Register an account | `BookingJourneySpec` › creates an account; every new spec registers its own customer |
| UC02 Sign in / sign out | `AuthSpec` (wrong password, demo buttons, sign-out, start page per role) |
| UC03 Manage my vehicles | `BookingRulesSpec` › adds a motorbike and a car and lists both with their type |
| UC04 Book a maintenance package | `BookingJourneySpec` › holds a time, changes it, and confirms; `WorkshopDaySpec` › books three services |
| UC05 Ask for an inspection | `ConsultFirstSpec` › asks for an inspection |
| UC06 Ask for a customisation | `ConsultFirstSpec` › asks for a customisation |
| UC07 Book an approved request | `ConsultFirstSpec` › sees the approval … and picks a time; shows the request as booked |
| UC08 View my appointments and requests | `BookingJourneySpec` › sees the booking as confirmed; `WorkshopDaySpec` › sees the two finished jobs as done |
| UC09 Cancel an appointment | `BookingJourneySpec` › cancels the booking; `ConsultFirstSpec` › cancelling … puts the request back |
| UC10 Withdraw a request | `ConsultFirstSpec` › withdrawing the request closes it |
| UC11 Add to my calendar | `BookingRulesSpec` › downloads the booking as a calendar file (checks the `.ics` content) |
| UC20 Real-time availability | `BookingRulesSpec` › only packages for the vehicle type; cannot do the job here |
| UC21 Allocate bay and technician | every booking (the assigned bay and technician appear on the board and in My jobs) |
| UC22 Nearest free alternatives | `BookingRulesSpec` › offers the nearest free times when someone takes the chosen time first |
| UC30 Workshop day board | `BookingJourneySpec` › finds the new booking; `WorkshopDaySpec` |
| UC31 Review an advice request | `ConsultFirstSpec` › approves the inspection; declines the customisation (reason required) |
| UC32 Record start, completion, no-show | `WorkshopDaySpec` › does not offer no-show before the start; starts … then marks it done |
| UC33 Cancel a booking (advisor) | `WorkshopDaySpec` › cancels a booking before work starts |
| UC40 View my jobs | `BookingJourneySpec` › sees the job in My jobs; `WorkshopDaySpec` |
| UC41 Start and finish a job | `WorkshopDaySpec` › starts and finishes their own job from My jobs |
| UC50 Manage service bays | `AdminSpec` › adds a service bay and takes it out of service |
| UC51 Manage technicians | `AdminSpec` › adds a technician with skills and a login; deactivates the new technician |
| UC52 Manage staff accounts | `AdminSpec` › creates a service advisor account; the new advisor signs in |
| UC60 Onboard a dealership | `AdminSpec` › onboards a dealership together with its first admin; its admin signs in |
| UC61 Monitor dealerships | `AdminSpec` › sees its capacity: no bays, no technicians, nothing booked; `BackOfficeSpec` |
| Admin generalises advisor | `WorkshopDaySpec` › the admin moves a job on the board; `AdminSpec` › an admin starts at the day board |
| *Drawn as future before #3:* re-plan bays and technicians | `BookingJourneySpec` › the workshop controller moves the job |
| *Drawn as future before #3:* fulfil parts orders | `BookingJourneySpec` › requests a part; restocks one unit, then issues the order |
| *Drawn as future before #3:* packages, opening hours | `BackOfficeSpec` › the package catalogue; saves the opening hours |

## Activity diagram branches

| Diagram | Branch | e2e test |
| --- | --- | --- |
| Book a package | Has a vehicle? no → add one | `BookingJourneySpec`, `BookingRulesSpec` |
| | Only packages for this vehicle type | `BookingRulesSpec` |
| | Can this dealership do the job? no → "cannot do this job here" | `BookingRulesSpec` (car at District 1), `AdminSpec` (a new, empty dealership) |
| | Open the first day with a free time | used by every booking (the day strip opens on a bookable day) |
| | Booked? yes → reference, technician, ticket | every booking |
| | Booked? no → "taken a moment ago" + nearest times → book one | `BookingRulesSpec` |
| | Optionally add to calendar | `BookingRulesSpec` |
| Consult first | Customer sends inspection / customisation | `ConsultFirstSpec` |
| | Advisor: can the workshop do it? no → decline with a reason → REJECTED, customer reads it | `ConsultFirstSpec` |
| | yes → time, skill, bay, estimate, message → APPROVED | `ConsultFirstSpec` |
| | Customer sees the reply (duration, estimate, note) | `ConsultFirstSpec` |
| | Pick a time → appointment CONFIRMED, request BOOKED | `ConsultFirstSpec` |
| | Cancels before the start → request back to APPROVED | `ConsultFirstSpec` |
| | Withdraw → CANCELLED | `ConsultFirstSpec` |
| Workshop day | Vehicle arrived → start → IN_PROGRESS → done → COMPLETED (advisor) | `WorkshopDaySpec` |
| | Same, by the assigned technician from My jobs | `WorkshopDaySpec` |
| | Not arrived, booking dropped → advisor cancels → CANCELLED, slot freed | `WorkshopDaySpec` |
| | Not arrived, start passed → no-show | only the guard: no-show is not offered before the start (a past start cannot be booked from the browser; `ApiSpec` covers the move) |
| | No way back from COMPLETED | `WorkshopDaySpec` (no action buttons after Done, no Cancel for the customer) |

Not reachable from a browser and left to the backend suite: the hold timing out after 10
minutes, a technician moving someone else's job (the page never offers it, `ApiSpec` checks the
403), and the five-attempt retry inside one booking.

## Where the code and the diagrams disagreed

Found while writing the tests above, against the diagrams as they were before #3. #3 redrew
the diagrams and updated the SRS; what is left open is listed in
[docs/diagrams/README.md](diagrams/README.md#gaps-found-while-tracing).

1. **The hold step was missing** from the booking activity and the state diagram (HELD and
   EXPIRED). Fixed in #3.
2. **Built features were drawn as future work** (workshop controller, parts staff, re-plan,
   parts orders, packages, opening hours). Fixed in #3.
3. **"Has the vehicle arrived?" is not checked against the date.** Staff can start a job on any
   day ahead (`WorkshopDaySpec` starts and finishes jobs booked five days out). Still open:
   #3 notes it on the diagram, and whether the code should refuse is question Q1 in
   `docs/code-review-2026-10-26.md`.
4. **Moving a job was in no diagram.** Fixed in #3 (UC35).
5. **The diagrams README said nothing contradicts a drawn flow.** Fixed in #3.

One small usability point found on the way, not a diagram mismatch: the advice request form
preselects the first dealership in the list (Riverside, Thu Duc), not the one the customer last
booked at, so a customer of District 1 who does not look sends the request to the wrong
workshop.
