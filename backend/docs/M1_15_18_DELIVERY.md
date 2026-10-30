# M1.15–M1.18 — Interim implementation evidence

**Status:** PARTIALLY IMPLEMENTED. Draft PR only. Not production-ready.

## Implemented (verified by CI #372)
- M1.15: onboarding case creation/listing, independent decision, tenant RLS and audit records. Employee onboarding checklists, requirements and activation gating are **not yet implemented**.
- M1.16: separation case creation/listing, independent approval/rejection and employment history/status update for effective dates not in the future. Full exit clearance, final-pay integration, document review and separation reversal are **not yet implemented**.
- M1.17: existing dual-admin MFA rehire approval now supports independent rejection of pending requests. Additional cancellation, eligibility and operational approval policies are **not yet implemented**.
- M1.18: auditable employee document metadata: category, filename, checksum, size, expiry and version field. **No file upload, download, document encryption, storage integration, content scanning, integrity verification or retention/deletion workflow exists yet.** Metadata does not prove possession of a file.
- All new HTTP routes require MFA-verified tenant admin. Tenant-aware DB policies are enabled and forced.
- CI added HTTP checks for onboarding-case creation, requester self-approval rejection, and document metadata registration/listing.

## Verified evidence
GitHub Actions #372 passed backend Maven, PostgreSQL migration and RLS/HTTP checks, plus frontend TypeScript production build and Playwright browser smoke tests.

## Critical remaining coding work
1. Structured onboarding templates and checklist completion with evidence verification and activation restrictions.
2. Separation clearance, approval-specific permissions, exit documentation, and final-pay API event integration.
3. Rehire cancellation and eligibility checks, concurrency tests, plus full rejection-path HTTP tests with two actors.
4. Secure object storage with authenticated streaming upload/download, encryption at rest, antivirus scanning, size/type verification, file hash checks, versioning, retention and purge. Never rely on client-supplied SHA-256 alone.
5. Validate case decision dates and implement safe future-dated scheduling; ensure all sensitive transitions have authorization, audit, rollback and concurrency tests.
6. True restricted-runtime and multiple-authenticated-tenant integration tests, real-backend Playwright flows, operational privacy policy, and HR sign-off.

## Release restrictions
No production approval, no real employee data, no automatic merge. PR #3 remains a draft and PR #2 stays independent.
