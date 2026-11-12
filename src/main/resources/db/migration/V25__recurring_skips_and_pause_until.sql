-- Repeat items: skip single dates of the series, and pause until a date.
CREATE TABLE wallet_recurring_skips (
    recurring_id UUID        NOT NULL REFERENCES wallet_recurring (id) ON DELETE CASCADE,
    skip_date    DATE        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (recurring_id, skip_date)
);

CREATE TABLE company_recurring_skips (
    recurring_id UUID        NOT NULL REFERENCES company_recurring (id) ON DELETE CASCADE,
    skip_date    DATE        NOT NULL,
    created_by   UUID        NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (recurring_id, skip_date)
);

-- Paused until this date (dates before it are not made / reminded); null = not paused
-- until a date. The series goes on by itself from this date.
ALTER TABLE wallet_recurring ADD COLUMN paused_until DATE;
ALTER TABLE company_recurring ADD COLUMN paused_until DATE;
