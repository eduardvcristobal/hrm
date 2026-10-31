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
POSITION_HTTP="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-ci-position.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","code":"ANALYST","name":"Business Analyst"}' "$COMPANY_ID")" "$BASE/api/v1/personnel/positions")"
test "$POSITION_HTTP" = 201 || { echo "Position registration failed: $POSITION_HTTP"; cat /tmp/hrm-ci-position.json; exit 1; }
POSITION_ID="$(python3 -c 'import json; print(json.load(open("/tmp/hrm-ci-position.json"))["id"])')"
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/personnel/positions?companyId=$COMPANY_ID" | grep -q 'ANALYST'
ASSIGN_HTTP="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-ci-assignment.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","departmentId":"%s","positionId":"%s","effectiveDate":"2026-06-01","reason":"Operations assignment"}' "$COMPANY_ID" "$DEPT_ID" "$POSITION_ID")" "$BASE/api/v1/personnel/employees/$EMP_ID/assignments")"
test "$ASSIGN_HTTP" = 201 || { echo "Assignment failed: $ASSIGN_HTTP"; cat /tmp/hrm-ci-assignment.json; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/personnel/employees/$EMP_ID/assignments" | grep -q 'Operations assignment'
CROSS_HTTP="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"companyId":"00000000-0000-0000-0000-000000000001","effectiveDate":"2026-07-01","reason":"Cross company"}' "$BASE/api/v1/personnel/employees/$EMP_ID/assignments")"
{ test "$CROSS_HTTP" = 400 || test "$CROSS_HTTP" = 403; } || { echo "Cross-company assignment not denied: $CROSS_HTTP"; exit 1; }
BASIC_SALARY="$(curl -sS -u "$AUTH" -b "$COOKIE" -c "$COOKIE" -o /dev/null -w '%{http_code}' "$BASE/api/v1/personnel/employees/$EMP_ID/compensation")"
test "$BASIC_SALARY" = 403 || { echo "Basic-only credentials accessed compensation: $BASIC_SALARY"; exit 1; }
ANON_SALARY="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/api/v1/personnel/employees/$EMP_ID/compensation")"
{ test "$ANON_SALARY" = 401 || test "$ANON_SALARY" = 403; } || { echo "Anonymous compensation access: $ANON_SALARY"; exit 1; }
PROFILE_PATCH="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-ci-profile.json -w '%{http_code}' -X PATCH -H 'Content-Type: application/json' --data '{"middleName":"Initial","preferredName":"Team Member","suffix":null}' "$BASE/api/v1/personnel/employees/$EMP_ID/profile")"
test "$PROFILE_PATCH" = 200 || { echo "Profile update failed: $PROFILE_PATCH"; cat /tmp/hrm-ci-profile.json; exit 1; }
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/personnel/employees/$EMP_ID/profile" | grep -q 'Team Member'
PRIVATE_WRITE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X PUT -H 'Content-Type: application/json' --data '{"governmentId":"TEST-ID-123","contactEmail":"test@example.invalid","mobile":"09170000000","bankAccount":"TEST-BANK-456"}' "$BASE/api/v1/personnel/employees/$EMP_ID/private")"
test "$PRIVATE_WRITE" = 200 || { echo "Private field update failed: $PRIVATE_WRITE"; exit 1; }
PRIVATE_GET="$(curl -sS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/personnel/employees/$EMP_ID/private")"
echo "$PRIVATE_GET" | grep -q 'TEST-ID-123'
BASIC_PRIVATE="$(curl -sS -u "$AUTH" -b "$COOKIE" -c "$COOKIE" -o /dev/null -w '%{http_code}' "$BASE/api/v1/personnel/employees/$EMP_ID/private")"
test "$BASIC_PRIVATE" = 403 || { echo "Basic-only private data disclosure: $BASIC_PRIVATE"; exit 1; }
PGPASSWORD="$DB_PASSWORD" psql -X -At -h localhost -p 5433 -U "${DB_USERNAME:-hrm_app}" -d hrm_dev -c "SELECT government_id_ciphertext FROM personnel_private_data WHERE employee_id='$EMP_ID'" | grep -q '^v1:'
POSITION_RETIRE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X PATCH -H 'Content-Type: application/json' --data '{"active":false}' "$BASE/api/v1/personnel/positions/$POSITION_ID/status")"
{ test "$POSITION_RETIRE" = 409 || test "$POSITION_RETIRE" = 403; } || { echo "Assigned position was retired: $POSITION_RETIRE"; exit 1; }
REPORT_HTTP="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-ci-supervisor.json -w '%{http_code}' -X PUT -H 'Content-Type: application/json' --data '{"supervisorId":null}' "$BASE/api/v1/personnel/employees/$EMP_ID/supervisor")"
test "$REPORT_HTTP" = 200 || { echo "Supervisor update failed: $REPORT_HTTP"; cat /tmp/hrm-ci-supervisor.json; exit 1; }
SELF_REPORT="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X PUT -H 'Content-Type: application/json' --data "$(printf '{"supervisorId":"%s"}' "$EMP_ID")" "$BASE/api/v1/personnel/employees/$EMP_ID/supervisor")"
{ test "$SELF_REPORT" = 400 || test "$SELF_REPORT" = 403; } || { echo "Self supervisor accepted: $SELF_REPORT"; exit 1; }
CHILD_DEPT="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-child-dept.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "$(printf '{"companyId":"%s","code":"OPS_CHILD","name":"Operations Child","parentId":"%s"}' "$COMPANY_ID" "$DEPT_ID")" "$BASE/api/v1/personnel/departments")"
test "$CHILD_DEPT" = 201 || { echo "Child department failed: $CHILD_DEPT"; exit 1; }
CHILD_ID="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-child-dept.json"))["id"])')"
CYCLE_STATUS="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X PATCH -H 'Content-Type: application/json' --data "$(printf '{"parentId":"%s"}' "$CHILD_ID")" "$BASE/api/v1/personnel/departments/$DEPT_ID/parent")"
{ test "$CYCLE_STATUS" = 403 || test "$CYCLE_STATUS" = 409; } || { echo "Department cycle accepted: $CYCLE_STATUS"; exit 1; }
SEPARATION="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"effectiveDate":"2026-08-01","status":"TERMINATED","type":"REGULAR","reason":"CI separation"}' "$BASE/api/v1/personnel/employees/$EMP_ID/employment")"
test "$SEPARATION" = 201 || { echo "Separation failed: $SEPARATION"; exit 1; }
REHIRE_REQUEST="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-rehire.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"effectiveDate":"2026-09-01","employmentType":"REGULAR","reason":"CI rehire request"}' "$BASE/api/v1/personnel/employees/$EMP_ID/rehire-requests")"
test "$REHIRE_REQUEST" = 201 || { echo "Rehire request failed: $REHIRE_REQUEST"; cat /tmp/hrm-rehire.json; exit 1; }
REHIRE_ID="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-rehire.json"))["id"])')"
SELF_APPROVAL="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/personnel/employees/$EMP_ID/rehire-requests/$REHIRE_ID/approve")"
test "$SELF_APPROVAL" = 403 || { echo "Same-actor rehire approval allowed: $SELF_APPROVAL"; exit 1; }
GENERIC_REHIRE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"effectiveDate":"2026-09-01","status":"ACTIVE","type":"REGULAR","reason":"Attempted bypass"}' "$BASE/api/v1/personnel/employees/$EMP_ID/employment")"
{ test "$GENERIC_REHIRE" = 403 || test "$GENERIC_REHIRE" = 409; } || { echo "Generic rehire bypass allowed: $GENERIC_REHIRE"; exit 1; }
CANCEL_HTTP="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-cancelled-rehire.json -w '%{http_code}' -X POST "$BASE/api/v1/personnel/employees/$EMP_ID/rehire-requests/$REHIRE_ID/cancel")"
test "$CANCEL_HTTP" = 200 || { echo "Rehire cancellation failed: $CANCEL_HTTP"; cat /tmp/hrm-cancelled-rehire.json; exit 1; }
python3 -c 'import json;assert json.load(open("/tmp/hrm-cancelled-rehire.json"))["status"]=="CANCELLED"'
CANCEL_AGAIN="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/personnel/employees/$EMP_ID/rehire-requests/$REHIRE_ID/cancel")"
{ test "$CANCEL_AGAIN" = 409 || test "$CANCEL_AGAIN" = 403; } || { echo "Repeat cancellation accepted: $CANCEL_AGAIN"; exit 1; }
APPROVE_CANCELLED="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/personnel/employees/$EMP_ID/rehire-requests/$REHIRE_ID/approve")"
{ test "$APPROVE_CANCELLED" = 403 || test "$APPROVE_CANCELLED" = 404; } || { echo "Cancelled rehire approval accepted: $APPROVE_CANCELLED"; exit 1; }
REPLACEMENT_HTTP="$(curl -sS -H "Authorization: Bearer $BEARER" -o /tmp/hrm-rehire-replacement.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"effectiveDate":"2026-09-01","employmentType":"REGULAR","reason":"Replacement pending request"}' "$BASE/api/v1/personnel/employees/$EMP_ID/rehire-requests")"
test "$REPLACEMENT_HTTP" = 201 || { echo "Replacement rehire failed: $REPLACEMENT_HTTP"; exit 1; }
REHIRE_ID="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-rehire-replacement.json"))["id"])')"
export EMP_ID REHIRE_ID
bash scripts/test-rehire-dual-control.sh
source scripts/test-hr-workflows.sh
source scripts/test-payroll-foundation.sh
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
