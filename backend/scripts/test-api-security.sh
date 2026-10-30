#!/usr/bin/env bash
set -euo pipefail
: "${DB_PASSWORD:?DB_PASSWORD required}"
BASE="http://localhost:8080"
PGPASSWORD="$DB_PASSWORD" psql -X -v ON_ERROR_STOP=1 -h localhost -p 5433 -U "${DB_USERNAME:-hrm_app}" -d hrm_dev <<'SQL'
BEGIN;
INSERT INTO tenant(slug,display_name) VALUES('ci-http-security','CI HTTP Security')
ON CONFLICT(slug) DO NOTHING;
INSERT INTO auth_identity(tenant_id,email,password_hash,enabled)
SELECT id,'admin-ci@example.test',crypt('CiOnlyStrongPassword123!',gen_salt('bf',10)),true FROM tenant
WHERE slug='ci-http-security' ON CONFLICT(email) DO NOTHING;
INSERT INTO auth_identity_role(identity_id,role)
SELECT id,'TENANT_ADMIN' FROM auth_identity WHERE email='admin-ci@example.test'
ON CONFLICT DO NOTHING;
COMMIT;
SQL
AUTH="admin-ci@example.test:CiOnlyStrongPassword123!"
COOKIE="$(mktemp)"
trap 'rm -f "$COOKIE"' EXIT
curl -fsS -u "$AUTH" -b "$COOKIE" -c "$COOKIE" "$BASE/api/v1/security/csrf" > /tmp/hrm-ci-token.json
TOKEN="$(python3 -c 'import json; print(json.load(open("/tmp/hrm-ci-token.json"))["token"])')"
test -n "$TOKEN"
DENIED="$(curl -sS -u "$AUTH" -b "$COOKIE" -c "$COOKIE" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"companyCode":"CI_CSRF_DENY","legalName":"Denied"}' "$BASE/api/v1/companies")"
test "$DENIED" = 403 || { echo "Expected authenticated CSRF denial 403, got $DENIED"; exit 1; }
CREATED="$(curl -sS -u "$AUTH" -b "$COOKIE" -c "$COOKIE" -o /tmp/hrm-ci-company.json -w '%{http_code}' -X POST -H "X-CSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' --data '{"companyCode":"CI_CSRF_OK","legalName":"CI Company"}' "$BASE/api/v1/companies")"
test "$CREATED" = 201 || { echo "Expected authorized create 201, got $CREATED"; cat /tmp/hrm-ci-company.json; exit 1; }
curl -fsS -u "$AUTH" -b "$COOKIE" "$BASE/api/v1/companies" | grep -q 'CI_CSRF_OK'
# Issue a token with authenticated Basic + CSRF; use it without cookies; revoke it.
ISSUED="$(curl -sS -u "$AUTH" -b "$COOKIE" -c "$COOKIE" -o /tmp/hrm-token.json -w '%{http_code}' -X POST -H "X-CSRF-TOKEN: $TOKEN" "$BASE/api/v1/security/tokens")"
test "$ISSUED" = 201 || { echo "Token issue returned $ISSUED"; cat /tmp/hrm-token.json; exit 1; }
BEARER="$(python3 -c 'import json; print(json.load(open("/tmp/hrm-token.json"))["value"])')"
test "$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' "$BASE/api/v1/me")" = 200
REVOKED="$(curl -sS -X DELETE -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' "$BASE/api/v1/security/tokens/current")"
test "$REVOKED" = 204 || { echo "Token revoke returned $REVOKED"; exit 1; }
AFTER="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' "$BASE/api/v1/me")"
test "$AFTER" = 401 || { echo "Revoked token still authenticates: $AFTER"; exit 1; }
for i in $(seq 1 10); do
  BAD="$(curl -sS -u 'admin-ci@example.test:incorrect-password' -o /dev/null -w '%{http_code}' "$BASE/api/v1/me")"
  test "$BAD" = 401 || { echo "Invalid login should return 401, got $BAD"; exit 1; }
done
PGPASSWORD="$DB_PASSWORD" psql -X -h localhost -p 5433 -U "${DB_USERNAME:-hrm_app}" -d hrm_dev -c 'SELECT attempts,window_start FROM auth_login_attempt ORDER BY updated_at DESC LIMIT 5'
LOCKED="$(curl -sS -u 'admin-ci@example.test:incorrect-password' -o /dev/null -w '%{http_code}' "$BASE/api/v1/me")"
test "$LOCKED" = 429 || { echo "Expected throttle 429, got $LOCKED"; exit 1; }
echo 'HTTP CSRF, tenant scope, and failed-login throttle passed'
