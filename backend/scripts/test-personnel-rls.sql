\set ON_ERROR_STOP on
-- Disposable database only. All fixtures and grants are rolled back.
BEGIN;
CREATE ROLE hrm_personnel_probe NOLOGIN NOSUPERUSER NOBYPASSRLS;
GRANT USAGE ON SCHEMA public TO hrm_personnel_probe;
GRANT SELECT ON personnel_department,personnel_employee,personnel_employment_history TO hrm_personnel_probe;
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
SELECT a::text AS tenant_a FROM p_fixture \gset
SET LOCAL ROLE hrm_personnel_probe;
SELECT set_config('app.tenant_id', :'tenant_a',true);
DO $check$
BEGIN
 IF (SELECT count(*) FROM personnel_employee WHERE employee_number IN ('A-001','B-001')) <> 1
 THEN RAISE EXCEPTION 'Personnel tenant isolation failed'; END IF;
 IF EXISTS (SELECT 1 FROM personnel_employee WHERE employee_number='B-001')
 THEN RAISE EXCEPTION 'Cross-tenant personnel exposed'; END IF;
END $check$;
SELECT set_config('app.tenant_id','',true);
DO $check$
BEGIN
 IF EXISTS (SELECT 1 FROM personnel_employee)
 THEN RAISE EXCEPTION 'Personnel visible without tenant context'; END IF;
END $check$;
RESET ROLE;
ROLLBACK;
