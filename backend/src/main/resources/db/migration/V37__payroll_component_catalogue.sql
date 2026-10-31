-- M3.2: company-scoped earning/deduction vocabulary only.
-- No amounts, rates, formulas, employee allocations, contributions, tax or payouts.
CREATE TABLE payroll_component_definition (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 company_id uuid NOT NULL,
 component_code varchar(40) NOT NULL,
 component_name varchar(160) NOT NULL,
 component_kind varchar(16) NOT NULL CHECK(component_kind IN ('EARNING','DEDUCTION')),
 treatment varchar(24) NOT NULL DEFAULT 'UNREVIEWED' CHECK(treatment='UNREVIEWED'),
 effective_from date NOT NULL,
 description varchar(250),
 created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_payroll_component_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT uq_payroll_component_version UNIQUE(tenant_id,company_id,component_code,effective_from),
 CONSTRAINT fk_payroll_component_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
 CONSTRAINT fk_payroll_component_actor FOREIGN KEY(tenant_id,created_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_payroll_component_code CHECK(component_code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
 CONSTRAINT ck_payroll_component_name CHECK(length(trim(component_name))>=3)
);
CREATE INDEX ix_payroll_component_company
 ON payroll_component_definition(tenant_id,company_id,component_kind,component_code,effective_from DESC);
ALTER TABLE payroll_component_definition ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_component_definition FORCE ROW LEVEL SECURITY;
CREATE POLICY payroll_component_definition_tenant ON payroll_component_definition
 USING (tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
