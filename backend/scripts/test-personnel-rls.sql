\set ON_ERROR_STOP on
-- Disposable database only. All fixtures and grants are rolled back.
BEGIN;
CREATE ROLE hrm_personnel_probe NOLOGIN NOSUPERUSER NOBYPASSRLS;
GRANT USAGE ON SCHEMA public TO hrm_personnel_probe;
GRANT SELECT ON personnel_department,personnel_employee,personnel_employment_history,personnel_assignment_history,personnel_compensation_history,personnel_position,personnel_rehire_request TO hrm_personnel_probe;
CREATE TEMP TABLE p_fixture(a uuid,b uuid,company_a uuid,company_b uuid,emp_a uuid,emp_b uuid);
INSERT INTO p_fixture VALUES (gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid());
INSERT INTO tenant(id,slug,display_name)
 SELECT a,'personnel-ci-a-'||a::text,'Personnel CI A' FROM p_fixture
 UNION ALL SELECT b,'personnel-ci-b-'||b::text,'Personnel CI B' FROM p_fixture;
SELECT set_config('app.tenant_id',a::text,true) FROM p_fixture;
INSERT INTO company(id,tenant_id,company_code,legal_name)
 SELECT company_a,a,'PERSON_A','Personnel A' FROM p_fixture;
INSERT INTO personnel_employee(id,tenant_id,company_id,employee_number,first_name,last_name,hire_date)
 SELECT emp_a,a,company_a,'A-001','Alice','TenantA',DATE '2026-01-01' FROM p_fixture;
SELECT set_config('app.tenant_id',b::text,true) FROM p_fixture;
INSERT INTO company(id,tenant_id,company_code,legal_name)
 SELECT company_b,b,'PERSON_B','Personnel B' FROM p_fixture;
INSERT INTO personnel_employee(id,tenant_id,company_id,employee_number,first_name,last_name,hire_date)
 SELECT emp_b,b,company_b,'B-001','Bob','TenantB',DATE '2026-01-01' FROM p_fixture;
SELECT set_config('app.tenant_id',a::text,true) FROM p_fixture;
INSERT INTO personnel_assignment_history(tenant_id,employee_id,company_id,effective_date,reason,recorded_by)
 SELECT a,emp_a,company_a,DATE '2026-02-01','A assignment',gen_random_uuid() FROM p_fixture;
INSERT INTO personnel_compensation_history(tenant_id,employee_id,effective_date,amount,pay_basis,reason,recorded_by)
 SELECT a,emp_a,DATE '2026-02-01',25000,'MONTHLY','A compensation',gen_random_uuid() FROM p_fixture;
SELECT set_config('app.tenant_id',b::text,true) FROM p_fixture;
INSERT INTO personnel_assignment_history(tenant_id,employee_id,company_id,effective_date,reason,recorded_by)
 SELECT b,emp_b,company_b,DATE '2026-02-01','B assignment',gen_random_uuid() FROM p_fixture;
INSERT INTO personnel_compensation_history(tenant_id,employee_id,effective_date,amount,pay_basis,reason,recorded_by)
 SELECT b,emp_b,DATE '2026-02-01',35000,'MONTHLY','B compensation',gen_random_uuid() FROM p_fixture;
SELECT set_config('app.tenant_id',a::text,true) FROM p_fixture;
INSERT INTO auth_identity(tenant_id,email,password_hash)
 SELECT a,'rehire-a-'||a::text||'@example.invalid','fixture' FROM p_fixture;
INSERT INTO personnel_position(tenant_id,company_id,position_code,position_name)
 SELECT a,company_a,'TEST_A','Position A' FROM p_fixture;
INSERT INTO personnel_rehire_request(tenant_id,employee_id,effective_date,employment_type,reason,requested_by)
 SELECT a,emp_a,DATE '2026-09-01','REGULAR','Tenant A request',id
 FROM p_fixture JOIN auth_identity ON auth_identity.tenant_id=p_fixture.a
 WHERE email LIKE 'rehire-a-%';
SELECT set_config('app.tenant_id',b::text,true) FROM p_fixture;
INSERT INTO auth_identity(tenant_id,email,password_hash)
 SELECT b,'rehire-b-'||b::text||'@example.invalid','fixture' FROM p_fixture;
INSERT INTO personnel_position(tenant_id,company_id,position_code,position_name)
 SELECT b,company_b,'TEST_B','Position B' FROM p_fixture;
INSERT INTO personnel_rehire_request(tenant_id,employee_id,effective_date,employment_type,reason,requested_by)
 SELECT b,emp_b,DATE '2026-09-01','REGULAR','Tenant B request',id
 FROM p_fixture JOIN auth_identity ON auth_identity.tenant_id=p_fixture.b
 WHERE email LIKE 'rehire-b-%';
SELECT a::text AS tenant_a FROM p_fixture \gset
SET LOCAL ROLE hrm_personnel_probe;
SELECT set_config('app.tenant_id', :'tenant_a',true);
DO $check$
BEGIN
 IF (SELECT count(*) FROM personnel_employee WHERE employee_number IN ('A-001','B-001')) <> 1
 THEN RAISE EXCEPTION 'Personnel tenant isolation failed'; END IF;
 IF (SELECT count(*) FROM personnel_assignment_history) <> 1 OR (SELECT count(*) FROM personnel_compensation_history) <> 1
 THEN RAISE EXCEPTION 'Sensitive personnel data breached tenant boundary'; END IF;
 IF EXISTS (SELECT 1 FROM personnel_employee WHERE employee_number='B-001')
 THEN RAISE EXCEPTION 'Cross-tenant personnel exposed'; END IF;
 IF (SELECT count(*) FROM personnel_position)<>1 OR (SELECT count(*) FROM personnel_rehire_request)<>1
 THEN RAISE EXCEPTION 'Position or rehire request leaked across tenants'; END IF;
END $check$;
SELECT set_config('app.tenant_id','',true);
DO $check$
BEGIN
 IF EXISTS (SELECT 1 FROM personnel_employee) OR EXISTS (SELECT 1 FROM personnel_assignment_history)
    OR EXISTS (SELECT 1 FROM personnel_compensation_history)
    OR EXISTS (SELECT 1 FROM personnel_position) OR EXISTS (SELECT 1 FROM personnel_rehire_request)
 THEN RAISE EXCEPTION 'Personnel visible without tenant context'; END IF;
END $check$;
RESET ROLE;
ROLLBACK;
