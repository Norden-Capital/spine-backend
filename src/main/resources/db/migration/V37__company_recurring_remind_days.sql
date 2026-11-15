-- Company repeat expenses: early reminders. The days before the due date when everyone gets
-- an alert (any of 7, 3, 1; empty = only on the due day). The reminder (occurrence) is made
-- on the earliest of these days, so it shows in "Due soon" early. New and existing repeats: 7 and 3.
ALTER TABLE company_recurring ADD COLUMN remind_days INTEGER[] NOT NULL DEFAULT '{7,3}';
ALTER TABLE company_recurring ADD CONSTRAINT company_recurring_remind_days_check
    CHECK (remind_days <@ ARRAY[7, 3, 1] AND array_position(remind_days, NULL) IS NULL);
