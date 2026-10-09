BEGIN;

-- v1.7.1.7: an idempotency key belongs to a user, not the entire platform.
ALTER TABLE ledger_entries
    DROP CONSTRAINT IF EXISTS ledger_entries_reference_type_reference_id_key;

ALTER TABLE ledger_entries
    ADD CONSTRAINT ledger_entries_user_reference_type_reference_id_key
    UNIQUE (user_id, reference_type, reference_id);

COMMIT;
