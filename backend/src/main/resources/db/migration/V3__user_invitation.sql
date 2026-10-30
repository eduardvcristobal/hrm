CREATE TABLE auth_invitation (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 email varchar(254) NOT NULL,
 role varchar(40) NOT NULL,
 token_sha256 char(64) NOT NULL UNIQUE,
 expires_at timestamptz NOT NULL,
 used_at timestamptz,
 created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT ck_invitation_role CHECK (role IN ('HR_ADMIN','PAYROLL_OFFICER','MANAGER','EMPLOYEE','AUDITOR'))
);
CREATE INDEX ix_invitation_tenant ON auth_invitation(tenant_id,created_at DESC);
-- Tokens are global, unguessable capabilities; queries must be by SHA-256 only.
-- No tenant API may list token hashes.
