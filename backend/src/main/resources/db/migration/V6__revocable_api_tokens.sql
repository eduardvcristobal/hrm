CREATE TABLE auth_api_token (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 identity_id uuid NOT NULL REFERENCES auth_identity(id) ON DELETE CASCADE,
 token_sha256 char(64) NOT NULL UNIQUE,
 issued_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz NOT NULL,
 revoked_at timestamptz,
 last_used_at timestamptz
);
CREATE INDEX ix_api_token_identity ON auth_api_token(identity_id, issued_at DESC);
