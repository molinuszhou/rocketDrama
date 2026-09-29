BEGIN;

-- v1.7.0.1: operationally align the US$300 qualification curve with a
-- repeating seven-day check-in cycle and ten daily active-ad opportunities
-- for both Wallet Boost and Crown Road acceleration.
--
-- The full qualification path is modelled as: check in, complete ten Crown
-- Road accelerations, watch 60 episodes and all daily tasks, claim episode
-- rewards, then complete ten Wallet Boost ads. Existing ledger rows and
-- already-earned Crown achievements remain immutable.

UPDATE signin_reward_tiers
SET gems = CASE day_number
    WHEN 1 THEN 10000
    WHEN 2 THEN 20000
    WHEN 3 THEN 30000
    WHEN 4 THEN 40000
    WHEN 5 THEN 50000
    WHEN 6 THEN 60000
    WHEN 7 THEN 70000
    ELSE gems
END
WHERE day_number BETWEEN 1 AND 7;

UPDATE reward_policy
SET
    -- Keep the full calendar week attainable while calibrating the precise
    -- high-engagement three-day path after daily active boosts are included.
    episode_reward_gems = 7784,

    -- Ten direct-cash wallet boosts can be completed each active day.
    wallet_redpacket_min_gems = 1000,
    wallet_redpacket_max_gems = 1000,
    release_boost_daily_limit = 10,
    release_boost_cooldown_seconds = 30,

    -- Ten Crown Road rewarded ads can be completed daily. Each completed ad
    -- already adds one point through crown_rewarded_ad_progress_points.
    crown_rewarded_daily_limit = 10,
    crown_rewarded_ad_progress_points = 1,
    crown_rewarded_cooldown_seconds = 30,
    updated_at = NOW()
WHERE id = 1;

INSERT INTO schema_migrations (version)
VALUES ('030_v1701_seven_day_cycle_ten_active_boosts')
ON CONFLICT (version) DO NOTHING;

COMMIT;
