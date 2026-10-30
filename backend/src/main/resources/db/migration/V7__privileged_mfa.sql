CREATE TABLE auth_totp_factor (
 identity_id uuid PRIMARY KEY REFERENCES auth_identity(id) ON DELETE CASCADE,
 secret_base32 varchar(128) NOT NULL,
 enabled boolean NOT NULL DEFAULT false,
 last_counter bigint NOT NULL DEFAULT -1,
 created_at timestamptz NOT NULL DEFAULT now()
);
