BEGIN;

-- v1.6.4.8: Six-tier, all-channel reward damping.
-- Internal reward units are only used for settlement/audit; credited value remains direct cash.
ALTER TABLE reward_policy
    ADD COLUMN IF NOT EXISTS gem_damping_tier5_threshold BIGINT NOT NULL DEFAULT 1995000
        CHECK (gem_damping_tier5_threshold >= 0),
    ADD COLUMN IF NOT EXISTS gem_damping_tier6_multiplier NUMERIC(6,4) NOT NULL DEFAULT 0.0100
        CHECK (gem_damping_tier6_multiplier >= 0 AND gem_damping_tier6_multiplier <= 1);

-- Fresh-build and runtime-upgrade defaults. The fifth band softens the final
-- approach to the cap; the sixth band preserves the existing 0.01x final damping.
UPDATE reward_policy
SET gem_damping_tier1_threshold = 1900000,
    gem_damping_tier2_threshold = 1950000,
    gem_damping_tier3_threshold = 1980000,
    gem_damping_tier4_threshold = 1990000,
    gem_damping_tier5_threshold = 1995000,
    gem_damping_tier1_multiplier = 1.0000,
    gem_damping_tier2_multiplier = 0.5000,
    gem_damping_tier3_multiplier = 0.4000,
    gem_damping_tier4_multiplier = 0.2000,
    gem_damping_tier5_multiplier = 0.1000,
    gem_damping_tier6_multiplier = 0.0100,
    updated_at = NOW()
WHERE id = 1;

INSERT INTO schema_migrations (version)
VALUES ('021_v1648_six_tier_damping')
ON CONFLICT (version) DO NOTHING;

COMMIT;
