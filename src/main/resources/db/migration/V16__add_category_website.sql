-- Optional website of a category (bare domain), used for its logo.
ALTER TABLE categories ADD COLUMN website VARCHAR(253);
ALTER TABLE wallet_categories ADD COLUMN website VARCHAR(253);

CREATE INDEX categories_website_idx ON categories (website) WHERE website IS NOT NULL;
CREATE INDEX wallet_categories_user_website_idx ON wallet_categories (user_id, website) WHERE website IS NOT NULL;
