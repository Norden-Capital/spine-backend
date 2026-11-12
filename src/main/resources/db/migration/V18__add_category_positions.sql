-- Manual order of categories (drag and drop). Position is 0-based among siblings
-- (same parent; same user for the wallet). Existing rows: by name.
ALTER TABLE categories RENAME COLUMN sort_order TO position;
UPDATE categories c SET position = r.rn
FROM (SELECT id, row_number() OVER (PARTITION BY parent_id ORDER BY lower(name), id) - 1 AS rn FROM categories) r
WHERE c.id = r.id;

ALTER TABLE wallet_categories ADD COLUMN position INTEGER NOT NULL DEFAULT 0;
UPDATE wallet_categories c SET position = r.rn
FROM (SELECT id, row_number() OVER (PARTITION BY user_id, parent_id ORDER BY lower(name), id) - 1 AS rn
      FROM wallet_categories) r
WHERE c.id = r.id;
