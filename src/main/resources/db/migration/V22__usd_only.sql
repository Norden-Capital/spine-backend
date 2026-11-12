-- The app is USD only. MAD rows were test data (user decision): they are deleted with
-- everything that depends on them, then the currency columns are dropped.

-- Ids of everything that goes, to clean alerts that point to them (link or dedupe key).
CREATE TEMPORARY TABLE usd_only_gone (id UUID PRIMARY KEY) ON COMMIT DROP;
INSERT INTO usd_only_gone SELECT id FROM transactions WHERE currency = 'MAD';
INSERT INTO usd_only_gone SELECT id FROM wallet_transactions WHERE currency = 'MAD';
INSERT INTO usd_only_gone SELECT id FROM wallet_budgets WHERE currency = 'MAD';
INSERT INTO usd_only_gone SELECT id FROM wallet_goals WHERE currency = 'MAD';
INSERT INTO usd_only_gone SELECT id FROM wallet_recurring WHERE currency = 'MAD';
INSERT INTO usd_only_gone SELECT id FROM company_recurring WHERE currency = 'MAD';
INSERT INTO usd_only_gone
SELECT o.id FROM company_recurring_occurrences o JOIN company_recurring r ON r.id = o.recurring_id
WHERE r.currency = 'MAD';

DELETE FROM notifications n USING usd_only_gone g
WHERE n.link LIKE '%' || g.id::text || '%' OR n.dedupe_key LIKE '%' || g.id::text || '%';

-- Proof files of deleted transactions become "unattached": the hourly cleanup job then
-- deletes their rows and their files on disk (it only takes uploads older than 24 hours).
UPDATE files SET transaction_id = NULL
WHERE transaction_id IN (SELECT id FROM transactions WHERE currency = 'MAD');
UPDATE company_recurring_occurrences SET transaction_id = NULL
WHERE transaction_id IN (SELECT id FROM transactions WHERE currency = 'MAD');

-- Comments and history go with their transaction (ON DELETE CASCADE).
DELETE FROM transactions WHERE currency = 'MAD';
-- Reminders go with their template (CASCADE).
DELETE FROM company_recurring WHERE currency = 'MAD';
-- files.wallet_transaction_id becomes NULL (SET NULL): cleaned like above.
DELETE FROM wallet_transactions WHERE currency = 'MAD';
-- wallet_transactions.recurring_id of kept rows becomes NULL (SET NULL).
DELETE FROM wallet_recurring WHERE currency = 'MAD';
DELETE FROM wallet_budgets WHERE currency = 'MAD';
-- Contributions go with their goal (CASCADE).
DELETE FROM wallet_goals WHERE currency = 'MAD';

-- Dropping a column also drops its CHECK constraints and indexes.
ALTER TABLE transactions DROP COLUMN currency;
ALTER TABLE wallet_transactions DROP COLUMN currency;
ALTER TABLE wallet_recurring DROP COLUMN currency;
ALTER TABLE wallet_goals DROP COLUMN currency;
ALTER TABLE company_recurring DROP COLUMN currency;
ALTER TABLE wallet_budgets DROP COLUMN currency;
-- One budget per category (or one total budget) per user.
CREATE UNIQUE INDEX wallet_budgets_uk ON wallet_budgets (user_id, category_id) NULLS NOT DISTINCT;
