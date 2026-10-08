BEGIN;

-- v1.7.1.0: retain only the ISO country code inferred from the request IP.
-- The raw IP address is never stored.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS geo_country CHAR(2);

CREATE INDEX IF NOT EXISTS idx_users_geo_country
    ON users (geo_country)
    WHERE geo_country IS NOT NULL AND geo_country <> '';

COMMIT;
