-- Explicit tenant policy; default zero waiting days preserves existing workflows.
CREATE TABLE personnel_rehire_policy (
 tenant_id uuid PRIMARY KEY REFERENCES tenant(id),
 minimum_wait_days integer NOT NULL DEFAULT 0 CHECK(minimum_wait_days BETWEEN 0 AND 3650),
 enabled boolean NOT NULL DEFAULT true,
 updated_by uuid,
 updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_rehire_policy_actor FOREIGN KEY(tenant_id,updated_by) REFERENCES auth_identity(tenant_id,id)
);
ALTER TABLE personnel_rehire_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE personnel_rehire_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY rehire_policy_tenant ON personnel_rehire_policy USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
