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

## M2.5.2 manual leave reconciliation and carryover preview (in certification)
- V41 stores tenant-isolated append-only DEBIT and compensating REVERSAL events tied to approved leave requests. An approved request can produce at most one debit, and a debit at most one reversal, enforced by database unique indexes.
- The POST `/api/v1/timekeeping/employees/{employeeId}/leave-balances/requests/{requestId}/consume` body is `{ "days":1.000, "note":"HR reviewed chargeable day" }`. Its days are explicit HR input, must not exceed request's calendar span or available informational credits. Employee row locking serializes competing credit postings/debits/reversals; a request's approval must already be recorded.
- The POST `/api/v1/timekeeping/employees/{employeeId}/leave-balances/events/{eventId}/reverse` body is `{ "note":"Approved adjustment" }`. It never deletes the original debit. Read-only `GET /.../leave-balances` shows accrued, consumed and remaining **informational** days; `GET /.../leave-balances/events?leaveTypeId=...` exposes the journal.
- V42 adds capped carryover **planning only**: tenant-specific policy with next-year cap, and a read-only preview using the current unsettled ledger. `POST /api/v1/timekeeping/leave-carryover-preview-policies` configures `{leaveTypeId,destinationYear,maximumDays}`; `GET /api/v1/timekeeping/employees/{employeeId}/leave-carryover-preview?leaveTypeId=...&destinationYear=...` shows a `posted:false` preview. It does not create, transfer or expire credits.
- This phase explicitly does NOT calculate chargeable working days, half-days, rest-day/holiday exclusion, Philippine statutory entitlements, policy year-end close, protected negative-balance exceptions, payable leave or payroll integration. Human HR/legal review and production data acceptance remain blocked.
- Live PostgreSQL HTTP tests cover approved debit, replay denial, reversal, double reversal denial, audit events, remaining balance and carryover cap/no-mutation; final integrated CI must pass before acceptance.

## M2.6 company holidays and rest days — provisional classification
- Flyway V43 adds company-scoped calendar dates (HOLIDAY, SPECIAL_NON_WORKING, COMPANY_CLOSURE) and employee-specific REST_DAY/WORK_DAY overrides. Each table has forced tenant RLS, company/employee foreign keys, a one-per-date uniqueness rule, actor attribution and audit events.
- The REST API under `/api/v1/timekeeping` adds POST and GET `/calendar-days`, POST and GET `/employees/{employeeId}/day-overrides`, and read-only GET `/employees/{employeeId}/calendar-preview`. Date range queries are capped at 31 days.
- Preview applies effective-dated shift weekday masks, then employee overrides. An unscheduled employee is `UNSCHEDULED`, **not** automatically on a rest day. Company holidays and employee rest-day overrides are displayed separately so both can coexist. Existing attendance and leave records are not altered.
- The React admin screen at `/calendar` is reachable from `/timekeeping`, and guarded through existing timekeeping authentication/MFA. Browser smoke tests validate navigation and unauthenticated API access refusal.
- Live PostgreSQL HTTP tests verify weekday/weekend classification, employer holiday overlay, employee rest-day exception, duplicate denial, audit events and date range bounds. Latest M2.6 CI must pass before this release increment is marked verified.
- **Not implemented or certified:** government-maintained holiday catalog; jurisdiction/location-specific holiday classifications; employee/branch regional overrides; actual statutory rest-day, OT or holiday pay rules; automatic chargeable leave-day computation; payroll handoff. Policy preview remains `INFORMATIONAL_NO_LEGAL_HOLIDAY_OR_PAYROLL_CALCULATION`.
