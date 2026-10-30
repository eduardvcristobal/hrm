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
BASIC_ADMIN="$(curl -sS -u "$AUTH" -b "$COOKIE" -c "$COOKIE" -o /dev/null -w '%{http_code}' -X POST -H "X-CSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' --data '{"companyCode":"BASIC_DENY","legalName":"Denied"}' "$BASE/api/v1/companies")"
test "$BASIC_ADMIN" = 403 || { echo "Basic authentication bypassed MFA admin guard: $BASIC_ADMIN"; exit 1; }
PRE_ENROLL="$(curl -sS -u "$AUTH" -b "$COOKIE" -c "$COOKIE" -o /dev/null -w '%{http_code}' -X POST -H "X-CSRF-TOKEN: $TOKEN" "$BASE/api/v1/security/tokens")"
{ test "$PRE_ENROLL" = 401 || test "$PRE_ENROLL" = 403; } || { echo "Privileged account issued token before MFA enrollment: $PRE_ENROLL"; exit 1; }
# MFA enrollment and replay checks for the privileged CI account.
ENROLL="$(curl -sS -u "$AUTH" -b "$COOKIE" -c "$COOKIE" -o /tmp/hrm-mfa.json -w '%{http_code}' -X POST -H "X-CSRF-TOKEN: $TOKEN" "$BASE/api/v1/security/mfa/enroll")"
test "$ENROLL" = 201 || { echo "MFA enrollment failed: $ENROLL"; cat /tmp/hrm-mfa.json; exit 1; }
SECRET="$(python3 -c 'import json; print(json.load(open("/tmp/hrm-mfa.json"))["secretHex"])')"
export SECRET
export STEP="$(python3 -c 'import time; print(int(time.time()//30))')"
OTP="$(python3 -c 'import os,hmac,hashlib,struct; k=bytes.fromhex(os.environ["SECRET"]); c=int(os.environ["STEP"]); d=hmac.new(k,struct.pack(">Q",c),hashlib.sha1).digest(); i=d[-1]&15; print("%06d" % ((struct.unpack(">I",d[i:i+4])[0]&0x7fffffff)%1000000))')"
CONFIRMED="$(curl -sS -u "$AUTH" -b "$COOKIE" -c "$COOKIE" -o /dev/null -w '%{http_code}' -X POST -H "X-CSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' --data "$(printf '{"code":"%s"}' "$OTP")" "$BASE/api/v1/security/mfa/confirm")"
test "$CONFIRMED" = 204 || { echo "MFA confirmation failed: $CONFIRMED"; exit 1; }
REPLAY="$(curl -sS -u "$AUTH" -b "$COOKIE" -c "$COOKIE" -o /dev/null -w '%{http_code}' -X POST -H "X-CSRF-TOKEN: $TOKEN" "$BASE/api/v1/security/tokens" -H "X-TOTP-Code: $OTP")"
{ test "$REPLAY" = 401 || test "$REPLAY" = 403; } || { echo "MFA replay not blocked: $REPLAY"; exit 1; }
UNVERIFIED="$(curl -sS -u "$AUTH" -b "$COOKIE" -c "$COOKIE" -o /dev/null -w '%{http_code}' -X POST -H "X-CSRF-TOKEN: $TOKEN" "$BASE/api/v1/security/tokens")"
{ test "$UNVERIFIED" = 401 || test "$UNVERIFIED" = 403; } || { echo "MFA-less issuance not blocked: $UNVERIFIED"; exit 1; }
NEXTOTP="$(python3 -c 'import os,hmac,hashlib,struct; k=bytes.fromhex(os.environ["SECRET"]); c=int(os.environ["STEP"])+1; d=hmac.new(k,struct.pack(">Q",c),hashlib.sha1).digest(); i=d[-1]&15; print("%06d" % ((struct.unpack(">I",d[i:i+4])[0]&0x7fffffff)%1000000))')"
ISSUED="$(curl -sS -u "$AUTH" -b "$COOKIE" -c "$COOKIE" -o /tmp/hrm-token.json -w '%{http_code}' -X POST -H "X-CSRF-TOKEN: $TOKEN" -H "X-TOTP-Code: $NEXTOTP" "$BASE/api/v1/security/tokens")"
test "$ISSUED" = 201 || { echo "MFA token issue returned $ISSUED"; cat /tmp/hrm-token.json; exit 1; }
BEARER="$(python3 -c 'import json; print(json.load(open("/tmp/hrm-token.json"))["value"])')"
test "$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' "$BASE/api/v1/me")" = 200
CREATED="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-ci-company.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"companyCode":"CI_CSRF_OK","legalName":"CI Company"}' "$BASE/api/v1/companies")"
test "$CREATED" = 201 || { echo "Expected elevated company create 201, got $CREATED"; cat /tmp/hrm-ci-company.json; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/companies" | grep -q 'CI_CSRF_OK'
COMPANY_ID="$(python3 -c 'import json; print(json.load(open("/tmp/hrm-ci-company.json"))["id"])')"
DEPT_HTTP="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-ci-department.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","code":"OPS","name":"Operations"}' "$COMPANY_ID")" "$BASE/api/v1/personnel/departments")"
test "$DEPT_HTTP" = 201 || { echo "Department registration failed: $DEPT_HTTP"; cat /tmp/hrm-ci-department.json; exit 1; }
DEPT_ID="$(python3 -c 'import json; print(json.load(open("/tmp/hrm-ci-department.json"))["id"])')"
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/personnel/departments?companyId=$COMPANY_ID" | grep -q '"code":"OPS"'
DEPT_BLOCK="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","code":"INVALID","name":"Invalid","parentId":"00000000-0000-0000-0000-000000000001"}' "$COMPANY_ID")" "$BASE/api/v1/personnel/departments")"
{ test "$DEPT_BLOCK" = 400 || test "$DEPT_BLOCK" = 403; } || { echo "Invalid department parent accepted: $DEPT_BLOCK"; exit 1; }
EMP_HTTP="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-ci-employee.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","employeeNumber":"CI-001","firstName":"Test","lastName":"Employee","hireDate":"2026-01-01"}' "$COMPANY_ID")" "$BASE/api/v1/personnel/employees")"
test "$EMP_HTTP" = 201 || { echo "Personnel registration failed: $EMP_HTTP"; cat /tmp/hrm-ci-employee.json; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/personnel/employees?companyId=$COMPANY_ID" | grep -q 'CI-001'
EMP_ID="$(python3 -c 'import json; print(json.load(open("/tmp/hrm-ci-employee.json"))["id"])')"
CHANGE_HTTP="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-ci-employment.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"effectiveDate":"2026-04-01","status":"ON_LEAVE","type":"REGULAR","jobTitle":"Analyst","reason":"Approved leave"}' "$BASE/api/v1/personnel/employees/$EMP_ID/employment")"
test "$CHANGE_HTTP" = 201 || { echo "Employment change failed: $CHANGE_HTTP"; cat /tmp/hrm-ci-employment.json; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/personnel/employees/$EMP_ID/employment" | grep -q 'ON_LEAVE'
PAY_HTTP="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-ci-pay.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"effectiveDate":"2026-05-01","amount":25000.00,"payBasis":"MONTHLY","reason":"Initial compensation"}' "$BASE/api/v1/personnel/employees/$EMP_ID/compensation")"
test "$PAY_HTTP" = 201 || { echo "Compensation creation failed: $PAY_HTTP"; cat /tmp/hrm-ci-pay.json; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/personnel/employees/$EMP_ID/compensation" | grep -q '25000.00'
ASSIGN_HTTP="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-ci-assignment.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","departmentId":"%s","effectiveDate":"2026-06-01","reason":"Operations assignment"}' "$COMPANY_ID" "$DEPT_ID")" "$BASE/api/v1/personnel/employees/$EMP_ID/assignments")"
test "$ASSIGN_HTTP" = 201 || { echo "Assignment failed: $ASSIGN_HTTP"; cat /tmp/hrm-ci-assignment.json; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/personnel/employees/$EMP_ID/assignments" | grep -q 'Operations assignment'
CROSS_HTTP="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"companyId":"00000000-0000-0000-0000-000000000001","effectiveDate":"2026-07-01","reason":"Cross company"}' "$BASE/api/v1/personnel/employees/$EMP_ID/assignments")"
{ test "$CROSS_HTTP" = 400 || test "$CROSS_HTTP" = 403; } || { echo "Cross-company assignment not denied: $CROSS_HTTP"; exit 1; }
BASIC_SALARY="$(curl -sS -u "$AUTH" -b "$COOKIE" -c "$COOKIE" -o /dev/null -w '%{http_code}' "$BASE/api/v1/personnel/employees/$EMP_ID/compensation")"
test "$BASIC_SALARY" = 403 || { echo "Basic-only credentials accessed compensation: $BASIC_SALARY"; exit 1; }
ANON_SALARY="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/api/v1/personnel/employees/$EMP_ID/compensation")"
{ test "$ANON_SALARY" = 401 || test "$ANON_SALARY" = 403; } || { echo "Anonymous compensation access: $ANON_SALARY"; exit 1; }
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
