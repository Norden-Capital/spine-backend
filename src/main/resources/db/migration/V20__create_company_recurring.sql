-- Company repeat expenses (rent, software...). REMINDERS ONLY: nothing is created
-- automatically. Each due date becomes one occurrence: DUE until someone saves the
-- transaction from the reminder (DONE, linked) or skips it (SKIPPED).
CREATE TABLE company_recurring (
    id          UUID           PRIMARY KEY,
    type        VARCHAR(10)    NOT NULL,
    amount      NUMERIC(14, 2) NOT NULL,
    currency    VARCHAR(3)     NOT NULL,
    description VARCHAR(200)   NOT NULL,
    category_id UUID           NOT NULL REFERENCES categories (id),
    website     VARCHAR(253),
    frequency   VARCHAR(10)    NOT NULL,
    start_date  DATE           NOT NULL,
    end_date    DATE,
    -- Next date to remind about; null = the series is over (after end_date).
    next_date   DATE,
    paused      BOOLEAN        NOT NULL DEFAULT FALSE,
    created_by  UUID           NOT NULL REFERENCES users (id),
    created_at  TIMESTAMPTZ    NOT NULL,
    updated_at  TIMESTAMPTZ    NOT NULL,
    CONSTRAINT company_recurring_type_ck CHECK (type IN ('EXPENSE', 'INCOME')),
    CONSTRAINT company_recurring_currency_ck CHECK (currency IN ('USD', 'MAD')),
    CONSTRAINT company_recurring_frequency_ck CHECK (frequency IN ('WEEKLY', 'MONTHLY', 'YEARLY')),
    CONSTRAINT company_recurring_amount_ck CHECK (amount > 0),
    CONSTRAINT company_recurring_dates_ck CHECK (end_date IS NULL OR end_date >= start_date)
);

CREATE INDEX company_recurring_due_idx ON company_recurring (next_date) WHERE NOT paused AND next_date IS NOT NULL;

CREATE TABLE company_recurring_occurrences (
    id             UUID        PRIMARY KEY,
    recurring_id   UUID        NOT NULL REFERENCES company_recurring (id) ON DELETE CASCADE,
    due_date       DATE        NOT NULL,
    status         VARCHAR(10) NOT NULL,
    transaction_id UUID        REFERENCES transactions (id),
    done_by        UUID        REFERENCES users (id),
    done_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT company_recurring_occ_status_ck CHECK (status IN ('DUE', 'DONE', 'SKIPPED')),
    CONSTRAINT company_recurring_occ_uk UNIQUE (recurring_id, due_date)
);

CREATE INDEX company_recurring_occ_due_idx ON company_recurring_occurrences (due_date) WHERE status = 'DUE';
CREATE UNIQUE INDEX company_recurring_occ_tx_uk ON company_recurring_occurrences (transaction_id)
    WHERE transaction_id IS NOT NULL;
