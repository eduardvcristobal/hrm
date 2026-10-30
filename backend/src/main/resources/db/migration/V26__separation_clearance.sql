CREATE TABLE personnel_separation_clearance (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 hr_case_id uuid NOT NULL,
 clearance_code varchar(40) NOT NULL CHECK(clearance_code ~ '^[A-Z0-9_]+$'),
 label varchar(160) NOT NULL,
 required boolean NOT NULL DEFAULT true,
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','CLEARED','BLOCKED')),
 reviewed_by uuid,
 reviewed_at timestamptz,
 CONSTRAINT fk_clearance_hr_case FOREIGN KEY(tenant_id,hr_case_id) REFERENCES personnel_hr_case(tenant_id,id),
 CONSTRAINT fk_clearance_reviewer FOREIGN KEY(tenant_id,reviewed_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT uq_clearance_code UNIQUE(tenant_id,hr_case_id,clearance_code),
 CONSTRAINT ck_clearance_review CHECK((status='PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL)
 OR(status IN ('CLEARED','BLOCKED') AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);
ALTER TABLE personnel_separation_clearance ENABLE ROW LEVEL SECURITY;
ALTER TABLE personnel_separation_clearance FORCE ROW LEVEL SECURITY;
CREATE POLICY separation_clearance_tenant ON personnel_separation_clearance USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
