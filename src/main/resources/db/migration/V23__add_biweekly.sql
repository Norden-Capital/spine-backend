-- Repeat items can also come every 2 weeks.
ALTER TABLE wallet_recurring DROP CONSTRAINT wallet_recurring_frequency_ck;
ALTER TABLE wallet_recurring ADD CONSTRAINT wallet_recurring_frequency_ck
    CHECK (frequency IN ('WEEKLY', 'BIWEEKLY', 'MONTHLY', 'YEARLY'));
ALTER TABLE company_recurring DROP CONSTRAINT company_recurring_frequency_ck;
ALTER TABLE company_recurring ADD CONSTRAINT company_recurring_frequency_ck
    CHECK (frequency IN ('WEEKLY', 'BIWEEKLY', 'MONTHLY', 'YEARLY'));
