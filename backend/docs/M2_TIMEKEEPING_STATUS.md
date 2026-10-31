# M2 — Timekeeping & Attendance: Development status

Base branch: main (M1 migrations through V35 present at branch point).
Feature branch: feature/m2-timekeeping-attendance
Draft PR: https://github.com/eduardvcristobal/hrm/pull/4

## Verified increments
- M2.1: V36 shift templates; effective-dated employee schedule assignments; support for named timezones and overnight times; weekday-overlap detection; tenant RLS and MFA tenant-admin access.
- M2.2: V37 server-timestamped attendance sessions with unique open session, controlled clock in/out, immutable history of actors, HTTP regression.
- M2.3 foundation: raw attendance day totals now split across local calendar boundaries, including cross-range sessions and DST. A capped query fails closed instead of silently truncating output.\n- M2.3 policy variance preview: dedicated tenant-admin MFA endpoint `GET /api/v1/timekeeping/employees/{employeeId}/variance-preview?from=YYYY-MM-DD&to=YYYY-MM-DD`. Uses effective-dated assigned shifts and shift-local IANA timezones. Reports scheduled, raw recorded, within-shift, outside-shift, late-after-grace and early departure minutes. Overlapping punches are deduplicated; missing/open punch sessions produce nullable late/undertime. Returns `provisional=true`, `INFORMATIONAL_NOT_PAYROLL_APPROVED`. Does not deduct unpaid break from recorded punches and does not claim approved overtime or payable hours. Unit tests cover overnight, grace, duplicate intervals, DST, incomplete punches. CI for these new commits must pass before verification is claimed.
- M2.4 foundation: V38 independent reviewer correction requests for closed sessions; unique pending correction, original event snapshot, stale-data protection and audit. Additional two-actor MFA HTTP coverage now committed for approval/rejection, replay denial and attendance snapshot immutability after rejection; latest CI must pass before counting as verified.
- React admin page at /timekeeping plus dashboard navigation, Playwright smoke tests. Restricted to MFA tenant administrators.
- Fixed inherited main issues: privileged token issuance without MFA enrollment, stale frontend browser CI runner and outdated tests.

Latest verified M2.4 regression: GitHub Actions workflows on commit 6dcf710d both passed (2026-10-31). M2.5 commits require their own CI verification and remain unaccepted until verified.

## Open phase work
- M2.3 remaining: integrate variance preview in the frontend and run positive multi-role and live PostgreSQL HTTP tests; add break-punch capture, explicit overtime authorization, holiday/rest-day attribution and tested employee-specific override policies.
- M2.4 remaining: verify new CI two-actor positive-path tests, audit export assertions, concurrent decision race and cross-tenant/multi-role isolation.
- M2.5 foundation (in CI): V39 leave categories and effective tenant/company-scoped request lifecycle with independent second-admin approval or rejection, pending cancellation, overlap checking under employee row lock, immutable review and event audit. HTTP regression added for positive and negative paths. These endpoints are tenant-admin MFA restricted and not yet a final leave policy engine.\n- M2.5 remaining: earned balances and policy-specific accruals, scheduled/non-working day rules, half-day/hourly requests, negative balance policy, reversals, employee self-service and multi-tenant/performance certification.
- M2.6 holidays/rest-day policy and time-zone/DST boundary validation.
- M2.7 payroll handoff and reconciliations, separate from payroll calculations.
- M2.8 reporting, retention and audit exports.
- M2.9 employee mobile/self-service/device workflows, privacy-controlled optional geolocation.
- M2.10 comprehensive E2E, performance, security, multi-tenant and human acceptance.

Do not merge PR automatically. Production remains NO-GO, with no real employee data allowed before acceptance.
## M2.5.1 leave credit milestone — pending integrated acceptance
- V40 introduces tenant-RLS policy history and once-only employee/month/type credit postings. Database constraints enforce numeric 0–31 days, first-of-month periods and uniqueness.
- API: `POST /api/v1/timekeeping/leave-credit-policies` with {leaveTypeId,monthlyCreditDays,effectiveFrom}; `GET /leave-credit-policies?leaveTypeId=...`; `POST /employees/{employeeId}/leave-credits/accrue?leaveTypeId=...&month=YYYY-MM`; `GET /employees/{employeeId}/leave-credits?leaveTypeId=...`; `GET /employees/{employeeId}/leave-credit-balances` under the `/api/v1/timekeeping` prefix.
- Employee row locking and uniqueness reject double postings, and posting a future month or pre-hire month is blocked. Posted entries are immutable through the public API and audited.
- The balance is **informational accrued credits**, not available-to-take days. There is no balance subtraction for approved leave, no proration, eligibility, carryover, expiration, policy-version retroactivity, year-end close or backdated employment replay.
- This is not a representation of Philippine statutory leave entitlement and must not affect employee wages, legal leave or payroll until reviewed by HR/legal and tested.
- PostgreSQL live HTTP regression covers policy create, duplicate denial, future rejection, single posting, audit and read-only credit aggregation. New CI verification is required before marking this increment accepted.
