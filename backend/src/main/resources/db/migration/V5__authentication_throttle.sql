CREATE TABLE auth_login_attempt (
 key_sha256 char(64) PRIMARY KEY,
 attempts integer NOT NULL DEFAULT 0,
 window_start timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_auth_login_attempt_updated ON auth_login_attempt(updated_at);
