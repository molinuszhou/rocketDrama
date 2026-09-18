BEGIN;

-- v1.6.3.5: 30-day check-in total = 200,000 Gems and five-tier damping.
-- Existing settled ledger entries are not rewritten; only future settlements use these values.
ALTER TABLE reward_policy
    ADD COLUMN IF NOT EXISTS gem_damping_tier4_threshold BIGINT NOT NULL DEFAULT 1980000
        CHECK (gem_damping_tier4_threshold >= 0),
    ADD COLUMN IF NOT EXISTS gem_damping_tier5_multiplier NUMERIC(6,4) NOT NULL DEFAULT 0.0100
        CHECK (gem_damping_tier5_multiplier >= 0 AND gem_damping_tier5_multiplier <= 1);

DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version = '017_v1635_checkin_200k_five_tier_damping') THEN
        UPDATE reward_policy
        SET gem_damping_tier1_threshold = 1900000,
            gem_damping_tier2_threshold = 1950000,
            gem_damping_tier3_threshold = 1980000,
            gem_damping_tier4_threshold = 1990000,
            gem_damping_tier1_multiplier = 1.0000,
            gem_damping_tier2_multiplier = 0.5000,
            gem_damping_tier3_multiplier = 0.4000,
            gem_damping_tier4_multiplier = 0.2000,
            gem_damping_tier5_multiplier = 0.0100,
            updated_at = NOW()
        WHERE id = 1;

        -- 30-day schedule totals exactly 200,000 Gems.
        UPDATE signin_reward_tiers
        SET gems = CASE day_number
            WHEN 1 THEN 3000
            WHEN 2 THEN 6000
            WHEN 3 THEN 8000
            WHEN 4 THEN 12000
            WHEN 5 THEN 15000
            WHEN 6 THEN 25000
            WHEN 7 THEN 50000
            WHEN 8 THEN 3000
            WHEN 9 THEN 3000
            WHEN 10 THEN 3000
            WHEN 11 THEN 3000
            WHEN 12 THEN 3000
            WHEN 13 THEN 3000
            WHEN 14 THEN 30000
            WHEN 15 THEN 1000
            WHEN 16 THEN 1000
            WHEN 17 THEN 1000
            WHEN 18 THEN 1000
            WHEN 19 THEN 1000
            WHEN 20 THEN 1000
            WHEN 21 THEN 1000
            WHEN 22 THEN 1000
            WHEN 23 THEN 1000
            WHEN 24 THEN 1000
            WHEN 25 THEN 1000
            WHEN 26 THEN 1000
            WHEN 27 THEN 1000
            WHEN 28 THEN 1000
            WHEN 29 THEN 1000
            WHEN 30 THEN 18000
        END;
        INSERT INTO schema_migrations (version) VALUES ('017_v1635_checkin_200k_five_tier_damping');
    END IF;
END
$migration$;
COMMIT;
