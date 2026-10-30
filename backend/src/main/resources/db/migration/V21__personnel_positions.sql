CREATE TABLE personnel_position (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 company_id uuid NOT NULL,
 position_code varchar(40) NOT NULL,
 position_name varchar(160) NOT NULL,
 active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_position_company FOREIGN KEY (tenant_id,company_id) REFERENCES company(tenant_id,id),
 CONSTRAINT uq_position_company_code UNIQUE(tenant_id,company_id,position_code),
 CONSTRAINT uq_position_company_id UNIQUE(tenant_id,company_id,id)
);
ALTER TABLE personnel_position ENABLE ROW LEVEL SECURITY;
ALTER TABLE personnel_position FORCE ROW LEVEL SECURITY;
CREATE POLICY personnel_position_tenant ON personnel_position USING (tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
ALTER TABLE personnel_assignment_history ADD COLUMN position_id uuid;
ALTER TABLE personnel_assignment_history ADD CONSTRAINT fk_assignment_position
 FOREIGN KEY(tenant_id,company_id,position_id) REFERENCES personnel_position(tenant_id,company_id,id);
ALTER TABLE personnel_employee ADD COLUMN position_id uuid;
ALTER TABLE personnel_employee ADD CONSTRAINT fk_employee_position
 FOREIGN KEY(tenant_id,company_id,position_id) REFERENCES personnel_position(tenant_id,company_id,id);
