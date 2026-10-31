# M3 — Payroll Management (main-based development)

## Baseline and isolation
- Branch: `feature/m3-payroll-management`, created from `main` at `271098264ad3a1e39494601e4282b779eac13778`.
- **M2 is not in this branch.** Attendance, leave balance, overtime, approved hours and any M2 immutable reconciliation source must not be read or invented here. M3 has no dependency on unmerged M2 migrations.
- M1 personnel compensation history is available as a source record, **not an automatically calculated payroll amount**.
- This development is **NO-GO** for payroll release, statutory compliance, real employee pay and production processing.

## M3.1 — Payroll configuration and draft pay-period foundation
- V36 defines tenant/organization-constrained effective-dated payroll configuration and company-linked pay periods.
- Frequency choices are `SEMI_MONTHLY`, `MONTHLY`, `WEEKLY`, and `BIWEEKLY`. Currency code must use three uppercase letters; timezone must parse through `ZoneId`. No frequency infers dates automatically.
- Periods are **DRAFT only**, enforced through a database `CHECK(status='DRAFT')`; overlapping date ranges are prohibited by PostgreSQL exclusion constraint, including concurrent creates. Pay dates and period ranges are bounded.
- All tables have forced tenant RLS, tenant FK checks and explicit company matching for configuration/period combinations.
- Only MFA-verified tenant administrators may access the `/api/v1/payroll/**` surface at this stage. Payroll officer administration needs separate M3 role-policy design and authorization tests before enabling.
- Endpoints: `POST/GET /api/v1/payroll/configurations`, `POST/GET /api/v1/payroll/periods`; create actions are audit logged. There are no payroll calculate, approve, post, release, pay, publish or export endpoints.
- The live disposable PostgreSQL HTTP gate `backend/scripts/test-payroll-foundation.sh` checks creation, tenant-scoped reads, duplicate configuration effective dates, invalid timezone, duplicate/overlapping periods, DRAFT classification, missing payroll release routes and unauthenticated denial.

## Delivery roadmap
1. **M3.1** Configuration and draft periods — coding and CI verification.
2. **M3.2** Earning/deduction component catalogue with immutable definitions, effective dates, statutory category boundaries and role validation; no deduction calculations yet.
3. **M3.3** Employee payroll enrollment and approved compensation-source snapshot, fully tenant scoped.
4. **M3.4** Deterministic calculation engine with fixtures for monthly, daily, hourly and partial periods, using `BigDecimal` and explicit rounding contracts; blocked until policy accepted.
5. **M3.5** Philippine statutory withholding/contributions versioned reference data, reviewed against official sources and effective dates; never hardcode evergreen rates.
6. **M3.6** Attendance/leave input adapter after M2 is reviewed/merged, or intentionally blocked when source is unavailable; independent dual-control source approval.
7. **M3.7** Payslip draft, independent review, immutable payroll journal and recalculation versioning; no payment release until approved.
8. **M3.8** Bank/payment provider integration (separate entitlement), idempotency and release dual-control; no live payment credentials in tests.
9. **M3.9** Reconciliation, audit, retention, privacy and reports.
10. **M3.10** Complete E2E, real database, concurrency, negative security, performance, legal, privacy, payroll and operations acceptance.

## Required gates
- Separate commits after each submilestone with CI success; PR remains DRAFT until required acceptance and approvals.
- Strict tenant isolation, FORCED RLS and fail-closed MFA. No use of real payroll data in tests.
- Human HR/payroll, finance, privacy, security, legal and operations approvals plus paid-hours/attendance source sign-off.
- Production remains **NO-GO**; never merge automatically.
