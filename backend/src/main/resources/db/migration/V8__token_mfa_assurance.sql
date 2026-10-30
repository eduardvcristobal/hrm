ALTER TABLE auth_api_token
    ADD COLUMN mfa_verified boolean NOT NULL DEFAULT false;
