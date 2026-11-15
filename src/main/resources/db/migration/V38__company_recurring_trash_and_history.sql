-- Company repeat expenses: a trash (soft delete, like transactions) and a history.
-- Deleted = in the trash: hidden from the list, "Due soon" and the job. Only the OWNER
-- sees the trash and restores.
ALTER TABLE company_recurring ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE company_recurring ADD COLUMN deleted_by UUID REFERENCES users (id);

CREATE INDEX company_recurring_deleted_at_idx ON company_recurring (deleted_at);

DROP INDEX company_recurring_due_idx;
CREATE INDEX company_recurring_due_idx ON company_recurring (next_date)
    WHERE NOT paused AND next_date IS NOT NULL AND deleted_at IS NULL;

-- Who changed what, and when (same shape as transaction_history).
-- changes = [{"field": ..., "from": ..., "to": ...}]
CREATE TABLE company_recurring_history (
    id           UUID        PRIMARY KEY,
    recurring_id UUID        NOT NULL REFERENCES company_recurring (id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES users (id),
    action       VARCHAR(20) NOT NULL,
    changes      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT company_recurring_history_action_ck CHECK (action IN ('CREATED', 'UPDATED', 'DELETED', 'RESTORED',
        'PAUSED', 'RESUMED', 'PAUSED_UNTIL', 'SKIPPED', 'UNSKIPPED'))
);

CREATE INDEX company_recurring_history_idx ON company_recurring_history (recurring_id, created_at);

-- One CREATED entry for each existing repeat: its current values, by its creator, at its
-- created_at. Same field names and texts as the app writes (CompanyRecurringHistory).
INSERT INTO company_recurring_history (id, recurring_id, user_id, action, changes, created_at)
SELECT gen_random_uuid(), r.id, r.created_by, 'CREATED',
       (SELECT coalesce(jsonb_agg(jsonb_build_object('field', v.field, 'from', NULL, 'to', v.value) ORDER BY v.n),
                        '[]'::jsonb)
        FROM (VALUES
                  (1, 'type', r.type),
                  (2, 'amount', r.amount::text),
                  (3, 'description', nullif(r.description, '')),
                  (4, 'category', c.name),
                  (5, 'website', nullif(r.website, '')),
                  (6, 'repeat', CASE
                      WHEN r.repeat_every = 1 THEN
                          CASE r.repeat_unit WHEN 'DAY' THEN 'Daily' WHEN 'WEEK' THEN 'Weekly'
                                             WHEN 'MONTH' THEN 'Monthly' ELSE 'Yearly' END
                      ELSE 'Every ' || r.repeat_every || ' ' || lower(r.repeat_unit) || 's' END),
                  (7, 'startDate', to_char(r.start_date, 'YYYY-MM-DD')),
                  (8, 'endDate', to_char(r.end_date, 'YYYY-MM-DD')),
                  (9, 'remindDays', CASE
                      WHEN cardinality(r.remind_days) = 0 THEN 'Due day only'
                      ELSE (SELECT string_agg(d::text, ', ' ORDER BY d DESC) FROM unnest(r.remind_days) d)
                           || CASE WHEN r.remind_days = '{1}' THEN ' day before' ELSE ' days before' END END)
             ) AS v(n, field, value)
        WHERE v.value IS NOT NULL),
       r.created_at
FROM company_recurring r
LEFT JOIN categories c ON c.id = r.category_id;
