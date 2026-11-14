-- Manual order of wallet goals (drag and drop). 0-based per user. Existing goals: oldest first.
ALTER TABLE wallet_goals ADD COLUMN position INTEGER NOT NULL DEFAULT 0;
UPDATE wallet_goals g SET position = r.rn
FROM (SELECT id, row_number() OVER (PARTITION BY user_id ORDER BY created_at, id) - 1 AS rn FROM wallet_goals) r
WHERE g.id = r.id;
