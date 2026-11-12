-- Private wallet: strictly per user. No admin access to any of these tables.

-- PIN (Argon2 hash) and the wrong-PIN lock.
CREATE TABLE wallet_settings (
    user_id         UUID         PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    pin_hash        VARCHAR(255) NOT NULL,
    failed_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE TABLE wallet_categories (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    parent_id  UUID        REFERENCES wallet_categories (id),
    name       VARCHAR(40) NOT NULL,
    icon       VARCHAR(40) NOT NULL,
    archived   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX wallet_categories_name_uk ON wallet_categories (user_id, lower(name));
CREATE INDEX wallet_categories_parent_idx ON wallet_categories (parent_id);

CREATE TABLE wallet_tags (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name       VARCHAR(40) NOT NULL,
    archived   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX wallet_tags_name_uk ON wallet_tags (user_id, lower(name));

CREATE TABLE wallet_recurring (
    id          UUID           PRIMARY KEY,
    user_id     UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        VARCHAR(60)    NOT NULL,
    type        VARCHAR(10)    NOT NULL,
    amount      NUMERIC(14, 2) NOT NULL,
    currency    VARCHAR(3)     NOT NULL,
    category_id UUID           NOT NULL REFERENCES wallet_categories (id),
    description VARCHAR(200)   NOT NULL,
    frequency   VARCHAR(10)    NOT NULL,
    start_date  DATE           NOT NULL,
    next_date   DATE           NOT NULL,
    active      BOOLEAN        NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL,
    updated_at  TIMESTAMPTZ    NOT NULL,
    CONSTRAINT wallet_recurring_type_ck CHECK (type IN ('EXPENSE', 'INCOME')),
    CONSTRAINT wallet_recurring_currency_ck CHECK (currency IN ('USD', 'MAD')),
    CONSTRAINT wallet_recurring_frequency_ck CHECK (frequency IN ('WEEKLY', 'MONTHLY', 'YEARLY')),
    CONSTRAINT wallet_recurring_amount_ck CHECK (amount > 0)
);

CREATE INDEX wallet_recurring_due_idx ON wallet_recurring (next_date) WHERE active;

CREATE TABLE wallet_transactions (
    id              UUID           PRIMARY KEY,
    user_id         UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type            VARCHAR(10)    NOT NULL,
    amount          NUMERIC(14, 2) NOT NULL,
    currency        VARCHAR(3)     NOT NULL,
    date            DATE           NOT NULL,
    description     VARCHAR(200)   NOT NULL,
    category_id     UUID           NOT NULL REFERENCES wallet_categories (id),
    recurring_id    UUID           REFERENCES wallet_recurring (id) ON DELETE SET NULL,
    recurring_date  DATE,          -- the occurrence a recurring item made (never twice)
    created_at      TIMESTAMPTZ    NOT NULL,
    updated_at      TIMESTAMPTZ,
    CONSTRAINT wallet_transactions_type_ck CHECK (type IN ('EXPENSE', 'INCOME')),
    CONSTRAINT wallet_transactions_currency_ck CHECK (currency IN ('USD', 'MAD')),
    CONSTRAINT wallet_transactions_amount_ck CHECK (amount > 0)
);

CREATE INDEX wallet_transactions_user_date_idx ON wallet_transactions (user_id, date);
CREATE UNIQUE INDEX wallet_transactions_occurrence_uk ON wallet_transactions (recurring_id, recurring_date)
    WHERE recurring_id IS NOT NULL;

CREATE TABLE wallet_transaction_tags (
    transaction_id UUID NOT NULL REFERENCES wallet_transactions (id) ON DELETE CASCADE,
    tag_id         UUID NOT NULL REFERENCES wallet_tags (id),
    PRIMARY KEY (transaction_id, tag_id)
);

-- Files can belong to a company transaction or to a wallet transaction (never both).
ALTER TABLE files ADD COLUMN wallet_transaction_id UUID REFERENCES wallet_transactions (id) ON DELETE SET NULL;
ALTER TABLE files ADD CONSTRAINT files_one_owner_ck CHECK (transaction_id IS NULL OR wallet_transaction_id IS NULL);
CREATE INDEX files_wallet_transaction_idx ON files (wallet_transaction_id);
DROP INDEX files_unattached_idx;
CREATE INDEX files_unattached_idx ON files (created_at) WHERE transaction_id IS NULL AND wallet_transaction_id IS NULL;

-- Monthly budgets: a total per currency (category_id NULL) or one per category.
CREATE TABLE wallet_budgets (
    id          UUID           PRIMARY KEY,
    user_id     UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    currency    VARCHAR(3)     NOT NULL,
    amount      NUMERIC(14, 2) NOT NULL,
    category_id UUID           REFERENCES wallet_categories (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ    NOT NULL,
    updated_at  TIMESTAMPTZ    NOT NULL,
    CONSTRAINT wallet_budgets_currency_ck CHECK (currency IN ('USD', 'MAD')),
    CONSTRAINT wallet_budgets_amount_ck CHECK (amount > 0)
);

CREATE UNIQUE INDEX wallet_budgets_uk ON wallet_budgets (user_id, currency, category_id) NULLS NOT DISTINCT;

CREATE TABLE wallet_goals (
    id         UUID           PRIMARY KEY,
    user_id    UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name       VARCHAR(60)    NOT NULL,
    target     NUMERIC(14, 2) NOT NULL,
    currency   VARCHAR(3)     NOT NULL,
    deadline   DATE,
    created_at TIMESTAMPTZ    NOT NULL,
    updated_at TIMESTAMPTZ    NOT NULL,
    CONSTRAINT wallet_goals_currency_ck CHECK (currency IN ('USD', 'MAD')),
    CONSTRAINT wallet_goals_target_ck CHECK (target > 0)
);

CREATE TABLE wallet_goal_contributions (
    id         UUID           PRIMARY KEY,
    goal_id    UUID           NOT NULL REFERENCES wallet_goals (id) ON DELETE CASCADE,
    amount     NUMERIC(14, 2) NOT NULL,
    date       DATE           NOT NULL,
    note       VARCHAR(200),
    created_at TIMESTAMPTZ    NOT NULL,
    CONSTRAINT wallet_goal_contributions_amount_ck CHECK (amount > 0)
);

CREATE INDEX wallet_goal_contributions_goal_idx ON wallet_goal_contributions (goal_id);
