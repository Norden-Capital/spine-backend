-- One row per user, created on the first save. No row = defaults.
-- time_zone NULL = automatic (the device's time zone).
CREATE TABLE user_preferences (
    user_id     UUID        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    date_format VARCHAR(10) NOT NULL,
    time_zone   VARCHAR(64),
    theme       VARCHAR(10) NOT NULL,
    font        VARCHAR(10) NOT NULL,
    text_size   VARCHAR(10) NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT user_preferences_date_format_ck CHECK (date_format IN ('DD/MM/YYYY', 'MM/DD/YYYY', 'YYYY-MM-DD')),
    CONSTRAINT user_preferences_theme_ck CHECK (theme IN ('LIGHT', 'DARK', 'SYSTEM')),
    CONSTRAINT user_preferences_font_ck CHECK (font IN ('GEIST', 'INTER', 'MANROPE', 'SYSTEM')),
    CONSTRAINT user_preferences_text_size_ck CHECK (text_size IN ('SMALL', 'NORMAL', 'LARGE'))
);
