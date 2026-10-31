-- M2.9.1: enrollment/attestation evidence only, NEVER a punch source.
-- One-time opaque device credentials are stored only as SHA-256 digests.
CREATE TABLE time_ess_device (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 employee_id uuid NOT NULL,
 identity_id uuid NOT NULL,
 display_name varchar(80) NOT NULL CHECK(length(trim(display_name))>=3),
 status varchar(16) NOT NULL DEFAULT 'PENDING'
   CHECK(status IN ('PENDING','APPROVED','ACTIVE','REJECTED','REVOKED')),
 requested_at timestamptz NOT NULL DEFAULT now(),
 reviewed_by uuid,
 reviewed_at timestamptz,
 activated_at timestamptz,
 credential_sha256 char(64),
 credential_expires_at timestamptz,
 revoked_at timestamptz,
 revoked_by uuid,
 CONSTRAINT uq_time_ess_device_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT fk_time_ess_device_employee FOREIGN KEY(tenant_id,employee_id)
   REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_time_ess_device_identity FOREIGN KEY(tenant_id,identity_id)
   REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_time_ess_device_reviewer FOREIGN KEY(tenant_id,reviewed_by)
   REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_time_ess_device_revoker FOREIGN KEY(tenant_id,revoked_by)
   REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT uq_time_ess_device_credential UNIQUE(credential_sha256),
 CONSTRAINT ck_time_ess_device_lifecycle CHECK(
    (status='PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL AND credential_sha256 IS NULL)
 OR (status='REJECTED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND credential_sha256 IS NULL)
 OR (status='APPROVED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND credential_sha256 IS NULL)
 OR (status='ACTIVE' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
     AND activated_at IS NOT NULL AND credential_sha256 IS NOT NULL AND credential_expires_at IS NOT NULL)
 OR (status='REVOKED' AND revoked_by IS NOT NULL AND revoked_at IS NOT NULL)),
 CONSTRAINT ck_time_ess_device_reviewer_independent CHECK(reviewed_by IS NULL OR reviewed_by<>identity_id)
);
CREATE INDEX ix_time_ess_device_employee ON time_ess_device(tenant_id,employee_id,requested_at DESC);
CREATE TABLE time_ess_device_attestation (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 device_id uuid NOT NULL,
 nonce uuid NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_time_ess_attestation_nonce UNIQUE(tenant_id,device_id,nonce),
 CONSTRAINT fk_time_ess_attestation_device FOREIGN KEY(tenant_id,device_id)
   REFERENCES time_ess_device(tenant_id,id)
);
CREATE INDEX ix_time_ess_attestation_device ON time_ess_device_attestation(tenant_id,device_id,recorded_at DESC);
ALTER TABLE time_ess_device ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_ess_device FORCE ROW LEVEL SECURITY;
CREATE POLICY time_ess_device_tenant ON time_ess_device
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
ALTER TABLE time_ess_device_attestation ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_ess_device_attestation FORCE ROW LEVEL SECURITY;
CREATE POLICY time_ess_attestation_tenant ON time_ess_device_attestation
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
