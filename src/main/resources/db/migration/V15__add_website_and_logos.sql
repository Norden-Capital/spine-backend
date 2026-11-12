-- Optional website of a transaction, stored as a bare domain (e.g. "claude.ai").
ALTER TABLE transactions ADD COLUMN website VARCHAR(253);
ALTER TABLE wallet_transactions ADD COLUMN website VARCHAR(253);

CREATE INDEX transactions_website_idx ON transactions (website) WHERE website IS NOT NULL;
CREATE INDEX wallet_transactions_user_website_idx ON wallet_transactions (user_id, website) WHERE website IS NOT NULL;

-- One cached logo per domain, fetched by the server (never by a third-party service).
-- status OK = image stored; NONE = no usable logo found (tried again after retry_after).
CREATE TABLE logos (
    domain       VARCHAR(253) PRIMARY KEY,
    status       VARCHAR(10)  NOT NULL CHECK (status IN ('OK', 'NONE')),
    content_type VARCHAR(50),
    data         BYTEA,
    fetched_at   TIMESTAMPTZ  NOT NULL,
    retry_after  TIMESTAMPTZ  NOT NULL,
    CHECK ((status = 'OK') = (data IS NOT NULL AND content_type IS NOT NULL))
);
