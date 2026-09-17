BEGIN;
-- v1.6.3.6: Crown Road uses one understandable metric: effective rounds.
-- watched episode = 1 round; two Crown Road rewarded ads = 1 round.
-- Sign-in days and a global rewarded-ad count are no longer requirements.
UPDATE achievement_policy
SET required_signin_days = 0,
    required_rv_count = 0
WHERE code IN ('BRONZE', 'SILVER', 'GOLD', 'DIAMOND', 'CROWN');

CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO schema_migrations (version)
VALUES ('016_crown_road_effective_rounds')
ON CONFLICT (version) DO NOTHING;
COMMIT;
