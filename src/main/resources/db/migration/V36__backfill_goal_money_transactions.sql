-- Backfill: every goal movement without a transaction gets one, so each wallet balance stays
-- exactly the same (before: income - expense - money in goals; now: income - expense).
-- An add (amount > 0) becomes an EXPENSE in "Goals", a take out (amount < 0) an INCOME in
-- "From goals", in the goal's currency, on the movement's date. Safe to run again (only rows
-- with no transaction yet). Category names are unique per person (any kind, any case): when
-- the name is taken, "Goals 2", "Goals 3", ... are tried.

INSERT INTO wallet_categories (id, user_id, name, icon, archived, position, kind, system_key, created_at, updated_at)
SELECT gen_random_uuid(), n.user_id,
       coalesce((SELECT cand.name
                 FROM (VALUES (1, 'Goals'), (2, 'Goals 2'), (3, 'Goals 3'), (4, 'Goals 4'), (5, 'Goals 5'))
                          AS cand (i, name)
                 WHERE NOT EXISTS (SELECT 1 FROM wallet_categories x
                                   WHERE x.user_id = n.user_id AND lower(x.name) = lower(cand.name))
                 ORDER BY cand.i LIMIT 1),
                'Goals ' || substr(md5(random()::text), 1, 6)),
       'coins', FALSE,
       (SELECT coalesce(max(x.position) + 1, 0) FROM wallet_categories x
        WHERE x.user_id = n.user_id AND x.parent_id IS NULL),
       'EXPENSE', 'GOALS_EXPENSE', now(), now()
FROM (SELECT DISTINCT g.user_id
      FROM wallet_goal_contributions c JOIN wallet_goals g ON g.id = c.goal_id
      WHERE c.transaction_id IS NULL AND c.amount > 0) n
WHERE NOT EXISTS (SELECT 1 FROM wallet_categories x WHERE x.user_id = n.user_id AND x.system_key = 'GOALS_EXPENSE');

INSERT INTO wallet_categories (id, user_id, name, icon, archived, position, kind, system_key, created_at, updated_at)
SELECT gen_random_uuid(), n.user_id,
       coalesce((SELECT cand.name
                 FROM (VALUES (1, 'From goals'), (2, 'From goals 2'), (3, 'From goals 3'), (4, 'From goals 4'),
                              (5, 'From goals 5')) AS cand (i, name)
                 WHERE NOT EXISTS (SELECT 1 FROM wallet_categories x
                                   WHERE x.user_id = n.user_id AND lower(x.name) = lower(cand.name))
                 ORDER BY cand.i LIMIT 1),
                'From goals ' || substr(md5(random()::text), 1, 6)),
       'coins', FALSE,
       (SELECT coalesce(max(x.position) + 1, 0) FROM wallet_categories x
        WHERE x.user_id = n.user_id AND x.parent_id IS NULL),
       'INCOME', 'GOALS_INCOME', now(), now()
FROM (SELECT DISTINCT g.user_id
      FROM wallet_goal_contributions c JOIN wallet_goals g ON g.id = c.goal_id
      WHERE c.transaction_id IS NULL AND c.amount < 0) n
WHERE NOT EXISTS (SELECT 1 FROM wallet_categories x WHERE x.user_id = n.user_id AND x.system_key = 'GOALS_INCOME');

WITH src AS (
    SELECT c.id AS contribution_id, gen_random_uuid() AS transaction_id, g.user_id,
           CASE WHEN c.amount > 0 THEN 'EXPENSE' ELSE 'INCOME' END AS type,
           abs(c.amount) AS amount, g.currency, c.date, left('Goal: ' || g.name, 200) AS description,
           c.created_at
    FROM wallet_goal_contributions c JOIN wallet_goals g ON g.id = c.goal_id
    WHERE c.transaction_id IS NULL
),
made AS (
    INSERT INTO wallet_transactions (id, user_id, type, amount, currency, date, description, category_id, created_at)
    SELECT s.transaction_id, s.user_id, s.type, s.amount, s.currency, s.date, s.description,
           (SELECT x.id FROM wallet_categories x
            WHERE x.user_id = s.user_id
              AND x.system_key = CASE WHEN s.type = 'EXPENSE' THEN 'GOALS_EXPENSE' ELSE 'GOALS_INCOME' END),
           s.created_at
    FROM src s
    RETURNING id
)
UPDATE wallet_goal_contributions c SET transaction_id = s.transaction_id
FROM src s
WHERE c.id = s.contribution_id;
