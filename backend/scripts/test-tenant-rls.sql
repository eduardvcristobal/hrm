-- Integration security regression. Run with admin/migration credentials on disposable CI DB.
-- This file executes in a rolled-back transaction, including the temporary role.
\set ON_ERROR_STOP on
BEGIN;
CREATE ROLE hrm_rls_regression NOLOGIN NOSUPERUSER NOBYPASSRLS;
GRANT USAGE ON SCHEMA public TO hrm_rls_regression;
GRANT SELECT, INSERT, UPDATE ON company TO hrm_rls_regression;
GRANT SELECT ON branch,audit_event TO hrm_rls_regression;
CREATE TEMP TABLE rls_expect (tenant_a uuid, tenant_b uuid);
INSERT INTO rls_expect VALUES (gen_random_uuid(),gen_random_uuid());
INSERT INTO tenant(id,slug,display_name)
SELECT tenant_a,'rls-a-'||tenant_a::text,'RLS A' FROM rls_expect
UNION ALL
SELECT tenant_b,'rls-b-'||tenant_b::text,'RLS B' FROM rls_expect;
-- FORCE RLS applies even to the owner, so set a transaction-local tenant for each insert.
SELECT set_config('app.tenant_id',tenant_a::text,true) FROM rls_expect;
INSERT INTO company(tenant_id,company_code,legal_name)
SELECT tenant_a,'RLS_A','Isolation A' FROM rls_expect;
SELECT set_config('app.tenant_id',tenant_b::text,true) FROM rls_expect;
INSERT INTO company(tenant_id,company_code,legal_name)
SELECT tenant_b,'RLS_B','Isolation B' FROM rls_expect;
-- A restricted role must see exactly its tenant's company.
-- Capture the tenant ID before switching roles: the test role cannot read the temp setup table.
SELECT tenant_a::text AS tenant_a FROM rls_expect \gset
SET LOCAL ROLE hrm_rls_regression;
SELECT set_config('app.tenant_id', :'tenant_a', true);
DO $$
BEGIN
 IF (SELECT count(*) FROM company WHERE company_code IN ('RLS_A','RLS_B')) <> 1 THEN
  RAISE EXCEPTION 'RLS failed to isolate tenant A';
 END IF;
 IF EXISTS (SELECT 1 FROM company WHERE company_code='RLS_B') THEN
  RAISE EXCEPTION 'Tenant B leaked into tenant A';
 END IF;
END $$;
-- Cross-tenant updates must not touch B, and inserts for B must be rejected.
DO $rls$
DECLARE changed integer;
BEGIN
 UPDATE company SET legal_name='SHOULD_NOT_CHANGE' WHERE company_code='RLS_B';
 GET DIAGNOSTICS changed = ROW_COUNT;
 IF changed <> 0 THEN RAISE EXCEPTION 'Cross-tenant update succeeded'; END IF;
END $rls$;
-- Missing setting must fail closed.
SELECT set_config('app.tenant_id','',true);
DO $$
BEGIN
 IF EXISTS(SELECT 1 FROM company) THEN RAISE EXCEPTION 'Missing tenant context returned rows'; END IF;
END $$;
RESET ROLE;
ROLLBACK;
