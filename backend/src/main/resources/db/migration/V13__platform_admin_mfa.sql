ALTER TABLE auth_platform_admin_token
  ADD COLUMN mfa_verified boolean NOT NULL DEFAULT false;

CREATE TABLE auth_platform_admin_totp_factor (
  admin_id uuid PRIMARY KEY REFERENCES auth_platform_admin(id) ON DELETE CASCADE,
  secret_ciphertext varchar(512) NOT NULL,
  enabled boolean NOT NULL DEFAULT false,
  last_counter bigint NOT NULL DEFAULT -1,
  created_at timestamptz NOT NULL DEFAULT now()
);
