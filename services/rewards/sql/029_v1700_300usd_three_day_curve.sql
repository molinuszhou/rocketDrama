BEGIN;

-- v1.7.0.0: align the direct-cash wallet economy to a US$300 lifetime ceiling.
--
-- Internal reward units map to US dollars at 10,000 units per US$1.00 in the
-- DEFAULT market. The high-engagement target path is three consecutive days,
-- 60 completed episodes per day, all available watch-task redpackets, daily
-- sign-in, and one wallet boost per day. The six-tier curve is calibrated so
-- that deterministic settlement reaches 2,900,000 units (US$290.00) rather
-- than the US$300.00 cap. This preserves room below the cap and avoids a
-- sudden hard-stop at the stated first cash-out target.
--
-- Existing ledger rows, claimed welcome rewards, and earned Crown Road levels
-- are deliberately not rewritten. These settings apply to future settlement.

UPDATE market_wallet_policy
SET welcome_bonus_gems = 600000,
    withdrawal_threshold_micros = CASE market_code
        WHEN 'BR' THEN 900000000       -- R$900.00 local policy
        WHEN 'ID' THEN 3000000000000   -- Rp3,000,000 local policy
        ELSE 300000000                 -- US$300.00
    END,
    updated_at = NOW();

UPDATE welcome_rewards
SET gems = 600000
WHERE state = 'PENDING';

-- Calibrate the first three daily check-ins used by the qualification path.
-- Later check-in days retain their existing values and continue to be subject
-- to the same lifetime damping curve.
UPDATE signin_reward_tiers
SET gems = CASE day_number
    WHEN 1 THEN 10000
    WHEN 2 THEN 20000
    WHEN 3 THEN 30000
    ELSE gems
END
WHERE day_number IN (1, 2, 3);

UPDATE reward_policy
SET episode_reward_gems = 8000,
    episode_task_max_episode = 60,
    episode_task_post18_interval = 6,

    -- Daily watch-task redpackets are fixed-value bands by milestone tier.
    -- Keeping min=max makes the qualification model deterministic and makes
    -- the customer-facing cash value easier to explain.
    episode_task_tier1_min_gems = 10000,
    episode_task_tier1_max_gems = 10000,
    episode_task_tier2_min_gems = 15000,
    episode_task_tier2_max_gems = 15000,
    episode_task_tier3_min_gems = 20000,
    episode_task_tier3_max_gems = 20000,
    episode_task_tier4_min_gems = 25000,
    episode_task_tier4_max_gems = 25000,

    -- One visible wallet acceleration per active day is part of the model.
    wallet_redpacket_min_gems = 1000,
    wallet_redpacket_max_gems = 1000,
    release_boost_daily_limit = 1,
    release_boost_cooldown_seconds = 3600,

    -- US$300 = 3,000,000 internal reward units in the DEFAULT/USD market.
    gems_earned_cap = 3000000,
    gem_damping_tier1_threshold = 2650000,
    gem_damping_tier2_threshold = 2780000,
    gem_damping_tier3_threshold = 2860000,
    gem_damping_tier4_threshold = 2920000,
    gem_damping_tier5_threshold = 2970000,
    gem_damping_tier1_multiplier = 1.0000,
    gem_damping_tier2_multiplier = 0.5000,
    gem_damping_tier3_multiplier = 0.2500,
    gem_damping_tier4_multiplier = 0.1200,
    gem_damping_tier5_multiplier = 0.0600,
    gem_damping_tier6_multiplier = 0.0100,
    updated_at = NOW()
WHERE id = 1;

INSERT INTO schema_migrations (version)
VALUES ('029_v1700_300usd_three_day_curve')
ON CONFLICT (version) DO NOTHING;

COMMIT;
