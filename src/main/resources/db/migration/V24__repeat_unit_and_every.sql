-- A repeat is now "every N units" (unit DAY, WEEK, MONTH or YEAR) instead of a fixed
-- frequency name. Old items: WEEKLY = WEEK/1, BIWEEKLY = WEEK/2, MONTHLY = MONTH/1,
-- YEARLY = YEAR/1.
ALTER TABLE wallet_recurring ADD COLUMN repeat_unit VARCHAR(5);
ALTER TABLE wallet_recurring ADD COLUMN repeat_every INTEGER;
UPDATE wallet_recurring SET
    repeat_unit = CASE frequency WHEN 'WEEKLY' THEN 'WEEK' WHEN 'BIWEEKLY' THEN 'WEEK' WHEN 'MONTHLY' THEN 'MONTH'
                                 ELSE 'YEAR' END,
    repeat_every = CASE frequency WHEN 'BIWEEKLY' THEN 2 ELSE 1 END;
ALTER TABLE wallet_recurring ALTER COLUMN repeat_unit SET NOT NULL;
ALTER TABLE wallet_recurring ALTER COLUMN repeat_every SET NOT NULL;
ALTER TABLE wallet_recurring DROP COLUMN frequency;
ALTER TABLE wallet_recurring ADD CONSTRAINT wallet_recurring_repeat_ck CHECK (
    (repeat_unit = 'DAY' AND repeat_every BETWEEN 1 AND 365)
    OR (repeat_unit = 'WEEK' AND repeat_every BETWEEN 1 AND 52)
    OR (repeat_unit = 'MONTH' AND repeat_every BETWEEN 1 AND 24)
    OR (repeat_unit = 'YEAR' AND repeat_every BETWEEN 1 AND 5));

ALTER TABLE company_recurring ADD COLUMN repeat_unit VARCHAR(5);
ALTER TABLE company_recurring ADD COLUMN repeat_every INTEGER;
UPDATE company_recurring SET
    repeat_unit = CASE frequency WHEN 'WEEKLY' THEN 'WEEK' WHEN 'BIWEEKLY' THEN 'WEEK' WHEN 'MONTHLY' THEN 'MONTH'
                                 ELSE 'YEAR' END,
    repeat_every = CASE frequency WHEN 'BIWEEKLY' THEN 2 ELSE 1 END;
ALTER TABLE company_recurring ALTER COLUMN repeat_unit SET NOT NULL;
ALTER TABLE company_recurring ALTER COLUMN repeat_every SET NOT NULL;
ALTER TABLE company_recurring DROP COLUMN frequency;
ALTER TABLE company_recurring ADD CONSTRAINT company_recurring_repeat_ck CHECK (
    (repeat_unit = 'DAY' AND repeat_every BETWEEN 1 AND 365)
    OR (repeat_unit = 'WEEK' AND repeat_every BETWEEN 1 AND 52)
    OR (repeat_unit = 'MONTH' AND repeat_every BETWEEN 1 AND 24)
    OR (repeat_unit = 'YEAR' AND repeat_every BETWEEN 1 AND 5));
