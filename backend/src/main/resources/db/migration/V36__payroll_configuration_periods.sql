-- M3.1 Payroll foundation sourced strictly from main (M1, highest V35).
-- No M2 tables referenced; no payroll run, calculation, release or payment execution.
CREATE TABLE payroll_configuration (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  company_id uuid NOT NULL,
  currency_code char(3) NOT NULL DEFAULT 'PHP',
  pay_frequency varchar(20) NOT NULL CHECK(pay_frequency IN ('SEMI_MONTHLY','MONTHLY','WEEKLY','BIWEEKLY')),
  timezone varchar(64) NOT NULL DEFAULT 'Asia/Manila',
  effective_from date NOT NULL,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_payroll_configuration_tenant_id UNIQUE(tenant_id,id),
  CONSTRAINT uq_payroll_configuration_effective UNIQUE(tenant_id,company_id,effective_from),
  CONSTRAINT fk_payroll_configuration_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
  CONSTRAINT fk_payroll_configuration_actor FOREIGN KEY(tenant_id,created_by) REFERENCES auth_identity(tenant_id,id),
  CONSTRAINT ck_payroll_config_currency CHECK(currency_code ~ '^[A-Z]{3}$')
);
CREATE INDEX ix_payroll_configuration_company ON payroll_configuration(tenant_id,company_id,effective_from DESC);
ALTER TABLE payroll_configuration ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_configuration FORCE ROW LEVEL SECURITY;
CREATE POLICY payroll_configuration_tenant ON payroll_configuration
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());

CREATE TABLE payroll_period (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 company_id uuid NOT NULL,
 configuration_id uuid NOT NULL,
 starts_on date NOT NULL,
 ends_on date NOT NULL,
 pay_on date NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK(status='DRAFT'),
 created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_payroll_period_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT uq_payroll_period_company_start UNIQUE(tenant_id,company_id,starts_on),
 CONSTRAINT fk_payroll_period_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
 CONSTRAINT fk_payroll_period_configuration FOREIGN KEY(tenant_id,configuration_id) REFERENCES payroll_configuration(tenant_id,id),
 CONSTRAINT fk_payroll_period_actor FOREIGN KEY(tenant_id,created_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_payroll_period_dates CHECK(ends_on>=starts_on AND ends_on<starts_on+interval '32 days' AND pay_on>=starts_on)
);
CREATE INDEX ix_payroll_period_company ON payroll_period(tenant_id,company_id,starts_on DESC);
ALTER TABLE payroll_period ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_period FORCE ROW LEVEL SECURITY;
CREATE POLICY payroll_period_tenant ON payroll_period
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());

-- Prevent a malicious cross-company pairing even when caller holds valid ids.
ALTER TABLE payroll_configuration ADD CONSTRAINT uq_payroll_cfg_tenant_company_id UNIQUE(tenant_id,company_id,id);
ALTER TABLE payroll_period ADD CONSTRAINT fk_payroll_period_same_company_configuration
 FOREIGN KEY(tenant_id,company_id,configuration_id)
 REFERENCES payroll_configuration(tenant_id,company_id,id);

-- DB-enforced no-overlap for DRAFT periods, including concurrent inserts.
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE payroll_period ADD CONSTRAINT exclude_payroll_period_overlap
 EXCLUDE USING gist(tenant_id WITH =, company_id WITH =, daterange(starts_on,ends_on,'[]') WITH &&);
