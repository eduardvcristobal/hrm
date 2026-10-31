-- M2.5.1: explicit employer-configured leave credit rules and auditable accrual.
-- Credits are informational only. These are not statutory entitlements,
-- spendable balances or payable leave days. Approved requests are NOT debited.
CREATE TABLE time_leave_credit_policy (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 leave_type_id uuid NOT NULL,
 monthly_credit_days numeric(8,3) NOT NULL CHECK(monthly_credit_days >= 0 AND monthly_credit_days <= 31),
 effective_from date NOT NULL,
 active boolean NOT NULL DEFAULT true,
 configured_by uuid NOT NULL,
 configured_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_time_credit_policy_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT uq_time_credit_policy_effective UNIQUE(tenant_id,leave_type_id,effective_from),
 CONSTRAINT fk_time_credit_policy_type FOREIGN KEY(tenant_id,leave_type_id)
   REFERENCES time_leave_type(tenant_id,id),
 CONSTRAINT fk_time_credit_policy_actor FOREIGN KEY(tenant_id,configured_by)
   REFERENCES auth_identity(tenant_id,id)
);
CREATE INDEX ix_time_credit_policy_lookup
 ON time_leave_credit_policy(tenant_id,leave_type_id,effective_from DESC);
CREATE TABLE time_leave_credit_entry (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 employee_id uuid NOT NULL,
 leave_type_id uuid NOT NULL,
 policy_id uuid NOT NULL,
 period_start date NOT NULL,
 credit_days numeric(8,3) NOT NULL CHECK(credit_days >= 0 AND credit_days <= 31),
 posted_by uuid NOT NULL,
 posted_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_time_credit_entry_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT uq_time_credit_entry_month UNIQUE(tenant_id,employee_id,leave_type_id,period_start),
 CONSTRAINT fk_time_credit_entry_employee FOREIGN KEY(tenant_id,employee_id)
   REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_time_credit_entry_type FOREIGN KEY(tenant_id,leave_type_id)
   REFERENCES time_leave_type(tenant_id,id),
 CONSTRAINT fk_time_credit_entry_policy FOREIGN KEY(tenant_id,policy_id)
   REFERENCES time_leave_credit_policy(tenant_id,id),
 CONSTRAINT fk_time_credit_entry_actor FOREIGN KEY(tenant_id,posted_by)
   REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_time_credit_month CHECK(period_start=date_trunc('month',period_start::timestamp)::date)
);
CREATE INDEX ix_time_credit_employee ON time_leave_credit_entry(tenant_id,employee_id,leave_type_id,period_start);
ALTER TABLE time_leave_credit_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_leave_credit_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY time_leave_credit_policy_tenant ON time_leave_credit_policy
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
ALTER TABLE time_leave_credit_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_leave_credit_entry FORCE ROW LEVEL SECURITY;
CREATE POLICY time_leave_credit_entry_tenant ON time_leave_credit_entry
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
