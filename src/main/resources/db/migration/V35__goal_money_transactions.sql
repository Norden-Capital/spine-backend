-- Goal money becomes wallet transactions (PRIVATE wallet only; the company side does not change).
-- Adding money to a goal makes an EXPENSE in the person's "Goals" category, taking it out makes an
-- INCOME in the "From goals" category. The two categories are found by system_key (not by name),
-- so a rename by the person never makes a second one.

ALTER TABLE wallet_categories
    ADD COLUMN system_key VARCHAR(20),
    ADD CONSTRAINT wallet_categories_system_key_ck CHECK (system_key IN ('GOALS_EXPENSE', 'GOALS_INCOME'));

CREATE UNIQUE INDEX wallet_categories_system_key_uk ON wallet_categories (user_id, system_key)
    WHERE system_key IS NOT NULL;

-- Each goal movement has its own transaction. No ON DELETE CASCADE: the app removes the
-- movement and its transaction together (undo, goal delete, transaction delete).
ALTER TABLE wallet_goal_contributions
    ADD COLUMN transaction_id UUID REFERENCES wallet_transactions (id),
    ADD CONSTRAINT wallet_goal_contributions_transaction_uk UNIQUE (transaction_id);
