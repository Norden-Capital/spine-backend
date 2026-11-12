-- Subcategories: one level only (checked in code: a parent has no parent).
ALTER TABLE categories ADD COLUMN parent_id UUID REFERENCES categories (id);
CREATE INDEX categories_parent_idx ON categories (parent_id);

-- Comments on company transactions (not part of the history).
CREATE TABLE transaction_comments (
    id             UUID         PRIMARY KEY,
    transaction_id UUID         NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    user_id        UUID         NOT NULL REFERENCES users (id),
    text           VARCHAR(500) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX transaction_comments_tx_idx ON transaction_comments (transaction_id, created_at);
