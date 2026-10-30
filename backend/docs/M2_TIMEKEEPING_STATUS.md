# M2 — Timekeeping & Attendance: Development status

Base branch: main (M1 migrations through V35 present at branch point).
Feature branch: feature/m2-timekeeping-attendance
Draft PR: https://github.com/eduardvcristobal/hrm/pull/4

## Verified increments
- M2.1: V36 shift templates; effective-dated employee schedule assignments; support for named timezones and overnight times; weekday-overlap detection; tenant RLS and MFA tenant-admin access.
- M2.2: V37 server-timestamped attendance sessions with unique open session, controlled clock in/out, immutable history of actors, HTTP regression.
- M2.3 foundation: raw attendance day totals now split across local calendar boundaries, including cross-range sessions and DST. A capped query fails closed instead of silently truncating output.\n- M2.3 policy variance preview: dedicated tenant-admin MFA endpoint `GET /api/v1/timekeeping/employees/{employeeId}/variance-preview?from=YYYY-MM-DD&to=YYYY-MM-DD`. Uses effective-dated assigned shifts and shift-local IANA timezones. Reports scheduled, raw recorded, within-shift, outside-shift, late-after-grace and early departure minutes. Overlapping punches are deduplicated; missing/open punch sessions produce nullable late/undertime. Returns `provisional=true`, `INFORMATIONAL_NOT_PAYROLL_APPROVED`. Does not deduct unpaid break from recorded punches and does not claim approved overtime or payable hours. Unit tests cover overnight, grace, duplicate intervals, DST, incomplete punches. CI for these new commits must pass before verification is claimed.
- M2.4 foundation: V38 independent reviewer correction requests for closed sessions; unique pending correction, original event snapshot, stale-data protection and audit. Negative path for self-approval verified; full two-actor approval positive path requires more integration coverage.
- React admin page at /timekeeping plus dashboard navigation, Playwright smoke tests. Restricted to MFA tenant administrators.
- Fixed inherited main issues: privileged token issuance without MFA enrollment, stale frontend browser CI runner and outdated tests.

Latest code verification: GitHub Actions #577 PASS (backend Maven/Flyway/PostgreSQL RLS/HTTP, frontend TypeScript/Chromium, real ClamAV): https://github.com/eduardvcristobal/hrm/actions/runs/41085003227

## Open phase work
- M2.3 remaining: integrate variance preview in the frontend and run positive multi-role and live PostgreSQL HTTP tests; add break-punch capture, explicit overtime authorization, holiday/rest-day attribution and tested employee-specific override policies.
- M2.4 full independent approval/rejection, multi-role isolation, concurrency and audit export tests.
- M2.5 leave applications, balances, accruals and approvals.
- M2.6 holidays/rest-day policy and time-zone/DST boundary validation.
- M2.7 payroll handoff and reconciliations, separate from payroll calculations.
- M2.8 reporting, retention and audit exports.
- M2.9 employee mobile/self-service/device workflows, privacy-controlled optional geolocation.
- M2.10 comprehensive E2E, performance, security, multi-tenant and human acceptance.

Do not merge PR automatically. Production remains NO-GO, with no real employee data allowed before acceptance.