-- Balances (company space): prepaid accounts that a service takes money from on a schedule
-- (e.g. SMS credit, toll badge). Spine guesses the balance from the last start point.
CREATE TABLE balances (
    id           UUID          PRIMARY KEY,
    name         VARCHAR(60)   NOT NULL,
    website      VARCHAR(253),
    category_id  UUID          NOT NULL REFERENCES categories (id),
    cut          NUMERIC(14,2) NOT NULL CHECK (cut > 0),
    -- DAY / WEEK / MONTH / YEAR = every N units; TIMES_A_MONTH = N times a month, spread evenly.
    repeat_unit  VARCHAR(13)   NOT NULL CHECK (repeat_unit IN ('DAY', 'WEEK', 'MONTH', 'YEAR', 'TIMES_A_MONTH')),
    repeat_every INT           NOT NULL CHECK (repeat_every > 0),
    -- First date of the cut series; all cut dates are counted from it.
    anchor_date  DATE          NOT NULL,
    low_limit    NUMERIC(14,2) CHECK (low_limit >= 0),
    warn_days    INT           NOT NULL DEFAULT 7 CHECK (warn_days BETWEEN 0 AND 365),
    note         VARCHAR(500),
    -- The start point: amount at the end of start_date (set on create, recharge and fix).
    start_amount NUMERIC(14,2) NOT NULL CHECK (start_amount >= 0),
    start_date   DATE          NOT NULL,
    -- +1 on each recharge / fix: balance alerts are sent once per start point.
    start_seq    INT           NOT NULL DEFAULT 1,
    created_by   UUID          NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ   NOT NULL,
    updated_by   UUID          REFERENCES users (id),
    updated_at   TIMESTAMPTZ   NOT NULL
);

CREATE TABLE balance_events (
    id             UUID          PRIMARY KEY,
    balance_id     UUID          NOT NULL REFERENCES balances (id) ON DELETE CASCADE,
    kind           VARCHAR(10)   NOT NULL CHECK (kind IN ('CREATED', 'RECHARGE', 'FIX')),
    event_date     DATE          NOT NULL,
    -- CREATED: the start amount; RECHARGE: the money added; FIX: the real balance typed.
    amount         NUMERIC(14,2) NOT NULL,
    -- The guess just before (RECHARGE, FIX).
    guess_before   NUMERIC(14,2),
    start_after    NUMERIC(14,2) NOT NULL,
    transaction_id UUID          REFERENCES transactions (id) ON DELETE SET NULL,
    user_id        UUID          REFERENCES users (id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ   NOT NULL
);

CREATE INDEX balance_events_balance_idx ON balance_events (balance_id, event_date, created_at);

-- Every alert says who it is for: COMPANY (sent to everyone in the company) or PRIVATE
-- (one person's wallet).
ALTER TABLE notifications ADD COLUMN scope VARCHAR(10);
UPDATE notifications SET scope = CASE WHEN kind = 'COMPANY_RECURRING_DUE' THEN 'COMPANY' ELSE 'PRIVATE' END;
ALTER TABLE notifications ALTER COLUMN scope SET NOT NULL;
ALTER TABLE notifications ADD CONSTRAINT notifications_scope_ck CHECK (scope IN ('COMPANY', 'PRIVATE'));
