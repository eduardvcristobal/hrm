-- M2.5.2: employer-configured carryover preview parameters only.
-- Policies do not grant, expire, transfer or debit leave. A future
-- certification milestone must define and verify actual year-end settlement.
CREATE TABLE time_leave_carryover_preview_policy (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 leave_type_id uuid NOT NULL,
 destination_year integer NOT NULL CHECK(destination_year BETWEEN 2000 AND 2200),
 maximum_days numeric(8,3) NOT NULL CHECK(maximum_days >= 0 AND maximum_days <= 366),
 configured_by uuid NOT NULL,
 configured_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_time_carryover_policy UNIQUE(tenant_id,leave_type_id,destination_year),
 CONSTRAINT fk_time_carryover_type FOREIGN KEY(tenant_id,leave_type_id)
  REFERENCES time_leave_type(tenant_id,id),
 CONSTRAINT fk_time_carryover_actor FOREIGN KEY(tenant_id,configured_by)
  REFERENCES auth_identity(tenant_id,id)
);
ALTER TABLE time_leave_carryover_preview_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_leave_carryover_preview_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY time_carryover_policy_tenant ON time_leave_carryover_preview_policy
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
