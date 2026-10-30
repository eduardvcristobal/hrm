-- Do not destroy existing factors during an encryption migration.
-- If plaintext rows exist, require a separately audited, offline re-encryption migration.
DO $block$
BEGIN
 IF EXISTS (SELECT 1 FROM auth_totp_factor) THEN
  RAISE EXCEPTION 'MFA factor migration requires approved offline re-encryption; no factors were removed';
 END IF;
END $block$;
ALTER TABLE auth_totp_factor RENAME COLUMN secret_hex TO secret_ciphertext;
ALTER TABLE auth_totp_factor ALTER COLUMN secret_ciphertext TYPE varchar(512);
COMMENT ON COLUMN auth_totp_factor.secret_ciphertext IS 'AES-256-GCM v1 ciphertext; external managed key';
