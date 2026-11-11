-- One row per login attempt of a known user (unknown emails are not recorded).
CREATE TABLE login_events (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    success    BOOLEAN     NOT NULL,
    ip         VARCHAR(45)
);

CREATE INDEX login_events_user_created_idx ON login_events (user_id, created_at);
