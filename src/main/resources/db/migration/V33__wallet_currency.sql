-- The PRIVATE wallet gets two currencies again: USD and MAD (the company stays USD only).
-- Each wallet item has its own currency. Each person has a default wallet currency and an
-- own rate (1 USD = wallet_usd_rate MAD); wallet totals are converted into the default
-- currency when read (never stored). Existing rows stay USD.

ALTER TABLE user_preferences
    ADD COLUMN wallet_currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    ADD COLUMN wallet_usd_rate NUMERIC(12, 4) NOT NULL DEFAULT 10,
    ADD CONSTRAINT user_preferences_wallet_currency_ck CHECK (wallet_currency IN ('USD', 'MAD')),
    ADD CONSTRAINT user_preferences_wallet_usd_rate_ck CHECK (wallet_usd_rate > 0);

ALTER TABLE wallet_transactions
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    ADD CONSTRAINT wallet_transactions_currency_ck CHECK (currency IN ('USD', 'MAD'));

ALTER TABLE wallet_goals
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    ADD CONSTRAINT wallet_goals_currency_ck CHECK (currency IN ('USD', 'MAD'));

ALTER TABLE wallet_budgets
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    ADD CONSTRAINT wallet_budgets_currency_ck CHECK (currency IN ('USD', 'MAD'));

ALTER TABLE wallet_recurring
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    ADD CONSTRAINT wallet_recurring_currency_ck CHECK (currency IN ('USD', 'MAD'));
