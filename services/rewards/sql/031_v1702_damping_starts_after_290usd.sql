BEGIN;

-- v1.7.0.2: settle every reward source at 100% through the US$290
-- qualification milestone. The next reward after 2,900,000 internal units
-- begins the six-tier convergence toward the US$300 lifetime hard cap.
--
-- The high-engagement model remains: seven-day recurring check-in, 60
-- completed episodes per day, ten Crown Road active ads and ten Wallet Boost
-- ads per day. Existing ledger rows and already settled rewards are immutable.

UPDATE reward_policy
SET
    -- Recalibrated with no attenuation before US$290.00 on the qualification
    -- path. The event-by-event model reaches 2,899,980 units, displayed as
    -- US$290.00 at two decimal places.
    episode_reward_gems = 5093,

    -- The first real attenuation is 0.50x immediately after US$290.00.
    -- tier1 retains 1.00x below the threshold by design; the settlement
    -- function selects a multiplier from the pre-settlement cumulative value.
    gems_earned_cap = 3000000,
    gem_damping_tier1_threshold = 2900000,
    gem_damping_tier2_threshold = 2950000,
    gem_damping_tier3_threshold = 2975000,
    gem_damping_tier4_threshold = 2990000,
    gem_damping_tier5_threshold = 2998000,
    gem_damping_tier1_multiplier = 1.0000,
    gem_damping_tier2_multiplier = 0.5000,
    gem_damping_tier3_multiplier = 0.2500,
    gem_damping_tier4_multiplier = 0.1200,
    gem_damping_tier5_multiplier = 0.0600,
    gem_damping_tier6_multiplier = 0.0100,
    updated_at = NOW()
WHERE id = 1;

INSERT INTO schema_migrations (version)
VALUES ('031_v1702_damping_starts_after_290usd')
ON CONFLICT (version) DO NOTHING;

COMMIT;
