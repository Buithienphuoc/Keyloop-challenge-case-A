# My prompts

The prompts I gave Claude while building this project that had the biggest positive effect on
it. They were written in Vietnamese and are translated to English here, lightly trimmed. Each one
is followed by a short note on what it changed.

## 1. Scope: build what the brief asks for, main feature first

**Simplify to the brief**

> I've looked at everything you changed and it feels too cluttered. Make the web app simpler:
> stick closely to the brief, get the main feature right first, then the secondary features.

*Impact:* turned a sprawling app back into one that answers the Keyloop brief. Each role's menu
shrank to a few items, without deleting any data (PR #26).

**The three core requirements, and only three roles**

> Core requirements:
> 1. Resource-constrained booking: let a user request a service appointment for a specific
>    vehicle, service type and dealership at a desired time.
> 2. Real-time availability check: before confirming, check that both a ServiceBay and a
>    qualified Technician are available for the entire service duration.
> 3. Confirmed appointment record: on success, create a persistent Appointment record linking
>    the customer, vehicle, technician and service bay.
>
> You can drop the Advisor. We only need 3 roles: Customer, Technician and SystemManager (who
> can see everything).

*Impact:* gave a clear yardstick for every decision. The booking now assigns the qualified, free
technician it reserves, so the confirmed appointment names customer, vehicle, technician and bay,
exactly as requirement 3 says.

**Grey out, don't delete**

> When you scoped the project down earlier, in the diagrams: show what isn't implemented yet in
> grey, don't delete it.

*Impact:* the diagrams still show the full design, and a reviewer can see what this version does
and what is planned. Grey parts come with a legend (PR #28).

## 2. Security and environments

**Split dev and prod profiles**

> One more thing I don't think you've done, and it matters: split the profiles into dev and prod.
> In the dev profile we can show the username and password of every demo account, and it only
> runs on localhost. In the prod profile we must never write any account's username or password
> on the frontend. We already have a database schema for dev; make a separate one for production
> on Azure, and send me the existing accounts' credentials separately in a text file I can
> download. I don't want people messing with my project. You can schedule this for after CI/CD
> succeeds.

*Impact:* production got its own database, strong per-account passwords kept in a GitHub secret,
and a frontend bundle with no credentials in it. The demo password is rejected in prod (PR #17).

**Don't trust generated secrets**

> How can I be sure these passwords aren't leaked?
>
> I mean, you generated them for me, but the people who made you might be able to read them.

*Impact:* led to a full scan of the repo, git history, PRs and CI logs, and to a local script
that lets me rotate every prod password on my own machine, so Claude never sees the new ones
(PR #19).

**Will a deploy lose data?**

> After each deploy, do I need to worry about losing data?

*Impact:* confirmed that production data lives in a separate Azure PostgreSQL database, that
migrations only add, that the seed only runs on an empty database, and that Azure backs it up
daily.

## 3. Testing the main feature

**Double-click booking**

> Check with the automation-test agent what happens if, after the customer presses Book but
> before the Appointment is saved to the database, they press it two or more times. Is the
> session frozen, so that a slow page plus repeated clicks can't create two or more
> Appointments? This is the main feature, so test it thoroughly.

*Impact:* the tests found two real bugs: requests for the same vehicle could deadlock for 1–2
minutes, and "Change time" could leave an orphaned slot hold. Both were fixed, with Selenium and
50-request concurrency tests added (PR #21).

> Once you've found the bug, consider a workaround: once a slot is booked, disable the Book button
> entirely. The customer can only book again after cancelling that appointment. There's no need to
> keep that button working when it isn't needed. And check the other buttons too.

*Impact:* a vehicle with an active booking can't be booked again (backend returns 409), and every
other submit button was checked for duplicate requests.

**Keep the test suite lean**

> Look at which Selenium tests are redundant and delete them so the suite runs faster.

*Impact:* the end-to-end suite went from 69 to 33 tests that cover only the flows the three roles
use, and runs in about two minutes.

## 4. Domain model and permissions

**Role permissions**

> Redo the permissions like this:
> - Customer is the lowest. They only see their own Appointments and Services: whatever they
>   create, they must be able to see. When they book an Appointment they can choose its Workshop.
> - Admin, Advisor and Technician can read all Appointments and Services and update their status,
>   but never delete.
> - System admin must be able to read all of: the list of Customers, Advisors, Technicians,
>   Admins and Workshops.

*Impact:* turned vague access rules into explicit, testable ones, with no delete endpoint at all
and a role-permissions Selenium spec (PR #24).

**Service is not Appointment**

> A service has: customer, date and time, workshop, technician.
> An appointment has: customer, date and time, workshop, message to be advised, advisor.
> A technician may assign themselves to a scheduled job. An advisor may assign a technician, but
> not themselves or another advisor.

*Impact:* separated two concepts that had been mixed up in one table, with clear assignment
rules (PR #23).

## 5. Working with the AI team

**Specialised agents**

> I want more agents so we work more efficiently:
> - a unit-test agent, to check that the functions you write are sound;
> - a Selenium automation-test agent, to check that the use cases and activity diagrams are
>   coded with the right flow;
> - a docs and diagram agent, to check that your code is readable and follows SOLID.

*Impact:* unit tests, use-case-driven end-to-end tests and a readability/SOLID review ran in
parallel. This found a login bug that returned 500 instead of 401, plus places where the code and
the diagrams disagreed.

**Patch notes for every PR**

> Tell the docs agent to update the patch notes for every pull request. Put the patch notes in a
> separate .md file in the root folder, next to the README.

*Impact:* `PATCH_NOTES.md` gives a reviewer a readable history of every change.

**Built to show it scales, and to ship**

> I want to show that our system is scalable, which fits the philosophy of the Scala language.
> Please implement the secondary features from our technical document that aren't done yet. Then
> connect to Azure or AWS and set up a CI/CD flow so it becomes a complete product. Even if
> Keyloop doesn't work out, I want to use this to apply for other jobs.

*Impact:* the project went from a local demo to a deployed product with a CI/CD pipeline on
Azure that runs backend tests, a web build and Selenium on every PR.
