-- Passkeys (WebAuthn): an extra way to log in. The password stays for recovery.
CREATE TABLE passkeys (
    id            UUID         PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    credential_id BYTEA        NOT NULL,
    public_key    BYTEA        NOT NULL,  -- COSE key
    sign_count    BIGINT       NOT NULL,
    transports    VARCHAR(200),           -- comma-separated, e.g. "internal,hybrid"
    name          VARCHAR(60)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    last_used_at  TIMESTAMPTZ,
    CONSTRAINT passkeys_credential_id_uk UNIQUE (credential_id)
);

CREATE INDEX passkeys_user_idx ON passkeys (user_id);
