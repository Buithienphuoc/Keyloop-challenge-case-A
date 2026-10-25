# AI collaboration log

Each entry: what the AI produced, how the problem was caught, and what was changed.
Keep adding entries as you work; three to five real ones are the strongest evidence for
the "AI Engineering & Verification" criterion.

## 1. Slot times shifted by one hour on daylight-saving days

- **What the AI wrote:** `candidateStarts` built each start time as
  `startOfDay.plus({ minutes: 480 })` to get 08:00.
- **How it was caught:** a unit test pinned to the Europe/London clock change
  (29 March 2026) expected the first slot at `08:00+01:00` and got `09:00+01:00`.
  Midnight-plus-minutes counts real elapsed time, and that day has only 23 hours.
- **Fix:** set the local wall-clock time instead (`set({ hour, minute })`).
- **Lesson:** time-zone code needs tests on the awkward days, not just "a normal Monday".
  Vietnam has no DST, so this would never show up in local manual testing, but Keyloop's
  customers are mostly in the UK and Europe.

## 2. A green concurrency test that did not test what it claimed

- **What the AI wrote:** a "50 customers race for one slot" test plus one raw-SQL check,
  and all 53 tests passed on the first run.
- **How it was caught:** passing first time is a reason to doubt, so the code was
  mutated on purpose. Deleting the *technician* `EXCLUDE` constraint from the migration
  left every test green. Cause: all racing requests choose the same first bay (best fit
  is deterministic), so the *bay* constraint alone rejected them; the technician rule was
  never exercised on its own.
- **Fix:** one direct database test per constraint (same bay only / same technician only
  / same vehicle only / back-to-back allowed). Each constraint was then deleted in turn and
  each deletion now turns at least one test red.
- **Lesson:** a test that passes is not evidence until you have seen it fail for the
  right reason. Mutation checks are cheap and worth doing on the core invariants.

## 3. "Jump to the first free day" stopped after one day

- **What the AI wrote:** when today was full, the booking page moved to the next date once,
  then stopped, because the effect bailed out as soon as a date was set.
- **How it was caught:** reading the code back before running it; the guard `if (date) return`
  made a second step impossible.
- **Fix:** an explicit `autoPick` flag that stays on until a day with a free slot is found, the
  user picks a day, or the dates run out. Seen working in the browser: on a Saturday evening the
  page opens on Monday.

## 4. Mobile layout three times wider than the phone

- **What the AI wrote:** single-column grids as `grid-template-columns: 1fr`.
- **How it was caught:** headless-browser screenshot at 390 px; `scrollWidth` was about 1400 px.
  The horizontally scrolling day strip forced the `1fr` column to its content width.
- **Fix:** `minmax(0, 1fr)` for every one-column grid; re-measured `scrollWidth` = 390.

## 5. Description text invisible on the main home card

- **What the AI wrote:** `.need p` and `.need-main p` with equal specificity; the later rule won
  and painted grey text on the blue card.
- **How it was caught:** screenshot review of the home page.
- **Fix:** `.need.need-main p`. Lesson: check contrast on the real render, not in the CSS.

## 6. Deadlocks under a burst of retries with one Idempotency-Key

- **What the AI wrote:** concurrent requests with the same key each inserted, and the loser
  replayed the winner's row after a unique or exclusion violation. Tests passed.
- **How it was caught:** a new 1000-request load test, then re-running the existing 50-request
  idempotency test in a loop: about one run in four returned some 500s. The logs showed
  PostgreSQL deadlock errors (`40P01`): two transactions each waiting on the other's unique-key
  and exclusion checks. Each deadlock also cost a one-second `deadlock_timeout`, which is why
  that test took 14 s.
- **Fix:** a transaction-scoped advisory lock on customer + key, so same-key requests queue
  instead of racing; the ones that wait find the committed row and replay it. Deadlock and
  serialization errors are also retried as a safety net (`booking_conflicts_total{reason="deadlock"}`).
  A regression test runs the race ten times; it fails without the fix. The test now takes 0.4 s.

## 7. Availability was CPU-bound on time-zone arithmetic

- **What the AI wrote:** correct slot computation that called Luxon zone operations for every
  technician at every start time.
- **How it was caught:** the load test showed 1000 availability reads taking 43 s; a CPU profile
  put about 40% of the time in Luxon's offset lookup (`Intl`), called from `isOnShift`.
- **Fix:** compute the job's local window once per start time and compare integers per
  technician; on days without a daylight-saving change use a fixed-offset zone (the same instants,
  much cheaper). Booked minutes for the tie-break are computed once, not inside the sort
  comparator. The DST unit tests still pass. Same burst: 43 s → 4.9 s.

## 8. Free-looking slots that booking would refuse

- **What the AI wrote:** `daySlots` checked lead time but not `maxDaysAhead`, while
  `validateStart` checked both. A far date showed free slots, and the "nearest alternatives" after
  a lost race could fall beyond the dealership's booking window (it searches 14 days, a dealership
  may accept only 7).
- **How it was caught:** a line-by-line review for the SOLID refactor, comparing the two
  functions that should agree.
- **Fix:** one shared `lastBookableDay` rule; such slots are now `TOO_FAR_AHEAD` (shown as
  "Not open yet"). A property test checks that every slot offered as available passes
  `validateStart`, over random rules, dates and clocks. Without the fix fast-check finds a
  counter-example at once.
- **Also caught in review, before it shipped:** the refactor briefly passed the test clock into
  session expiry, while PostgreSQL checks expiry with its own clock. Reverted, with a comment.

## 9. Moving the backend from TypeScript to Scala / Play

- **What happened:** the first build environment could not reach Maven Central, so the backend
  was written in TypeScript first. Once network access was opened, the AI ported it to Scala 3 +
  Play 3, keeping the module layout, class names, ports, SQL schema and HTTP contract, so the
  React app and the diagrams did not change.
- **How it was verified:** the whole TypeScript test suite was ported spec by spec (same cases,
  same expected values), plus the load test, and all of it passes against real PostgreSQL. The
  React app was then driven in a headless browser against the Scala API. The TypeScript backend was
  then removed, so there is one backend to maintain.
- **Problems the port itself surfaced:** entries 10 to 12.

## 10. Jackson version clash stopped the packaged server from starting

- **What the AI wrote:** a build that compiled cleanly, but two of its dependencies (Flyway and
  the Logstash encoder) pulled in a newer Jackson than the one Play is built against.
- **How it was caught:** booting the packaged server (`sbt stage`) before writing any tests: it
  failed at startup with "Scala module 2.14.3 requires Jackson Databind < 2.15.0, found 2.19.1".
  `sbt compile` cannot see this; only running it does.
- **Fix:** pin every Jackson module to one version with `dependencyOverrides` in `build.sbt`.

## 11. Connection-pool starvation under a burst

- **What the AI wrote:** use cases that open a `db.run` and, inside it, call a port that opens
  its own `db.run` (for example reading the catalogue while computing a job's requirement).
  Correct, and every functional test passed, including 50 concurrent bookings.
- **How it was caught:** the 1000-request load test: requests hung for 30 s and failed with
  "Connection is not available (total=20, active=20, waiting=161)". All 20 pooled connections
  were held by requests waiting for a second connection, so none could finish.
- **Fix:** `PgDb` remembers the session the current (virtual) thread already holds; a nested
  `run` reuses it and a nested `tx` joins the enclosing transaction. No use case changed. The
  same burst then completed with no errors.

## 12. Connections dropped when 1000 clients connect at once

- **What the AI wrote:** Play's default server settings.
- **How it was caught:** after entry 11 was fixed, the first burst still failed with "HTTP/1.1
  header parser received no bytes" while later bursts (on reused keep-alive connections) passed.
  That pattern pointed at connection setup, not request handling: Pekko HTTP's accept backlog
  defaults to 100.
- **Fix:** `pekko.http.server.backlog = 1024` in `application.conf`, with a comment saying why.
