BEGIN;

-- v1.7.0.9: retain the app's anonymous Rewards UID for operational reconciliation.
-- This is the same pseudonymous UUID shown in Settings and used as the Rewards user_id;
-- AppsFlyer UID remains in the separate attribution record.
ALTER TABLE reporting_delivery_events
    ADD COLUMN IF NOT EXISTS user_uid TEXT NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_reporting_delivery_events_user_uid
    ON reporting_delivery_events (user_uid, client_occurred_at DESC);

COMMIT;
