-- Company transactions (shared by all users), their tags, proof files and change history.

CREATE TABLE transactions (
    id          UUID           PRIMARY KEY,
    type        VARCHAR(10)    NOT NULL,
    amount      NUMERIC(14, 2) NOT NULL,
    currency    VARCHAR(3)     NOT NULL,
    date        DATE           NOT NULL,
    description VARCHAR(200)   NOT NULL,
    category_id UUID           NOT NULL REFERENCES categories (id),
    created_by  UUID           NOT NULL REFERENCES users (id),
    created_at  TIMESTAMPTZ    NOT NULL,
    updated_by  UUID           REFERENCES users (id),
    updated_at  TIMESTAMPTZ,
    deleted_at  TIMESTAMPTZ,    -- set = in the trash (never hard deleted)
    deleted_by  UUID           REFERENCES users (id),
    CONSTRAINT transactions_type_ck CHECK (type IN ('EXPENSE', 'INCOME')),
    CONSTRAINT transactions_currency_ck CHECK (currency IN ('USD', 'MAD')),
    CONSTRAINT transactions_amount_ck CHECK (amount > 0)
);

CREATE INDEX transactions_date_idx ON transactions (date);
CREATE INDEX transactions_deleted_at_idx ON transactions (deleted_at);

CREATE TABLE transaction_tags (
    transaction_id UUID NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    tag_id         UUID NOT NULL REFERENCES tags (id),
    PRIMARY KEY (transaction_id, tag_id)
);

-- Uploaded proof files. Uploaded first (transaction_id NULL), then attached.
CREATE TABLE files (
    id             UUID         PRIMARY KEY,
    owner_id       UUID         NOT NULL REFERENCES users (id),
    original_name  VARCHAR(255) NOT NULL,
    content_type   VARCHAR(100) NOT NULL,
    size           BIGINT       NOT NULL,
    storage_key    VARCHAR(64)  NOT NULL,   -- random file name on disk
    created_at     TIMESTAMPTZ  NOT NULL,
    transaction_id UUID         REFERENCES transactions (id),
    CONSTRAINT files_storage_key_uk UNIQUE (storage_key)
);

CREATE INDEX files_transaction_idx ON files (transaction_id);
CREATE INDEX files_unattached_idx ON files (created_at) WHERE transaction_id IS NULL;

-- Who changed what, and when. changes = [{"field": ..., "from": ..., "to": ...}]
CREATE TABLE transaction_history (
    id             UUID        PRIMARY KEY,
    transaction_id UUID        NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    user_id        UUID        NOT NULL REFERENCES users (id),
    action         VARCHAR(10) NOT NULL,
    changes        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT transaction_history_action_ck CHECK (action IN ('CREATED', 'UPDATED', 'DELETED', 'RESTORED'))
);

CREATE INDEX transaction_history_tx_idx ON transaction_history (transaction_id, created_at);
