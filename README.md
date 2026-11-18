# Makerere Student Election Monitor

A shared, public-facing dashboard for following interim online-election declarations from configured tally centres. Voters can watch without signing in. Administrators configure the current posts, candidates, tally centres, and individual agent accounts; each field agent can submit aggregate declarations only for their assigned centre.

The monitor is separate from Makerere's voting portal. It does not retrieve or certify official results, count ballots, or collect student numbers, passwords, emailed voting tokens, or individual ballot choices.

## Public deployment (Render)

The project includes a Docker image and Render Blueprint. It is not deployed by this repository; deployment requires your own Render account and an administrator password.

1. Push this project to a private or public Git repository you control.
2. In Render, create a new Blueprint from that repository and review the service and persistent-disk plan/cost before deploying.
3. When prompted for `ADMIN_INITIAL_PASSWORD`, set a unique password of at least 12 characters. Store it in a password manager; do not put it in source control or chat.
4. Deploy the service. Render provides the public HTTPS address; share that URL with voters.
5. Sign in as `admin`, change the initial password, add the current official posts/candidates and each tally centre (college, school/institute, centre name), then create named agent accounts with unique one-time passwords and a centre assignment. Share credentials with each agent through a separate trusted channel.
6. Ask agents to sign in, verify their assigned centre, and change the one-time password before submitting anything. Agents can then submit aggregate figures with the declaration/source reference and log classified field observations. Submissions appear on the public page immediately as **interim monitor reports**. A later correction is retained in the activity log and replaces that centre's earlier snapshot in current totals.
7. Administrators can review urgent/open observations, record resolution notes, reopen issues, reassign agents, reset agent passwords, and disable or reactivate accounts. Password resets sign out the agent and require a new personal password at next sign-in.

The Blueprint uses one service instance and a persistent disk for the election file. Do not scale it horizontally: this lightweight file-backed edition is designed for one server process. Back up the persistent disk regularly and restrict Render dashboard access to trusted administrators. Sessions are held in server memory and expire after 12 hours of inactivity; a restart signs agents out.

## Run locally

1. Install a JDK 17 or newer.
2. Double-click `run.bat`.
3. Open `http://127.0.0.1:8080`. On a fresh local install, the terminal prints a one-time generated administrator password. Save it before closing the terminal.

The local server binds only to loopback. Do not expose it directly to the internet; use the hosted HTTPS deployment for multi-device access.

## Operating workflow

- **Public / voter view:** read-only, no login required; live updates arrive through server-sent events, with a periodic refresh fallback.
- **Page navigation:** use the header menu button to show or hide the sidebar on desktop, or open the navigation drawer on phones and narrow screens. Switch between overview, candidate results, tally centres, latest declarations, and official information; the selected page is restored on refresh. Signed-in staff also get a field-operations page. The page stays in place while the mobile drawer is open; close it with its close button, Escape, or the backdrop. Tally-centre rows become stacked cards instead of requiring sideways scrolling.
- **Appearance:** use the header button to switch between the default Luna Dark theme and a light theme; the choice is saved in this browser. Overview metrics, candidate results, and agent summary tiles use square cards.
- **Latest declarations:** the activity page presents recent interim reports as a chronological feed with the source, reporting post, tally centre, and college/school clearly separated.
- **Candidate graph:** each candidate card shows vote count and vote-share percentage; the bar visualizes that same share.
- **Post selector:** voters choose a configured Guild post first, then narrow results by college or by a standalone school/unit. Until the election team configures active posts, the public selector shows the 92nd Guild post templates as references only; each is marked as a template and has no results.
- **Administrator:** configure active posts from the current official notice first, then add candidates to those posts; register tally centres, create and maintain individually assigned field-agent accounts, and triage a private observation queue. Resolution notes, reporter identity, and resolution history are audit metadata visible only inside the restricted workspace.
- **Field agent:** signs in with an individually assigned account and must replace the one-time password before accessing reporting actions. The focused agent desk separates declaration entry, private observations, and centre activity. The account can submit aggregate candidate totals and source reference only for its assigned tally centre, and log routine or urgent, categorized field observations. Agents can see follow-up for observations at their own centre; the public cannot see the observation log.
- **Live totals:** only the latest submitted snapshot for each post/centre pair contributes to candidate totals. Every submission is retained in the update log, so corrections do not double count or erase the earlier declaration.
- **College/school performance:** voters can filter by post, college, or a stand-alone school/unit to compare declared candidate totals across configured centres.
- **Official academic directory:** college options place Makerere's official initials before the full name in parentheses (for example, `CAES (College of Agricultural and Environmental Sciences)`). Stand-alone unit labels use the same style for documented initials such as MakSPH and IGDS. Where a school belongs to a listed college, reports use that parent college rather than duplicating the school. The school filter is for officially listed stand-alone schools/units without a parent college.
- **No fabricated setup:** the workspace begins without active posts, candidates, tally centres, or reports. The built-in 92nd Guild templates are category references, not confirmation of the current ballot. Use the active Electoral Commission notice for actual ballot details.

Treat public submissions as unverified, interim observations—not official results. The source/reference field is visible to voters. Agents should cite an official declaration reference and must not enter personal or confidential information there. Field observations are restricted to administrators and agents assigned to the same tally centre. Keep them factual and operational: never enter student numbers, voting credentials, individual vote choices, or unnecessary personal details. Agree a verification, escalation, and correction procedure with election organizers before going live.

## Makerere context

Makerere's official voting guidance describes online voting through a student-number login and emailed token. This monitor must never request or store either. The Dean of Students page describes election oversight and Halls of Residence as voting centres. The college directory includes the units listed on Makerere's official academic-units page, including the School of Law, which that page lists among college-level units. The standalone-school selector includes the School of Public Health and the Institute of Gender and Development Studies (IGDS) listed in Makerere's official colleges-and-departments page. Post templates in the setup form come from the 92nd Guild Electoral Commission call dated 6 March 2026. The notice spells out GRC (Guild Representative Council) and CGC (College Guild Council); other posts retain their official names where the notice does not provide a short form. Those templates do not establish the current cycle's ballot, candidates, timetable, or tally-centre roster. Confirm active official notices before setup.

Official references:

- [Official student voting guidance](https://mak.ac.ug/students-guild/how-vote)
- [Students' Guild: elections and current Guild documents](https://mak.ac.ug/students/students-guild)
- [Dean of Students: election oversight and voting centres](https://mak.ac.ug/university-governance/university-management/central-university-management-committee/dean-students)
- [Official academic units and college-level units](https://mak.ac.ug/study-mak/academic-units)
- [Official colleges, schools and departments](https://mak.ac.ug/study-mak/colleges-departments)
- [Makerere source using the MakSPH initials](https://mak.ac.ug/headlines/36187)
- [Institute of Gender and Development Studies (IGDS)](https://igds.mak.ac.ug/)
- [92nd Guild Electoral Commission call for nominations, 6 March 2026 (PDF)](https://mak.ac.ug/sites/default/files/2026-03/CALL%20FOR%20NOMINATIONS%20OF%20THE%2092nd%20GUILD%20L.pdf)

## Data and deployment limits

The application stores state in the TSV file selected by `DATA_FILE` (locally `makerere-election-monitor.tsv`). Passwords are stored as salted PBKDF2 hashes; authentication sessions are in memory. Existing version 2 and 3 files remain readable and are upgraded to version 4 on the next saved change. Keep the persistent disk backed up and protected. This is a lightweight monitoring tool, not an official results system or an independently certified election platform.

The hosted configuration relies on the provider's HTTPS edge. Keep `SESSION_COOKIE_SECURE=true`, use the persistent volume, and do not put secrets or real election data in the Docker image or Git repository.
