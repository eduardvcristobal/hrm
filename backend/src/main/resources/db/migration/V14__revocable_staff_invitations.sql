ALTER TABLE auth_invitation ADD COLUMN revoked_at timestamptz;
CREATE INDEX ix_auth_invitation_pending ON auth_invitation(tenant_id,expires_at)
    WHERE used_at IS NULL AND revoked_at IS NULL;
