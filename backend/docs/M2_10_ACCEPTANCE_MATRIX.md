# M2.10 — Timekeeping & Attendance Acceptance Matrix

**Status: NO-GO.** This document describes gates and their automation coverage. CI success verifies the specific automated tests, **not** regulatory, operational or clinical/personal-data approval.

**Branch:** `feature/m2-timekeeping-attendance`. **Draft PR:** https://github.com/eduardvcristobal/hrm/pull/4. **Production data:** prohibited until human sign-off.

| Gate | Automated evidence in repository | Unverified / manual acceptance | Status |
|---|---|---|---|
| Tenant authorization and MFA | `backend/scripts/test-tenant-rls.sql`, `test-api-security.sh`, `test-employee-self-service.sh` | Two independent live tenants with real role matrices; external penetration test | **NO-GO** |
| Employee identity and scope | `test-employee-self-service.sh`: unlinked denial, admin binding, revoked link, self-service employee-only API | Onboarding identity proofing and revocation SLA sign-off | **NO-GO** |
| Schedule, timezone, DST | `test-timekeeping.sh`, `AttendanceVarianceCalculatorTest.java`, `test-calendar-policy.sh` | HR certification for all configured company calendars, overnight shifts and holidays | **NO-GO** |
| Leave policies and payments | `test-leave-management.sh`, `test-leave-balance.sh`, `test-leave-credits.sh` | Legal validation of local entitlements, holidays, cutoffs, carryover and paid leave | **NO-GO** |
| Attendance corrections | `test-timekeeping.sh`: independent review, concurrency/replay and immutable source checks | Multi-approver audits and payroll sign-off | **NO-GO** |
| Payroll handoff | `test-payroll-reconciliation.sh` | Payable-hours logic, export contracts, negative testing, payroll-provider release control | **NO-GO** |
| Controlled CSV exports | `test-timekeeping-reports.sh` | Data classification, retention period and lawful-basis approval; end-to-end export-to-storage controls | **NO-GO** |
| Employee devices | `test-device-security.sh`: independent approval, one-time secret, MFA, token/nonce replay and revocation | Actual hardware/OS key storage; device theft, credential rotation, anti-tampering, accessibility, recovery, regional consent | **NO-GO** |
| Offline attendance | No enabled feature | Design offline queue deduplication, signed event envelopes, trusted time, multi-device arbitration, synchronization and redress | **NOT IMPLEMENTED** |
| Optional location and biometrics | Database CHECK enforces disabled flags in `V46` | Privacy impact assessment, informed consent, device disclosures and retention minimization | **DISABLED** |
| Load, backup and DR | Build/test/ClamAV CI plus RLS scripts | Quantified concurrency/load targets, p95 & p99, backup/restore drill and monitoring alert exercises | **NO-GO** |
| Human approvals | None automatable | HR, privacy officer, security, payroll, legal and operations sign-off recorded with dated evidence | **PENDING** |

## M2.10 exit criteria

1. All relevant CI workflows pass on the **same** PR head SHA, including both PostgreSQL and browser checks. Failures must be addressed, not waived.
2. Establish a two-tenant staging environment with distinct, MFA-enabled employee and admin accounts. Verify read/write isolation, including stale browser sessions, revoked identities and duplicated or modified request identifiers.
3. Run 10-way+ concurrent attendance, correction, leave and device-attestation trials across database replicas, with documented expected/observed outcomes; add reproducible failure traces and lock-contention analysis.
4. Measure load and response distributions under documented hardware, database and dataset assumptions. Agree SLA/SLO thresholds with operations before certification.
5. Record actual backup encryption, off-site storage, disaster restore RTO/RPO and incident/alert tests.
6. Independently certify local labor, privacy and payroll policies. **Do not infer paid hours** from raw attendance timestamps.
7. Review mobile accessibility, offline recovery and customer support procedures; retain a signed UAT artifact. Do not enable unsupported device functions as shortcuts.
8. Obtain explicit human approval before removing NO-GO and making PR ready to merge. **Do not merge PR automatically.**

## Current automation coverage interpretation

The M2.9.1 device attestation is an **authentication proof only**, not a clock-in or hour record. The one-time device secret is delivered to an MFA-authenticated employee after a second-actor review. A separate secure device OS storage integration, theft recovery and cryptographic channel binding are still needed for production. A unique nonce prevents an identical device proof from being accepted twice, including simultaneous submissions; this alone does not prove a physical employee was present.

## M2.10.1 — automated first-pass two-tenant security and HTTP latency gates

The following checks execute against **ephemeral CI data**. They are a prerequisite for staging, not production clearance:

- `backend/scripts/test-m2-tenant-boundaries.sql` creates separate tenant A/B employees, identity links, registered devices and nonce rows. A restricted `NOSUPERUSER NOBYPASSRLS` role checks that tenant A cannot see B's records, cannot update B's devices or identity links by known ID, and gets no results with no tenant context. All fixture writes are rolled back.
- `backend/scripts/test-m2-load.py` sends **100 authenticated GET requests with 10 concurrent workers** to the self-service `/me` endpoint on real PostgreSQL. Every response must carry the expected employee's ID and all device capture flags disabled. The script emits `/tmp/hrm-m210-http-load.json` with response statuses, p50, p95 and p99 latency, and uses a generous **5,000 ms provisional CI guardrail**. This provisional limit is not an approved SLO or performance baseline.
- `backend/scripts/test-device-security.sh` checks **10 simultaneous submissions of one valid nonce** to an enrolled, approved device. Exactly one attestation may succeed; all other results must be explicit 403/409 replay denials. It also checks missing MFA, invalid secrets, stale timestamps, one-time activation and credential revocation.
- The backend GitHub Actions workflow uploads the synthetic latency report as a temporary build artifact even if the test fails. Review the artifact alongside the exact head SHA and status before interpreting these results.

**Still mandatory:** two distinct full browser sessions across tenants, real infrastructure replicas, metrics under representative user load, a measured encrypted restore, manual HR/privacy/security/operations sign-off, and a documented incident response exercise. Do not change production NO-GO or mark the entire M2.10 milestone accepted based on these CI checks.

### M2.10.2 — two independent HTTP identities and ten-way proof contention

- `backend/scripts/test-m2-cross-tenant-http.sh` provisions a second synthetic tenant and a distinct MFA-assured employee bearer token in disposable CI PostgreSQL. The API must show each employee their own profile and registered devices while rejecting tenant A administrators/employees attempting to inspect or revoke tenant B device records, even with the exact foreign device UUID. It also confirms the second employee cannot access tenant A's administrative timekeeping APIs.
- `backend/scripts/test-device-security.sh` additionally sends **ten concurrent identical nonce attestation requests**. Exactly one HTTP 200 is permitted, and the other nine must be 403 or 409. This certifies one narrow anti-replay invariant, **not** device presence, time-clock accuracy, or distributed-system performance.
- The existing CI identity and RLS coverage plus the synthetic 100-request, 10-worker HTTP report are checked on the same branch head. They do **not** establish production-equivalent multiple application replicas or legal readiness.

### M2.10.3 — dual-process shared-database reproducibility

A dedicated CI job (`replica-certification` in `.github/workflows/backend-ci.yml`) boots **two separate Spring Boot JVMs** on loopback ports 8081 and 8082 against one disposable PostgreSQL 17 service. It waits for the first process to finish Flyway migrations before booting the second. `backend/scripts/test-m2-replica-fixtures.sh` seeds an MFA-assured synthetic employee and an independently reviewed active device, never real personnel data.

`backend/scripts/test-m2-replica-load.py` alternates employee-authenticated reads across both JVMs with shared PostgreSQL; checks identity isolation and disabled attendance capture flags; and submits one identical device attestation nonce concurrently from both application processes. Exactly one successful attestation is permitted. Result JSON and JVM logs are retained as the `m210-dual-jvm-evidence` GitHub Actions artifact (14 days).

**Limits:** This is *two processes on one shared GitHub runner and database*, not multiple physical hosts, independent database replicas, an external load balancer, a sustained peak-traffic capacity test, or proof of outage recovery. The 5,000 ms p95 is an intentionally generous CI regression limit, **not** an operations-approved SLA. Production is still NO-GO.

### M2.10.3 paced traffic extension

The CI dual-JVM check additionally runs a **bounded ~20-second, 800-request paced read scenario with 20 workers** (500 ms pacing per worker), alternating requests between both processes, recording per-replica error counts, observed requests/second, mean and p50/p95/p99/max. It then sends 20 simultaneous identical nonce proofs across the two JVMs; exactly one may be accepted. A temporary `m210-dual-jvm-evidence` artifact contains the JSON and separate JVM logs. The harness is intentionally bounded to at most 2,000 requests and 40 workers.

This is a **synthetic sustained CI regression**, not a representative 24-hour soak, real production-volume traffic, a cross-host network partition test, a full active-active disaster drill or certified payroll/attendance release. Hardware, traffic distribution and capacity are specific to the GitHub-hosted runner; performance thresholds remain provisional.
