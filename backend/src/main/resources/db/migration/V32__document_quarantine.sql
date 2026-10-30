ALTER TABLE personnel_document_metadata
 ADD COLUMN review_status varchar(20) NOT NULL DEFAULT 'QUARANTINED',
 ADD COLUMN reviewed_by uuid,
 ADD COLUMN reviewed_at timestamptz,
 ADD COLUMN retain_until date,
 ADD CONSTRAINT ck_document_review_status CHECK(review_status IN ('QUARANTINED','REJECTED','RELEASED')),
 ADD CONSTRAINT fk_document_reviewer FOREIGN KEY(tenant_id,reviewed_by) REFERENCES auth_identity(tenant_id,id),
 ADD CONSTRAINT ck_document_review_state CHECK (
 (review_status='QUARANTINED' AND reviewed_by IS NULL AND reviewed_at IS NULL)
 OR (review_status IN ('REJECTED','RELEASED') AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL));
-- Existing metadata-only entries remain QUARANTINED; no automatic release.
CREATE INDEX ix_document_review ON personnel_document_metadata(tenant_id,employee_id,review_status);
