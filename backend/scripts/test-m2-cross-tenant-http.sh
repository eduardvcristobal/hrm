#!/usr/bin/env bash
# M2.10: two distinct MFA-assured tenants over the real HTTP security stack.
# Sources alongside synthetic tenant A's already-linked employee token.
: "${BASE:?}" "${BEARER:?}" "${EMP_TOKEN:?}" "${EMP_ID:?}" "${DB_PASSWORD:?}"
OTHER_TOKEN="$(openssl rand -hex 32)"
OTHER_HASH="$(printf '%s' "$OTHER_TOKEN" | sha256sum | cut -d' ' -f1)"
PGPASSWORD="$DB_PASSWORD" psql -X -v ON_ERROR_STOP=1 -h localhost -p 5433 -U "${DB_USERNAME:-hrm_app}" -d hrm_dev -v token_hash="$OTHER_HASH" <<'SQL'
BEGIN;
INSERT INTO tenant(slug,display_name) VALUES('m210-http-other','M2.10 Other Tenant');
INSERT INTO auth_identity(tenant_id,email,password_hash,enabled)
SELECT id,'m210-employee-other@example.invalid','ci-only-unusable',true
FROM tenant WHERE slug='m210-http-other';
INSERT INTO auth_identity_role(identity_id,role)
SELECT id,'EMPLOYEE' FROM auth_identity WHERE email='m210-employee-other@example.invalid';
INSERT INTO auth_api_token(identity_id,token_sha256,expires_at,mfa_verified)
SELECT id,:'token_hash',now()+interval '30 minutes',true
FROM auth_identity WHERE email='m210-employee-other@example.invalid';
SELECT set_config('app.tenant_id',(SELECT id::text FROM tenant WHERE slug='m210-http-other'),true);
INSERT INTO company(tenant_id,company_code,legal_name)
SELECT id,'M210_OTHER','Other synthetic company' FROM tenant WHERE slug='m210-http-other';
INSERT INTO personnel_employee(tenant_id,company_id,employee_number,first_name,last_name,hire_date)
SELECT tenant_id,id,'M210-B','Second','Tenant',DATE '2026-01-01'
FROM company WHERE company_code='M210_OTHER';
INSERT INTO time_employee_identity_link(tenant_id,identity_id,employee_id,linked_by)
SELECT e.tenant_id,i.id,e.id,i.id
FROM personnel_employee e JOIN auth_identity i ON i.tenant_id=e.tenant_id
WHERE e.employee_number='M210-B' AND i.email='m210-employee-other@example.invalid';
INSERT INTO time_ess_device(tenant_id,employee_id,identity_id,display_name)
SELECT e.tenant_id,e.id,i.id,'Other tenant laptop'
FROM personnel_employee e JOIN auth_identity i ON i.tenant_id=e.tenant_id
WHERE e.employee_number='M210-B' AND i.email='m210-employee-other@example.invalid';
COMMIT;
SQL
curl -fsS -H "Authorization: Bearer $OTHER_TOKEN" "$BASE/api/v1/employee-self-service/me" > /tmp/hrm-m210-other-profile.json
OTHER_EMP="$(python3 -c 'import json;print(json.load(open("/tmp/hrm-m210-other-profile.json"))["employee"]["employeeId"])')"
python3 -c 'import json,sys;x=json.load(open("/tmp/hrm-m210-other-profile.json"));assert x["employee"]["employeeId"]!=sys.argv[1]' "$EMP_ID"
curl -fsS -H "Authorization: Bearer $OTHER_TOKEN" "$BASE/api/v1/employee-self-service/devices" > /tmp/hrm-m210-other-devices.json
OTHER_DEVICE="$(python3 -c 'import json;x=json.load(open("/tmp/hrm-m210-other-devices.json"));assert len(x)==1 and x[0]["status"]=="PENDING";print(x[0]["id"])')"
curl -fsS -H "Authorization: Bearer $EMP_TOKEN" "$BASE/api/v1/employee-self-service/me" > /tmp/hrm-m210-original-profile.json
python3 -c 'import json,sys;x=json.load(open("/tmp/hrm-m210-original-profile.json"));assert x["employee"]["employeeId"]==sys.argv[1]' "$EMP_ID"
curl -fsS -H "Authorization: Bearer $BEARER" "$BASE/api/v1/employee-self-service/admin/devices?employeeId=$OTHER_EMP" > /tmp/hrm-m210-foreign-admin-list.json
python3 -c 'import json;x=json.load(open("/tmp/hrm-m210-foreign-admin-list.json"));assert x==[],x'
B_ADMIN_REVOKE="$(curl -sS -H "Authorization: Bearer $BEARER" -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/employee-self-service/admin/devices/$OTHER_DEVICE/revoke")"
{ test "$B_ADMIN_REVOKE" = 403 || test "$B_ADMIN_REVOKE" = 409; } || { echo "Tenant A admin revoked tenant B device: $B_ADMIN_REVOKE"; exit 1; }
A_EMP_REVOKE="$(curl -sS -H "Authorization: Bearer $EMP_TOKEN" -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/employee-self-service/devices/$OTHER_DEVICE/revoke")"
{ test "$A_EMP_REVOKE" = 403 || test "$A_EMP_REVOKE" = 409; } || { echo "Tenant A employee revoked tenant B device: $A_EMP_REVOKE"; exit 1; }
OTHER_ADMIN="$(curl -sS -H "Authorization: Bearer $OTHER_TOKEN" -o /dev/null -w '%{http_code}' "$BASE/api/v1/timekeeping/employees/$EMP_ID/attendance")"
test "$OTHER_ADMIN" = 403 || { echo "Employee B accessed tenant A admin timekeeping: $OTHER_ADMIN"; exit 1; }
curl -fsS -H "Authorization: Bearer $OTHER_TOKEN" "$BASE/api/v1/employee-self-service/devices" > /tmp/hrm-m210-other-devices.json
python3 -c 'import json,sys;x=json.load(open("/tmp/hrm-m210-other-devices.json"));assert len(x)==1 and x[0]["id"]==sys.argv[1] and x[0]["status"]=="PENDING"' "$OTHER_DEVICE"
echo "M2.10 two separately authenticated tenants: employee profiles, device read/write IDOR and admin gate passed"
