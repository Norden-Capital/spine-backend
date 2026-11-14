-- Web Push: one row per device (browser) that turned notifications on.
-- The endpoint is unique: a device that changes user is moved to the new user.
CREATE TABLE push_subscriptions (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    endpoint    VARCHAR(2048) NOT NULL UNIQUE,
    -- The browser's keys (base64url): p256dh = its P-256 public key, auth = 16-byte secret.
    p256dh      VARCHAR(128) NOT NULL,
    auth        VARCHAR(64) NOT NULL,
    -- A short name for the device (the browser's User-Agent), or null.
    label       VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    -- Last time the push service accepted a message for it.
    last_ok_at  TIMESTAMPTZ,
    -- Failed sends in a row (not 404/410: those delete it at once). Deleted at 5.
    failures    INTEGER NOT NULL DEFAULT 0 CHECK (failures >= 0)
);

CREATE INDEX push_subscriptions_user_idx ON push_subscriptions (user_id);
