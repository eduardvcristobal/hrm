-- M2.9 security hardening: revoke identity links without deleting evidence.
-- Uniqueness intentionally remains lifetime-wide; re-linking requires a
-- separately reviewed future administrative remediation workflow.
ALTER TABLE time_employee_identity_link
 ADD COLUMN revoked_at timestamptz,
 ADD COLUMN revoked_by uuid,
 ADD CONSTRAINT fk_time_ess_revoker FOREIGN KEY(tenant_id,revoked_by)
 REFERENCES auth_identity(tenant_id,id),
 ADD CONSTRAINT ck_time_ess_revoked CHECK(
   (revoked_at IS NULL AND revoked_by IS NULL)
   OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL)
 );
CREATE INDEX ix_time_ess_active_identity
 ON time_employee_identity_link(tenant_id,identity_id) WHERE revoked_at IS NULL;
