-- Each month keeps its own budget limit. A row = the limit set in that month (month = its first
-- day). The limit of a month M = the row with the latest month <= M, or the earliest row when M
-- is before all rows. Existing budgets: one row for the current month.
CREATE TABLE wallet_budget_limits (
    budget_id UUID           NOT NULL REFERENCES wallet_budgets (id) ON DELETE CASCADE,
    month     DATE           NOT NULL,
    amount    NUMERIC(14, 2) NOT NULL,
    currency  VARCHAR(3)     NOT NULL,
    PRIMARY KEY (budget_id, month),
    CONSTRAINT wallet_budget_limits_currency_ck CHECK (currency IN ('USD', 'MAD')),
    CONSTRAINT wallet_budget_limits_amount_ck CHECK (amount > 0)
);

INSERT INTO wallet_budget_limits (budget_id, month, amount, currency)
SELECT id, date_trunc('month', now())::date, amount, currency FROM wallet_budgets;
