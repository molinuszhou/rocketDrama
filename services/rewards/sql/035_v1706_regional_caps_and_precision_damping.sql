BEGIN;

-- v1.7.0.6: preserve the US$300 / 3,000,000 internal-unit lifetime ceiling,
-- apply the requested six-band damping curve, and align the two localized
-- markets to their explicit local-currency caps.
--
-- The sixth requested multiplier is 0.00001. Earlier NUMERIC(6,4) columns
-- cannot store that value without rounding it to zero, so all multiplier
-- columns are widened to a five-decimal scale before the policy is updated.
-- Existing ledger entries remain immutable; this policy affects future reward
-- settlements only.

ALTER TABLE reward_policy
    ALTER COLUMN gem_damping_tier1_multiplier TYPE NUMERIC(8,5) USING gem_damping_tier1_multiplier::NUMERIC(8,5),
    ALTER COLUMN gem_damping_tier2_multiplier TYPE NUMERIC(8,5) USING gem_damping_tier2_multiplier::NUMERIC(8,5),
    ALTER COLUMN gem_damping_tier3_multiplier TYPE NUMERIC(8,5) USING gem_damping_tier3_multiplier::NUMERIC(8,5),
    ALTER COLUMN gem_damping_tier4_multiplier TYPE NUMERIC(8,5) USING gem_damping_tier4_multiplier::NUMERIC(8,5),
    ALTER COLUMN gem_damping_tier5_multiplier TYPE NUMERIC(8,5) USING gem_damping_tier5_multiplier::NUMERIC(8,5),
    ALTER COLUMN gem_damping_tier6_multiplier TYPE NUMERIC(8,5) USING gem_damping_tier6_multiplier::NUMERIC(8,5);

UPDATE reward_policy
SET
    gems_earned_cap = 3000000,
    gem_damping_tier1_threshold = 2900000,
    gem_damping_tier2_threshold = 2950000,
    gem_damping_tier3_threshold = 2975000,
    gem_damping_tier4_threshold = 2990000,
    gem_damping_tier5_threshold = 2998000,
    gem_damping_tier1_multiplier = 1.00000,
    gem_damping_tier2_multiplier = 0.20000,
    gem_damping_tier3_multiplier = 0.04000,
    gem_damping_tier4_multiplier = 0.00100,
    gem_damping_tier5_multiplier = 0.00010,
    gem_damping_tier6_multiplier = 0.00001,
    updated_at = NOW()
WHERE id = 1;

-- BR: 10,000 internal units = R$5.00, so 3,000,000 units = R$1,500.00.
-- ID: 10,000 internal units = Rp20,000, so 3,000,000 units = Rp6,000,000.
-- USD reference remains US$1.00 per 10,000 internal units for both markets.
UPDATE market_wallet_policy
SET
    currency_micros_per_10000_gems = CASE market_code
        WHEN 'BR' THEN 5000000       -- R$5.00 in micros
        WHEN 'ID' THEN 20000000000   -- Rp20,000 in micros
        ELSE currency_micros_per_10000_gems
    END,
    withdrawal_threshold_micros = CASE market_code
        WHEN 'BR' THEN 1500000000       -- R$1,500.00 in micros
        WHEN 'ID' THEN 6000000000000    -- Rp6,000,000 in micros
        ELSE withdrawal_threshold_micros
    END,
    updated_at = NOW()
WHERE market_code IN ('BR', 'ID');

INSERT INTO schema_migrations (version)
VALUES ('035_v1706_regional_caps_and_precision_damping')
ON CONFLICT (version) DO NOTHING;

COMMIT;
