ALTER TABLE auth_identity ADD COLUMN username varchar(32);

ALTER TABLE auth_identity
    ADD CONSTRAINT ck_auth_identity_username
    CHECK (username IS NULL OR username ~ '^[a-z0-9._-]{3,32}$');

CREATE UNIQUE INDEX uq_auth_identity_username ON auth_identity(username) WHERE username IS NOT NULL;
