-- Balances: a trash (soft delete, like company repeat expenses). Everyone in the company
-- adds, edits, recharges, fixes and deletes. Deleted = in the trash: hidden from the list,
-- the dashboard card, the job and the alerts. Only the OWNER sees the trash and restores.
ALTER TABLE balances ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE balances ADD COLUMN deleted_by UUID REFERENCES users (id);

CREATE INDEX balances_deleted_at_idx ON balances (deleted_at);

-- The history gets DELETED and RESTORED lines (who and when). They are not a start point:
-- no amount, no start after (guess_before = the guess at that moment).
ALTER TABLE balance_events DROP CONSTRAINT balance_events_kind_check;
ALTER TABLE balance_events ADD CONSTRAINT balance_events_kind_check
    CHECK (kind IN ('CREATED', 'RECHARGE', 'FIX', 'DELETED', 'RESTORED'));
ALTER TABLE balance_events ALTER COLUMN amount DROP NOT NULL;
ALTER TABLE balance_events ALTER COLUMN start_after DROP NOT NULL;
