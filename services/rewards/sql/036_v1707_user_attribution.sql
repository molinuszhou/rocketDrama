BEGIN;

-- v1.7.0.7: retain a minimal, operational AppsFlyer install-attribution record
-- with the anonymous Rewards user. Raw conversion data, click IDs and site IDs
-- are intentionally not stored.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS appsflyer_uid TEXT,
    ADD COLUMN IF NOT EXISTS attribution_status TEXT,
    ADD COLUMN IF NOT EXISTS attribution_media_source TEXT,
    ADD COLUMN IF NOT EXISTS attribution_campaign TEXT,
    ADD COLUMN IF NOT EXISTS attribution_channel TEXT,
    ADD COLUMN IF NOT EXISTS attribution_surface TEXT,
    ADD COLUMN IF NOT EXISTS attribution_first_recorded_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS attribution_updated_at TIMESTAMPTZ;

ALTER TABLE users
    DROP CONSTRAINT IF EXISTS chk_users_attribution_surface;
ALTER TABLE users
    ADD CONSTRAINT chk_users_attribution_surface
    CHECK (attribution_surface IS NULL OR attribution_surface IN ('A', 'B'));

CREATE INDEX IF NOT EXISTS idx_users_attribution_surface
    ON users (attribution_surface, attribution_media_source)
    WHERE attribution_surface IS NOT NULL;

COMMIT;
