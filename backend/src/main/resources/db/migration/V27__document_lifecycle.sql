-- Fail-closed retention and legal hold. Deletion is soft/tombstoned until a certified purge.
ALTER TABLE personnel_document_metadata
 ADD COLUMN legal_hold boolean NOT NULL DEFAULT false,
 ADD COLUMN legal_hold_reason varchar(500),
 ADD COLUMN deleted_at timestamptz,
 ADD COLUMN deleted_by uuid,
 ADD COLUMN document_family_id uuid,
 ADD CONSTRAINT fk_document_deleter FOREIGN KEY(tenant_id,deleted_by) REFERENCES auth_identity(tenant_id,id),
 ADD CONSTRAINT ck_document_legal_hold CHECK
  ((legal_hold AND legal_hold_reason IS NOT NULL AND length(trim(legal_hold_reason))>0)
   OR (NOT legal_hold AND legal_hold_reason IS NULL)),
 ADD CONSTRAINT ck_document_delete_state CHECK
  ((deleted_at IS NULL AND deleted_by IS NULL) OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL)),
 ADD CONSTRAINT ck_document_hold_delete CHECK(NOT (legal_hold AND deleted_at IS NOT NULL));
UPDATE personnel_document_metadata SET document_family_id=id WHERE document_family_id IS NULL;
ALTER TABLE personnel_document_metadata ALTER COLUMN document_family_id SET NOT NULL;
ALTER TABLE personnel_document_metadata ALTER COLUMN document_family_id SET DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX uq_document_family_version
 ON personnel_document_metadata(tenant_id,employee_id,document_family_id,version);
CREATE INDEX ix_document_retention
 ON personnel_document_metadata(tenant_id,retain_until) WHERE deleted_at IS NULL;
