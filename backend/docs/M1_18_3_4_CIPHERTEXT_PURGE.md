# M1.18.3.4 — Live ciphertext purge (developer preview)

## Implemented
- Flyway V28 adds tenant-isolated purge requests, creator/executor constraints and unique per-document requests.
- API POST /api/v1/personnel/employees/{employeeId}/documents/{documentId}/purge opens a request only for an expired, tombstoned, non-held document with live ciphertext.
- API POST /api/v1/personnel/employees/{employeeId}/documents/{documentId}/purge/{requestId}/execute requires a different authenticated MFA tenant administrator; deletes the live ciphertext row and audits the action.
- API GET /api/v1/personnel/employees/{employeeId}/documents/{documentId}/purge lists the request.
- Locks and rechecks legal hold, retention and tombstone state during both actions. Metadata, version history and audit trail remain.
- Runtime DB privileges are limited to document-content DELETE and purge-request state updates; tenant FORCE RLS remains.

## Verification
GitHub Actions #519 passed all three jobs: https://github.com/eduardvcristobal/hrm/actions/runs/41075407752
Regression checks held-document and active-document purge denials. Positive-path two-actor purge API, concurrency races and backup recovery are not yet certified.

## Important limitation
PostgreSQL DELETE removes live logical rows, NOT physical secure erasure of MVCC/WAL pages, replicas, snapshots, exports, object storage or backups. A compliant deletion program requires retention-aware backup expiry, encryption-key lifecycle and infrastructure controls.

## Outstanding
- End-to-end two-person happy-path purge with separate MFA principals and tenant-isolation verification.
- Purge worker, policy/legal review, periodic cleanup and operational metrics.
- Physical media/database backup erasure strategy and auditable recovery validation.
- M1.18.3.5 key identifiers, rotations and rollback tests.

Production remains NO-GO. PR #3 stays draft.