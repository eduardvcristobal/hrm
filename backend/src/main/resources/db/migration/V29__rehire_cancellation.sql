ALTER TABLE personnel_rehire_request
 DROP CONSTRAINT ck_rehire_status,
 DROP CONSTRAINT ck_rehire_approval_state;
ALTER TABLE personnel_rehire_request
 ADD COLUMN cancelled_by uuid,
 ADD COLUMN cancelled_at timestamptz,
 ADD CONSTRAINT fk_rehire_canceller FOREIGN KEY(tenant_id,cancelled_by) REFERENCES auth_identity(tenant_id,id),
 ADD CONSTRAINT ck_rehire_status CHECK(status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
 ADD CONSTRAINT ck_rehire_approval_state CHECK (
 (status='PENDING' AND approved_by IS NULL AND approved_at IS NULL AND cancelled_by IS NULL AND cancelled_at IS NULL)
 OR (status IN ('APPROVED','REJECTED') AND approved_by IS NOT NULL AND approved_at IS NOT NULL AND cancelled_by IS NULL AND cancelled_at IS NULL)
 OR (status='CANCELLED' AND approved_by IS NULL AND approved_at IS NULL AND cancelled_by=requested_by AND cancelled_at IS NOT NULL)
 );
