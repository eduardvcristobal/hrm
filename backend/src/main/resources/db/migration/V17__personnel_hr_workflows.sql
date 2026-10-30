CREATE TABLE personnel_hr_case (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 employee_id uuid NOT NULL,
 case_type varchar(30) NOT NULL CHECK(case_type IN ('ONBOARDING','SEPARATION')),
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
 effective_date date NOT NULL,
 reason varchar(500) NOT NULL,
 requested_by uuid NOT NULL,
 decided_by uuid,
 created_at timestamptz NOT NULL DEFAULT now(),
 decided_at timestamptz,
 CONSTRAINT fk_hr_case_employee FOREIGN KEY(tenant_id,employee_id) REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_hr_case_requester FOREIGN KEY(tenant_id,requested_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_hr_case_decider FOREIGN KEY(tenant_id,decided_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_hr_case_dual_control CHECK(decided_by IS NULL OR decided_by<>requested_by),
 CONSTRAINT ck_hr_case_decision CHECK(
 (status='PENDING' AND decided_by IS NULL AND decided_at IS NULL)
 OR (status IN ('APPROVED','REJECTED','CANCELLED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_hr_case_pending ON personnel_hr_case(tenant_id,employee_id,case_type) WHERE status='PENDING';
ALTER TABLE personnel_hr_case ENABLE ROW LEVEL SECURITY;
ALTER TABLE personnel_hr_case FORCE ROW LEVEL SECURITY;
CREATE POLICY hr_case_tenant ON personnel_hr_case USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());

CREATE TABLE personnel_document_metadata (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 employee_id uuid NOT NULL,
 category varchar(40) NOT NULL CHECK(category IN ('CONTRACT','IDENTITY','CERTIFICATION','ONBOARDING','SEPARATION','OTHER')),
 filename varchar(200) NOT NULL,
 sha256 char(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
 size_bytes bigint NOT NULL CHECK(size_bytes>0 AND size_bytes<=10485760),
 expires_on date,
 version integer NOT NULL DEFAULT 1 CHECK(version>0),
 created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_document_employee FOREIGN KEY(tenant_id,employee_id) REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_document_actor FOREIGN KEY(tenant_id,created_by) REFERENCES auth_identity(tenant_id,id)
);
CREATE INDEX ix_document_employee ON personnel_document_metadata(tenant_id,employee_id,created_at DESC);
ALTER TABLE personnel_document_metadata ENABLE ROW LEVEL SECURITY;
ALTER TABLE personnel_document_metadata FORCE ROW LEVEL SECURITY;
CREATE POLICY document_tenant ON personnel_document_metadata USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
