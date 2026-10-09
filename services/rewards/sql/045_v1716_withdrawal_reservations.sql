BEGIN;

-- v1.7.1.6: an accepted withdrawal immediately reserves wallet balance and is idempotent.
ALTER TABLE withdrawal_requests
    ADD COLUMN IF NOT EXISTS client_request_id TEXT,
    ADD COLUMN IF NOT EXISTS currency_code CHAR(3),
    ADD COLUMN IF NOT EXISTS currency_fraction_digits SMALLINT,
    ADD COLUMN IF NOT EXISTS market_code TEXT,
    ADD COLUMN IF NOT EXISTS reserved_at TIMESTAMPTZ;

-- Historical rows predate client idempotency. New requests must always set this field.
CREATE UNIQUE INDEX IF NOT EXISTS uq_withdrawal_requests_user_client_request
    ON withdrawal_requests (user_id, client_request_id)
    WHERE client_request_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_withdrawal_requests_user_active
    ON withdrawal_requests (user_id, created_at DESC)
    WHERE status IN ('PENDING_REVIEW', 'PROCESSING');

COMMIT;
