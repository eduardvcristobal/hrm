CREATE TABLE personnel_onboarding_requirement (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 hr_case_id uuid NOT NULL,
 requirement_code varchar(40) NOT NULL CHECK(requirement_code ~ '^[A-Z0-9_]+$'),
 label varchar(160) NOT NULL,
 mandatory boolean NOT NULL DEFAULT true,
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','VERIFIED','REJECTED')),
 verified_by uuid,
 verified_at timestamptz,
 evidence_document_id uuid,
 CONSTRAINT fk_onboarding_case FOREIGN KEY(tenant_id,hr_case_id) REFERENCES personnel_hr_case(tenant_id,id),
 CONSTRAINT fk_onboarding_verifier FOREIGN KEY(tenant_id,verified_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_onboarding_evidence FOREIGN KEY(tenant_id,evidence_document_id) REFERENCES personnel_document_metadata(tenant_id,id),
 CONSTRAINT uq_onboarding_requirement UNIQUE(tenant_id,hr_case_id,requirement_code),
 CONSTRAINT ck_onboarding_verification CHECK (
  (status='PENDING' AND verified_by IS NULL AND verified_at IS NULL)
  OR (status IN ('VERIFIED','REJECTED') AND verified_by IS NOT NULL AND verified_at IS NOT NULL))
);
ALTER TABLE personnel_hr_case ADD CONSTRAINT uq_hr_case_tenant_id UNIQUE(tenant_id,id);
ALTER TABLE personnel_document_metadata ADD CONSTRAINT uq_document_tenant_id UNIQUE(tenant_id,id);
ALTER TABLE personnel_onboarding_requirement ENABLE ROW LEVEL SECURITY;
ALTER TABLE personnel_onboarding_requirement FORCE ROW LEVEL SECURITY;
CREATE POLICY onboarding_requirement_tenant ON personnel_onboarding_requirement
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
CREATE INDEX ix_onboarding_case ON personnel_onboarding_requirement(tenant_id,hr_case_id,status);
