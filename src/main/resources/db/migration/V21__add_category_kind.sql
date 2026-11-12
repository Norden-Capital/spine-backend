-- Categories are split by kind: EXPENSE or INCOME. A subcategory always has the kind of
-- its main category. Existing categories are expenses, except the wallet starting
-- category "Salary", which is income.
ALTER TABLE categories ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'EXPENSE'
    CONSTRAINT categories_kind_ck CHECK (kind IN ('EXPENSE', 'INCOME'));
ALTER TABLE wallet_categories ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'EXPENSE'
    CONSTRAINT wallet_categories_kind_ck CHECK (kind IN ('EXPENSE', 'INCOME'));

UPDATE wallet_categories SET kind = 'INCOME' WHERE parent_id IS NULL AND lower(name) = 'salary';
UPDATE wallet_categories c SET kind = p.kind FROM wallet_categories p WHERE c.parent_id = p.id;

-- Starting income categories (skipped when the name is already used), after the main ones.
INSERT INTO categories (id, name, icon, archived, position, kind, created_at, updated_at)
SELECT gen_random_uuid(), s.name, s.icon, FALSE,
       (SELECT coalesce(max(position), -1) FROM categories WHERE parent_id IS NULL) + s.n, 'INCOME', now(), now()
FROM (VALUES ('Client payment', 'credit-card', 1), ('Salary', 'banknote', 2), ('Refund', 'receipt', 3),
             ('Investment', 'landmark', 4), ('Other income', 'ellipsis', 5)) AS s (name, icon, n)
WHERE NOT EXISTS (SELECT 1 FROM categories c WHERE lower(c.name) = lower(s.name));

INSERT INTO wallet_categories (id, user_id, name, icon, archived, position, kind, created_at, updated_at)
SELECT gen_random_uuid(), u.user_id, s.name, s.icon, FALSE, u.last + s.n, 'INCOME', now(), now()
FROM (SELECT user_id, max(position) AS last FROM wallet_categories WHERE parent_id IS NULL GROUP BY user_id) u
CROSS JOIN (VALUES ('Client payment', 'credit-card', 1), ('Salary', 'banknote', 2), ('Refund', 'receipt', 3),
                   ('Investment', 'landmark', 4), ('Other income', 'ellipsis', 5)) AS s (name, icon, n)
WHERE NOT EXISTS (SELECT 1 FROM wallet_categories c WHERE c.user_id = u.user_id AND lower(c.name) = lower(s.name));
