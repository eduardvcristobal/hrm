-- M0.2: tenant-safe platform data and login identities.
-- Login identities are global by design: resolving an email precedes knowing a tenant.
CREATE TABLE auth_identity (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 email varchar(254) NOT NULL UNIQUE,
 password_hash varchar(255) NOT NULL,
 enabled boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_auth_identity_tenant_id UNIQUE (tenant_id,id)
);
CREATE TABLE auth_identity_role (
 identity_id uuid NOT NULL REFERENCES auth_identity(id) ON DELETE CASCADE,
 role varchar(40) NOT NULL,
 PRIMARY KEY(identity_id, role),
 CONSTRAINT ck_auth_role CHECK (role IN ('TENANT_ADMIN','HR_ADMIN','PAYROLL_OFFICER','MANAGER','EMPLOYEE','AUDITOR'))
);
CREATE INDEX ix_auth_identity_tenant ON auth_identity(tenant_id);
-- Tenant-scoped business tables must use a transaction-local tenant context.
-- Missing/invalid context evaluates to NULL and returns no rows.
CREATE FUNCTION app_tenant_id() RETURNS uuid LANGUAGE sql STABLE AS $$
 SELECT NULLIF(current_setting('app.tenant_id',true),'')::uuid
$$;
ALTER TABLE company ENABLE ROW LEVEL SECURITY;
ALTER TABLE company FORCE ROW LEVEL SECURITY;
CREATE POLICY company_tenant_policy ON company
 USING (tenant_id = app_tenant_id()) WITH CHECK (tenant_id = app_tenant_id());
ALTER TABLE branch ENABLE ROW LEVEL SECURITY;
ALTER TABLE branch FORCE ROW LEVEL SECURITY;
CREATE POLICY branch_tenant_policy ON branch
 USING (tenant_id = app_tenant_id()) WITH CHECK (tenant_id = app_tenant_id());
ALTER TABLE audit_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_event FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_tenant_policy ON audit_event
 USING (tenant_id = app_tenant_id()) WITH CHECK (tenant_id = app_tenant_id());
-- tenant is a global administrative table; never expose it through a tenant API.
-- Production must separate Flyway owner and restricted application DB accounts.
