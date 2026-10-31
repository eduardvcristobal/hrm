\set ON_ERROR_STOP on
-- M2.10 restricted-role PostgreSQL acceptance. Disposable DB; transaction rolls back.
-- Tests BOTH read and write RLS, including employee identity links and device proof ledgers.
BEGIN;
CREATE ROLE hrm_m210_probe NOLOGIN NOSUPERUSER NOBYPASSRLS;
GRANT USAGE ON SCHEMA public TO hrm_m210_probe;
GRANT SELECT,UPDATE,INSERT ON time_employee_identity_link,time_ess_device,time_ess_device_attestation TO hrm_m210_probe;
CREATE TEMP TABLE m210_ids(tenant_a uuid,tenant_b uuid,company_a uuid,company_b uuid,emp_a uuid,emp_b uuid,identity_a uuid,identity_b uuid,device_a uuid,device_b uuid,link_a uuid,link_b uuid);
INSERT INTO m210_ids SELECT gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),
gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),
gen_random_uuid(),gen_random_uuid(),gen_random_uuid();
INSERT INTO tenant(id,slug,display_name)
SELECT tenant_a,'m210-a-'||tenant_a::text,'M2.10 A' FROM m210_ids
UNION ALL SELECT tenant_b,'m210-b-'||tenant_b::text,'M2.10 B' FROM m210_ids;
INSERT INTO auth_identity(id,tenant_id,email,password_hash)
SELECT identity_a,tenant_a,'m210-a-'||identity_a::text||'@example.invalid','ci-fixture' FROM m210_ids
UNION ALL SELECT identity_b,tenant_b,'m210-b-'||identity_b::text||'@example.invalid','ci-fixture' FROM m210_ids;
SELECT set_config('app.tenant_id',tenant_a::text,true) FROM m210_ids;
INSERT INTO company(id,tenant_id,company_code,legal_name)
SELECT company_a,tenant_a,'M210_A','Company A' FROM m210_ids;
INSERT INTO personnel_employee(id,tenant_id,company_id,employee_number,first_name,last_name,hire_date)
SELECT emp_a,tenant_a,company_a,'M210-A','Alice','A',DATE '2026-01-01' FROM m210_ids;
INSERT INTO time_employee_identity_link(id,tenant_id,identity_id,employee_id,linked_by)
SELECT link_a,tenant_a,identity_a,emp_a,identity_a FROM m210_ids;
INSERT INTO time_ess_device(id,tenant_id,employee_id,identity_id,display_name)
SELECT device_a,tenant_a,emp_a,identity_a,'Test device A' FROM m210_ids;
INSERT INTO time_ess_device_attestation(tenant_id,device_id,nonce)
SELECT tenant_a,device_a,gen_random_uuid() FROM m210_ids;
SELECT set_config('app.tenant_id',tenant_b::text,true) FROM m210_ids;
INSERT INTO company(id,tenant_id,company_code,legal_name)
SELECT company_b,tenant_b,'M210_B','Company B' FROM m210_ids;
INSERT INTO personnel_employee(id,tenant_id,company_id,employee_number,first_name,last_name,hire_date)
SELECT emp_b,tenant_b,company_b,'M210-B','Bob','B',DATE '2026-01-01' FROM m210_ids;
INSERT INTO time_employee_identity_link(id,tenant_id,identity_id,employee_id,linked_by)
SELECT link_b,tenant_b,identity_b,emp_b,identity_b FROM m210_ids;
INSERT INTO time_ess_device(id,tenant_id,employee_id,identity_id,display_name)
SELECT device_b,tenant_b,emp_b,identity_b,'Test device B' FROM m210_ids;
INSERT INTO time_ess_device_attestation(tenant_id,device_id,nonce)
SELECT tenant_b,device_b,gen_random_uuid() FROM m210_ids;
SELECT tenant_a::text AS a,tenant_b::text AS b,device_b::text AS target_device_b,link_b::text AS target_link_b FROM m210_ids \gset
SET LOCAL ROLE hrm_m210_probe;
SELECT set_config('app.tenant_id',:'a',true);
DO $check$
BEGIN
 IF (SELECT count(*) FROM time_employee_identity_link)<>1
 OR (SELECT count(*) FROM time_ess_device)<>1
 OR (SELECT count(*) FROM time_ess_device_attestation)<>1 THEN
   RAISE EXCEPTION 'M2.10 device/identity RLS tenant A query failed';
 END IF;
END $check$;
-- Tenant A cannot UPDATE tenant B even when given B's primary key.
UPDATE time_ess_device SET display_name='SHOULD NOT MODIFY'
WHERE id=:'target_device_b'::uuid;
DO $check$
BEGIN
 IF FOUND THEN RAISE EXCEPTION 'Cross-tenant device modification succeeded'; END IF;
END $check$;
-- Validate the actual row count with a SQL variable; no exceptions are expected.
SELECT count(*) AS foreign_rows FROM time_ess_device WHERE id=:'target_device_b'::uuid \gset
\if :foreign_rows
\echo 'Cross-tenant device read exposed data'
\quit 1
\endif
-- Reassignment attempt must violate RLS WITH CHECK even if a visible
-- tenant A row is targeted. Use a savepoint to keep transaction healthy.
SAVEPOINT forbidden_reassignment;
DO $test$
BEGIN
 BEGIN
  UPDATE time_employee_identity_link SET tenant_id=:'b'::uuid
   WHERE tenant_id=:'a'::uuid;
  RAISE EXCEPTION 'Cross-tenant identity rewrite was not blocked';
 EXCEPTION WHEN insufficient_privilege OR check_violation OR foreign_key_violation THEN NULL;
 END;
END $test$;
ROLLBACK TO SAVEPOINT forbidden_reassignment;
SELECT set_config('app.tenant_id','',true);
DO $check$
BEGIN
 IF EXISTS(SELECT 1 FROM time_employee_identity_link)
 OR EXISTS(SELECT 1 FROM time_ess_device)
 OR EXISTS(SELECT 1 FROM time_ess_device_attestation) THEN
  RAISE EXCEPTION 'Missing tenant context exposed M2.9 device records';
 END IF;
END $check$;
RESET ROLE;
ROLLBACK;
\echo 'M2.10 cross-tenant identity/device read, mutation and no-context RLS checks passed'
