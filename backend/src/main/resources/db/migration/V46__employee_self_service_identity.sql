-- M2.9: tenant-verified identity-to-employee binding.
-- An email address or employee-provided UUID is never sufficient to claim a record.
CREATE TABLE time_employee_identity_link (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 identity_id uuid NOT NULL,
 employee_id uuid NOT NULL,
 linked_by uuid NOT NULL,
 linked_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_time_ess_link_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT uq_time_ess_identity UNIQUE(tenant_id,identity_id),
 CONSTRAINT uq_time_ess_employee UNIQUE(tenant_id,employee_id),
 CONSTRAINT fk_time_ess_identity FOREIGN KEY(tenant_id,identity_id) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_time_ess_employee FOREIGN KEY(tenant_id,employee_id) REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_time_ess_admin FOREIGN KEY(tenant_id,linked_by) REFERENCES auth_identity(tenant_id,id)
);
CREATE INDEX ix_time_ess_link_employee ON time_employee_identity_link(tenant_id,employee_id);
ALTER TABLE time_employee_identity_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_employee_identity_link FORCE ROW LEVEL SECURITY;
CREATE POLICY time_ess_link_tenant ON time_employee_identity_link
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
-- Explicit device feature controls: disabled by default; this is a policy
-- placeholder, NOT a device enrollment, biometric or GPS storage mechanism.
CREATE TABLE time_ess_device_policy (
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 company_id uuid NOT NULL,
 employee_clock_in_enabled boolean NOT NULL DEFAULT false CHECK(employee_clock_in_enabled=false),
 geolocation_enabled boolean NOT NULL DEFAULT false CHECK(geolocation_enabled=false),
 biometric_enabled boolean NOT NULL DEFAULT false CHECK(biometric_enabled=false),
 created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,company_id),
 CONSTRAINT fk_time_ess_device_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
 CONSTRAINT fk_time_ess_device_actor FOREIGN KEY(tenant_id,created_by) REFERENCES auth_identity(tenant_id,id)
);
ALTER TABLE time_ess_device_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_ess_device_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY time_ess_device_policy_tenant ON time_ess_device_policy
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
