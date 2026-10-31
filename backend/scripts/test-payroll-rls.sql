\set ON_ERROR_STOP on
-- M3.1–M3.2 isolation. Disposable PostgreSQL only; all changes rolled back.
BEGIN;
CREATE ROLE m3_payroll_rls_probe NOLOGIN NOSUPERUSER NOBYPASSRLS;
GRANT USAGE ON SCHEMA public TO m3_payroll_rls_probe;
GRANT SELECT,UPDATE ON payroll_configuration,payroll_period,payroll_component_definition TO m3_payroll_rls_probe;
CREATE TEMP TABLE m3_fixture(
 tenant_a uuid,tenant_b uuid,company_a uuid,company_b uuid,
 actor_a uuid,actor_b uuid,config_a uuid,config_b uuid,period_a uuid,period_b uuid
);
INSERT INTO m3_fixture SELECT gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),
 gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid();
INSERT INTO tenant(id,slug,display_name)
SELECT tenant_a,'payroll-a-'||tenant_a::text,'M3 Tenant A' FROM m3_fixture
UNION ALL SELECT tenant_b,'payroll-b-'||tenant_b::text,'M3 Tenant B' FROM m3_fixture;
INSERT INTO auth_identity(id,tenant_id,email,password_hash)
SELECT actor_a,tenant_a,'payroll-a-'||actor_a::text||'@example.invalid','test-hash' FROM m3_fixture
UNION ALL SELECT actor_b,tenant_b,'payroll-b-'||actor_b::text||'@example.invalid','test-hash' FROM m3_fixture;
SELECT set_config('app.tenant_id',tenant_a::text,true) FROM m3_fixture;
INSERT INTO company(id,tenant_id,company_code,legal_name)
SELECT company_a,tenant_a,'M3_A','Company A' FROM m3_fixture;
INSERT INTO payroll_configuration(id,tenant_id,company_id,pay_frequency,currency_code,timezone,effective_from,created_by)
SELECT config_a,tenant_a,company_a,'SEMI_MONTHLY','PHP','Asia/Manila',DATE '2026-01-01',actor_a FROM m3_fixture;
INSERT INTO payroll_period(id,tenant_id,company_id,configuration_id,starts_on,ends_on,pay_on,created_by)
SELECT period_a,tenant_a,company_a,config_a,DATE '2026-11-01',DATE '2026-11-15',
 DATE '2026-11-20',actor_a FROM m3_fixture;
INSERT INTO payroll_component_definition(tenant_id,company_id,component_code,component_name,component_kind,effective_from,created_by)
SELECT tenant_a,company_a,'BASIC','Basic income','EARNING',DATE '2026-01-01',actor_a FROM m3_fixture;
SELECT set_config('app.tenant_id',tenant_b::text,true) FROM m3_fixture;
INSERT INTO company(id,tenant_id,company_code,legal_name)
SELECT company_b,tenant_b,'M3_B','Company B' FROM m3_fixture;
INSERT INTO payroll_configuration(id,tenant_id,company_id,pay_frequency,currency_code,timezone,effective_from,created_by)
SELECT config_b,tenant_b,company_b,'MONTHLY','PHP','Asia/Manila',DATE '2026-01-01',actor_b FROM m3_fixture;
INSERT INTO payroll_period(id,tenant_id,company_id,configuration_id,starts_on,ends_on,pay_on,created_by)
SELECT period_b,tenant_b,company_b,config_b,DATE '2026-11-01',DATE '2026-11-30',
 DATE '2026-12-05',actor_b FROM m3_fixture;
INSERT INTO payroll_component_definition(tenant_id,company_id,component_code,component_name,component_kind,effective_from,created_by)
SELECT tenant_b,company_b,'DEDUCTION','Other deduction','DEDUCTION',DATE '2026-01-01',actor_b FROM m3_fixture;
SELECT tenant_a::text AS tenant_a,config_b::text AS config_b,period_b::text AS period_b FROM m3_fixture \gset
SET LOCAL ROLE m3_payroll_rls_probe;
SELECT set_config('app.tenant_id',:'tenant_a',true);
DO $check$
BEGIN
 IF (SELECT count(*) FROM payroll_configuration)<>1
  OR (SELECT count(*) FROM payroll_period)<>1
  OR (SELECT count(*) FROM payroll_component_definition)<>1
 THEN RAISE EXCEPTION 'M3 payroll RLS SELECT leaked tenant records'; END IF;
 IF EXISTS(SELECT 1 FROM payroll_component_definition WHERE component_kind='DEDUCTION')
 THEN RAISE EXCEPTION 'M3 payroll catalogue leaked cross-tenant deduction'; END IF;
END $check$;
WITH changed AS (
 UPDATE payroll_configuration SET timezone='UTC'
 WHERE id=:'config_b'::uuid RETURNING id
) SELECT count(*) AS modified FROM changed \gset
\if :modified
\echo 'M3 RLS leaked cross-tenant configuration UPDATE'
\quit
\endif
WITH changed AS (
 UPDATE payroll_period SET pay_on=DATE '2026-12-10'
 WHERE id=:'period_b'::uuid RETURNING id
) SELECT count(*) AS modified FROM changed \gset
\if :modified
\echo 'M3 RLS leaked cross-tenant period UPDATE'
\quit
\endif
SELECT set_config('app.tenant_id','',true);
DO $check$
BEGIN
 IF EXISTS(SELECT 1 FROM payroll_configuration)
 OR EXISTS(SELECT 1 FROM payroll_period)
 OR EXISTS(SELECT 1 FROM payroll_component_definition)
 THEN RAISE EXCEPTION 'M3 payroll tables exposed data with no tenant context'; END IF;
END $check$;
RESET ROLE;
ROLLBACK;
\echo 'M3 RLS restricted-role company/period/component isolation passed'
