-- Fail closed: production migration must not silently interpret old plaintext TOTP factors.
-- Existing factors are invalidated and must be enrolled again with encrypted storage.
DELETE FROM auth_totp_factor;
ALTER TABLE auth_totp_factor RENAME COLUMN secret_hex TO secret_ciphertext;
ALTER TABLE auth_totp_factor ALTER COLUMN secret_ciphertext TYPE varchar(512);
COMMENT ON COLUMN auth_totp_factor.secret_ciphertext IS 'AES-256-GCM v1 envelope, external key; never plaintext';
