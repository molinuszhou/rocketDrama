BEGIN;
-- v1.7.1.3: keep one operational row per observed ad callback and record the
-- reporting destinations associated with that row instead of duplicating one
-- server event per provider.
ALTER TABLE ad_mediation_events
    ADD COLUMN IF NOT EXISTS reporting_targets TEXT[] NOT NULL DEFAULT '{}';
CREATE INDEX IF NOT EXISTS idx_ad_mediation_events_reporting_targets
    ON ad_mediation_events USING GIN (reporting_targets);
COMMIT;
