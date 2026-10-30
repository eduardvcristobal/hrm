-- M1.18.3.4: two-person authorization for ciphertext purge of tombstoned records.
CREATE TABLE personnel_document_purge_request (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 document_id uuid NOT NULL,
 requested_by uuid NOT NULL,
 requested_at timestamptz NOT NULL DEFAULT now(),
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','EXECUTED')),
 executed_by uuid,
 executed_at timestamptz,
 CONSTRAINT fk_purge_document FOREIGN KEY(tenant_id,document_id)
   REFERENCES personnel_document_metadata(tenant_id,id),
 CONSTRAINT fk_purge_requester FOREIGN KEY(tenant_id,requested_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_purge_executor FOREIGN KEY(tenant_id,executed_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_purge_actors CHECK(executed_by IS NULL OR executed_by<>requested_by),
 CONSTRAINT ck_purge_state CHECK(
  (status='PENDING' AND executed_by IS NULL AND executed_at IS NULL)
  OR (status='EXECUTED' AND executed_by IS NOT NULL AND executed_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_purge_request_per_document
 ON personnel_document_purge_request(tenant_id,document_id);
ALTER TABLE personnel_document_purge_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE personnel_document_purge_request FORCE ROW LEVEL SECURITY;
CREATE POLICY purge_request_tenant ON personnel_document_purge_request
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
