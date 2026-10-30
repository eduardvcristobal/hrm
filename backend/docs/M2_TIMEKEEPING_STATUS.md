# M2 — Timekeeping & Attendance: Development status

Base branch: main (M1 migrations through V35 present at branch point).
Feature branch: feature/m2-timekeeping-attendance
Draft PR: https://github.com/eduardvcristobal/hrm/pull/4

## Verified increments
- M2.1: V36 shift templates; effective-dated employee schedule assignments; support for named timezones and overnight times; weekday-overlap detection; tenant RLS and MFA tenant-admin access.
- M2.2: V37 server-timestamped attendance sessions with unique open session, controlled clock in/out, immutable history of actors, HTTP regression.
- M2.3 foundation: raw attendance day totals (completed elapsed minutes and open-session count) for a bounded window and selected timezone. These are NOT payable hours, lateness, undertime, or overtime.
- M2.4 foundation: V38 independent reviewer correction requests for closed sessions; unique pending correction, original event snapshot, stale-data protection and audit. Negative path for self-approval verified; full two-actor approval positive path requires more integration coverage.
- React admin page at /timekeeping plus dashboard navigation, Playwright smoke tests. Restricted to MFA tenant administrators.
- Fixed inherited main issues: privileged token issuance without MFA enrollment, stale frontend browser CI runner and outdated tests.

Latest code verification: GitHub Actions #577 PASS (backend Maven/Flyway/PostgreSQL RLS/HTTP, frontend TypeScript/Chromium, real ClamAV): https://github.com/eduardvcristobal/hrm/actions/runs/41085003227

## Open phase work
- M2.3 policy-governed late, undertime, approved overtime and holiday/rest-day attribution (not yet computed).
- M2.4 full independent approval/rejection, multi-role isolation, concurrency and audit export tests.
- M2.5 leave applications, balances, accruals and approvals.
- M2.6 holidays/rest-day policy and time-zone/DST boundary validation.
- M2.7 payroll handoff and reconciliations, separate from payroll calculations.
- M2.8 reporting, retention and audit exports.
- M2.9 employee mobile/self-service/device workflows, privacy-controlled optional geolocation.
- M2.10 comprehensive E2E, performance, security, multi-tenant and human acceptance.

Do not merge PR automatically. Production remains NO-GO, with no real employee data allowed before acceptance.