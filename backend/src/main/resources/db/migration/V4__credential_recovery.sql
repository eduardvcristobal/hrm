CREATE TABLE auth_password_reset (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 identity_id uuid NOT NULL REFERENCES auth_identity(id) ON DELETE CASCADE,
 token_sha256 char(64) NOT NULL UNIQUE,
 expires_at timestamptz NOT NULL,
 used_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_password_reset_identity ON auth_password_reset(identity_id,created_at DESC);
