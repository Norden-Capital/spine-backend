-- The 7 starting categories. "Other" has a high sort_order so it always stays last;
-- new categories are added before it.
INSERT INTO categories (id, name, icon, archived, sort_order, created_at, updated_at) VALUES
    (gen_random_uuid(), 'Travel',    'plane',     FALSE, 10,   now(), now()),
    (gen_random_uuid(), 'Food',      'utensils',  FALSE, 20,   now(), now()),
    (gen_random_uuid(), 'Transport', 'car',       FALSE, 30,   now(), now()),
    (gen_random_uuid(), 'Software',  'laptop',    FALSE, 40,   now(), now()),
    (gen_random_uuid(), 'Office',    'briefcase', FALSE, 50,   now(), now()),
    (gen_random_uuid(), 'Bills',     'receipt',   FALSE, 60,   now(), now()),
    (gen_random_uuid(), 'Other',     'ellipsis',  FALSE, 1000, now(), now());
