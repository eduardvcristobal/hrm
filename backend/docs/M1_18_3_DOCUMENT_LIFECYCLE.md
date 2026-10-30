# M1.18.3 — Document Lifecycle Increment

CI: https://github.com/eduardvcristobal/hrm/actions/runs/41073358001 (all jobs passed).

Implemented: Flyway V27 adds legal holds, reasons, document-family identifiers, version uniqueness, and tombstone identities. Lifecycle API exposes detail, legal-hold modification, retention extension, and a non-destructive tombstone guarded by retention/hold checks. Released-document download and review exclude tombstoned records. HTTP regression checks that held documents cannot be tombstoned or have retention changed.

Important limitations: tombstone is NOT physical deletion; no certified purge worker exists. Document-family/version fields are schema foundations only and no replacement-version endpoint exists. AES-GCM still uses the single configured HRM_DOCUMENT_KEY; key identifiers and rotation are NOT implemented. Legal hold creation/removal requires further role segregation, governance and approval evidence. No immutable or external legal-hold register has been certified. The review API may set retention fields, so end-to-end policy enforcement needs more testing and hardening.

Production remains NO-GO, and PR #3 must remain draft. Do not use with actual sensitive employee documents.
