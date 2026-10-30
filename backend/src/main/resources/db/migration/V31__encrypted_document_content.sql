-- Encrypted document bytes are separate from the legacy metadata-only inventory.
CREATE TABLE personnel_document_content (
 tenant_id uuid NOT NULL,
 document_id uuid NOT NULL,
 ciphertext bytea NOT NULL,
 nonce bytea NOT NULL CHECK(octet_length(nonce)=12),
 algorithm varchar(20) NOT NULL DEFAULT 'AES-256-GCM' CHECK(algorithm='AES-256-GCM'),
 stored_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,document_id),
 CONSTRAINT fk_document_content_metadata FOREIGN KEY(tenant_id,document_id)
   REFERENCES personnel_document_metadata(tenant_id,id) ON DELETE RESTRICT,
 CONSTRAINT ck_document_content_nonempty CHECK(octet_length(ciphertext)>16)
);
ALTER TABLE personnel_document_content ENABLE ROW LEVEL SECURITY;
ALTER TABLE personnel_document_content FORCE ROW LEVEL SECURITY;
CREATE POLICY document_content_tenant ON personnel_document_content
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
