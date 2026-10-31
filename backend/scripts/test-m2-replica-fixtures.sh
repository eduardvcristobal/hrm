#!/usr/bin/env bash
# Provision synthetic TWO-PROCESS fixtures in a disposable CI database ONLY.
set -euo pipefail
: "${DB_PASSWORD:?}" "${DB_USERNAME:?}"
export M210_EMP_TOKEN="$(openssl rand -hex 32)"
export M210_DEVICE_SECRET="$(openssl rand -hex 32)"
TOKEN_HASH="$(printf '%s' "$M210_EMP_TOKEN" | sha256sum | cut -d' ' -f1)"
DEVICE_HASH="$(printf '%s' "$M210_DEVICE_SECRET" | sha256sum | cut -d' ' -f1)"
export M210_EMP_TOKEN M210_DEVICE_SECRET
PGPASSWORD="$DB_PASSWORD" psql -X -t -A -v ON_ERROR_STOP=1 -h localhost -p 5433 -U "$DB_USERNAME" -d hrm_dev -v token_hash="$TOKEN_HASH" -v device_hash="$DEVICE_HASH" <<'SQL'
BEGIN;
INSERT INTO tenant(slug,display_name) VALUES('m210-replica-ci','M2.10 Synthetic Replica CI');
INSERT INTO auth_identity(tenant_id,email,password_hash,enabled)
SELECT id,'m210-replica-employee@example.invalid','ci-no-login',true
FROM tenant WHERE slug='m210-replica-ci';
INSERT INTO auth_identity(tenant_id,email,password_hash,enabled)
SELECT id,'m210-replica-admin@example.invalid','ci-no-login',true
FROM tenant WHERE slug='m210-replica-ci';
INSERT INTO auth_identity_role(identity_id,role)
SELECT id,'EMPLOYEE' FROM auth_identity WHERE email='m210-replica-employee@example.invalid';
INSERT INTO auth_api_token(identity_id,token_sha256,expires_at,mfa_verified)
SELECT id,:'token_hash',now()+interval '1 hour',true
FROM auth_identity WHERE email='m210-replica-employee@example.invalid';
SELECT set_config('app.tenant_id',
 (SELECT id::text FROM tenant WHERE slug='m210-replica-ci'),true);
INSERT INTO company(tenant_id,company_code,legal_name)
SELECT id,'M210_REPLICA','Synthetic M2.10 Company'
FROM tenant WHERE slug='m210-replica-ci';
INSERT INTO personnel_employee(tenant_id,company_id,employee_number,first_name,last_name,hire_date)
SELECT tenant_id,id,'M210-REPLICA','M2.10','Employee',DATE '2026-01-01'
FROM company WHERE company_code='M210_REPLICA';
INSERT INTO time_employee_identity_link(tenant_id,identity_id,employee_id,linked_by)
SELECT e.tenant_id,i.id,e.id,i.id
FROM personnel_employee e JOIN auth_identity i ON i.tenant_id=e.tenant_id
WHERE e.employee_number='M210-REPLICA' AND i.email='m210-replica-employee@example.invalid';
INSERT INTO time_ess_device(
 tenant_id,employee_id,identity_id,display_name,status,reviewed_by,
 reviewed_at,activated_at,credential_sha256,credential_expires_at)
SELECT e.tenant_id,e.id,i.id,'CI two-process enrolled device','ACTIVE',admin.id,
 now(),now(),:'device_hash',now()+interval '1 hour'
FROM personnel_employee e
 JOIN auth_identity i ON i.tenant_id=e.tenant_id
 JOIN auth_identity admin ON admin.tenant_id=e.tenant_id AND admin.email='m210-replica-admin@example.invalid'
WHERE e.employee_number='M210-REPLICA' AND i.email='m210-replica-employee@example.invalid';
\o /tmp/hrm-m210-employee-id
SELECT id FROM personnel_employee WHERE employee_number='M210-REPLICA';
\o
\o /tmp/hrm-m210-device-id
SELECT id FROM time_ess_device WHERE display_name='CI two-process enrolled device';
\o
COMMIT;
SQL
export M210_EMP_ID="$(tr -d '[:space:]' < /tmp/hrm-m210-employee-id)"
export M210_DEVICE_ID="$(tr -d '[:space:]' < /tmp/hrm-m210-device-id)"
test -n "$M210_EMP_ID" && test -n "$M210_DEVICE_ID"
echo 'M2.10 synthetic two-process fixtures created (secrets not printed)'
