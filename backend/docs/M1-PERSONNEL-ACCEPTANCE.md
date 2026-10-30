# M1 Personnel Management — Interim Acceptance Report

Status: **DRAFT PR #3 / NOT MERGE-READY / PRODUCTION NO-GO**

## Implemented scope

- M1.1–M1.3: tenant-isolated departments, employee registry, company-scoped directories and guarded departmental changes.
- M1.4–M1.6: effective-dated employment, compensation and organizational-assignment history; position catalog and employee profiles.
- M1.7–M1.9: multi-tenant RLS tests, MFA-gated HR/payroll role separation, encrypted private fields and audited updates. Production key rotation and full privacy operations remain outstanding.
- M1.10: same-company reporting lines, supervisor and department-cycle prevention, job-position assignments and deactivation guards.
- M1.11: central lifecycle transition policy; terminated/retired employees cannot use generic reactivation. A terminated employee's rehire request requires a **different** MFA-verified tenant administrator for approval, with transaction locks, chronology checks and audit records. Retired employees are not eligible for this initial workflow.
- M1.12: React/TypeScript developer-preview personnel workspace under `frontend/` with directory, profile, department/position listing and employee creation. No production login flow yet.
- M1.13: extended two-tenant RLS checks for employee, position, assignment, compensation and rehire request tables; dual-admin rehire HTTP checks; three Chromium browser smoke cases against mocked APIs.

## Verified technical evidence

- GitHub Actions #313: lifecycle transition unit tests and backend regression passed.
- GitHub Actions #324: rehire submission, self-approval rejection and generic bypass rejection passed.
- GitHub Actions #328: second MFA-verified tenant administrator successfully approved rehire.
- GitHub Actions #346: frontend production build plus backend security/tenant regressions passed.
- GitHub Actions #355: frontend TypeScript build and all **3 mocked-API Chromium browser smoke tests** passed, along with backend Maven/Flyway/API and RLS checks.

Latest evidence: https://github.com/eduardvcristobal/hrm/actions/runs/41049441455

## Acceptance gaps and blockers

1. **Identity and security:** finish M0.3's key rotation, MFA recovery, production infrastructure tests and independent security review.
2. **Privacy:** production secrets management, personnel PII key rotation, consent/legal-basis and retention policies, redaction, comprehensive field-level permissions and privacy review.
3. **Human workflows:** independent separation approval, dispute/correction processes, rehire verification/approval operational policy, rehire cancellation and rejection; comprehensive concurrency tests.
4. **Employee lifecycle:** onboarding paperwork, resignation, exit clearance, final pay, retirement, rehire case management, approvals, promotions and transfers with richer effective-dated records.
5. **Frontend:** integrated secure sign-in/MFA without pasted bearer tokens, full authorized employee workflows, responsive accessibility audit, internationalization and browser E2E against the real backend (current tests mock APIs).
6. **Tenant security:** multi-role, two-authenticated-tenant HTTP tests using truly restricted runtime database credentials, full cross-tenant negative suites.
7. **Release operations:** UAT, staging pilot, performance baseline, incident response, backup/restore drill and formal HR/privacy/security/operations sign-off.

This report is a **technical progress assessment only**, not regulatory certification or authorization to process real employee information.

**Do not merge PR #3 automatically. Preserve PR #2 separately.**
