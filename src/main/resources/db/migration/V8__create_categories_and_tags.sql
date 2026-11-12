-- Expense categories (managed by OWNER/ADMIN) and free tags (anyone can add).
-- Rows are never deleted, only archived, so old expenses keep them.

CREATE TABLE categories (
    id         UUID        PRIMARY KEY,
    name       VARCHAR(40) NOT NULL,
    icon       VARCHAR(40) NOT NULL,
    archived   BOOLEAN     NOT NULL DEFAULT FALSE,
    sort_order INTEGER     NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Names are unique without caring about case ("Food" = "food").
CREATE UNIQUE INDEX categories_name_uk ON categories (lower(name));

CREATE TABLE tags (
    id         UUID        PRIMARY KEY,
    name       VARCHAR(40) NOT NULL,
    archived   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by UUID        REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX tags_name_uk ON tags (lower(name));
