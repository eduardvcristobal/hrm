CREATE TABLE personnel_separation_asset (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 hr_case_id uuid NOT NULL,
 asset_tag varchar(80) NOT NULL,
 description varchar(200) NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'OUTSTANDING' CHECK(status IN ('OUTSTANDING','RETURNED','EXCEPTION')),
 resolved_by uuid,
 resolved_at timestamptz,
 CONSTRAINT fk_sep_asset_case FOREIGN KEY(tenant_id,hr_case_id) REFERENCES personnel_hr_case(tenant_id,id),
 CONSTRAINT fk_sep_asset_reviewer FOREIGN KEY(tenant_id,resolved_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT uq_sep_asset_tag UNIQUE(tenant_id,hr_case_id,asset_tag),
 CONSTRAINT ck_sep_asset_resolution CHECK ((status='OUTSTANDING' AND resolved_by IS NULL AND resolved_at IS NULL)
 OR (status IN ('RETURNED','EXCEPTION') AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL))
);
ALTER TABLE personnel_separation_asset ENABLE ROW LEVEL SECURITY;
ALTER TABLE personnel_separation_asset FORCE ROW LEVEL SECURITY;
CREATE POLICY sep_asset_tenant ON personnel_separation_asset USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
