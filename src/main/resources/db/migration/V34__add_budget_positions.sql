-- Manual order of wallet budgets (drag and drop). 0-based per user. Existing budgets: oldest first.
ALTER TABLE wallet_budgets ADD COLUMN position INTEGER NOT NULL DEFAULT 0;
UPDATE wallet_budgets b SET position = r.rn
FROM (SELECT id, row_number() OVER (PARTITION BY user_id ORDER BY created_at, id) - 1 AS rn FROM wallet_budgets) r
WHERE b.id = r.id;
