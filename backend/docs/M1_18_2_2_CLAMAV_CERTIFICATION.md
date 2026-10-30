# M1.18.2.2 ClamAV CI Verification

GitHub Actions #483: https://github.com/eduardvcristobal/hrm/actions/runs/41071107153

- Dedicated CI job boots real clamav/clamav:stable and waits for PING.
- Java test checks clean PDF-like bytes, EICAR detection and absent-scanner ERROR.
- Backend API/RLS checks and frontend Chromium smoke also passed.
- Clamd integration is fail-closed: an unavailable daemon must not authorize document release.

Operational requirements: set HRM_CLAMAV_HOST and HRM_CLAMAV_PORT (3310 by default); keep clamd on a private network and update signatures. Continue to require independent document review.

NOT certified: production daemon/signature freshness, malicious PDF sandboxing, two-actor full HTTP flows, throughput/retries, retention purge, key rotation, privacy and DR approvals.

PR #3 stays draft, production NO-GO.