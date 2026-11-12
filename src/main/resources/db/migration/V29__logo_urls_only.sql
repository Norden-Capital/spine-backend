-- Logos are now loaded by the browser straight from the brand's site (servers are often
-- blocked, phones are not). The server only keeps the icon URL each site declares.
DELETE FROM logos;
ALTER TABLE logos DROP COLUMN data;
ALTER TABLE logos DROP COLUMN content_type;
-- Best icon declared in the site's home page (link rel icon / apple-touch-icon / manifest), or null.
ALTER TABLE logos ADD COLUMN icon_url VARCHAR(2048);
