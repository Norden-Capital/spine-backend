-- In-app alerts, per user. dedupe_key makes an alert appear only once
-- (for example once per budget per month).
CREATE TABLE notifications (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind       VARCHAR(30)  NOT NULL,
    title      VARCHAR(120) NOT NULL,
    body       VARCHAR(300) NOT NULL,
    link       VARCHAR(200),
    dedupe_key VARCHAR(200) NOT NULL,
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT notifications_dedupe_uk UNIQUE (user_id, dedupe_key)
);

CREATE INDEX notifications_user_idx ON notifications (user_id, created_at);
