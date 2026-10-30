# M1 Personnel Management — Interim Acceptance Evidence

Status: **DRAFT / NOT MERGE-READY**. No production approval.

## Delivered and verified
- M1.1: PostgreSQL department/employee/employment history schema with tenant-scoped RLS.
- M1.2: employee registration and company-scoped directory.
- M1.3: department creation, directory and guarded deactivation.
- M1.4: effective-dated employment status/type changes with immutable history.
- M1.5: compensation history, company-bound foreign keys and audit trail.
- M1.6: company-bound branch/department assignment and effective-dated assignment ledger; employee profile read and limited name-preference update.
- M1.7: expanded PostgreSQL two-tenant tests for assignment and compensation isolation, missing tenant context, and HTTP denial of anonymous/Basic-only salary access.
- CI #242, #246, #250 passed; #250 includes Maven, Flyway, RLS and authenticated HTTP regression.

## Controls
- Personnel routes currently require tenant-admin plus MFA assurance. No employee-facing self-service access is enabled.
- Government IDs, tax numbers, personal contact details, banking and other sensitive identifiers are deliberately excluded from these endpoints.
- Compensation records are append-only and have a separate audit event.

## Acceptance blockers before merging
1. Role-specific permissions for HR administrators, payroll officers, managers and employees, including field-level salary access.
2. End-to-end tests using truly least-privileged runtime database credentials and multiple authenticated tenants.
3. Full department hierarchy edits/cycle prevention; job-position taxonomy and transitions; pagination and search.
4. Sensitive PII encryption/retention/redaction, field-level audit and privacy assessment.
5. Concurrency tests for assignments, employment and compensation changes; structured validation/error consistency.
6. React/TypeScript personnel UI, browser E2E and operational/UAT evidence.
7. Independently resolve relevant M0.3 security release blockers before production deployment.

**Keep PR #3 draft; do not merge automatically.**
