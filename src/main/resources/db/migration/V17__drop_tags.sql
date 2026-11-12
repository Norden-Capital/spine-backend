-- Tags are removed (category + subcategory is enough). Old history rows that mention
-- "tags" stay as they are (they are plain JSON text, still readable).
DROP TABLE wallet_transaction_tags;
DROP TABLE wallet_tags;
DROP TABLE transaction_tags;
DROP TABLE tags;
