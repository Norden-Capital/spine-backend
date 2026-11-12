-- Seed of the user's drawn face (DiceBear). Null = default (made from the user id).
ALTER TABLE users ADD COLUMN avatar_seed VARCHAR(64)
    CHECK (avatar_seed ~ '^[A-Za-z0-9-]{1,64}$');
