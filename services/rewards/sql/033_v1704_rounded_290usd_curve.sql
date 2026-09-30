BEGIN;

-- v1.7.0.4: keep the established high-engagement three-day US$290.00
-- qualification path, while making every user-facing reward amount a clean
-- multiple of 500 internal units (in practice, all values below are whole
-- thousands except the 500-unit task tiers where explicitly configured).
--
-- Qualification path: one welcome reward, daily check-in, 80 completed
-- episodes/day, all 14 daily watch-task claims, 10 Crown Road accelerations
-- and 10 wallet boosts/day for three consecutive active days. With the normal
-- event-by-event settlement order and no damping before US$290, it settles at
-- exactly 2,900,000 units (US$290.00 in the DEFAULT/USD market).
--
-- Existing ledger entries, already-earned Crown achievements and already
-- generated episode claims remain immutable. These settings apply to future
-- settlement and future achievement awards only.

UPDATE reward_policy
SET
    -- Base episode cash reward. Crown multipliers continue to apply normally.
    episode_reward_gems = 2000,

    -- Daily watch-task redpackets: fixed values by visible milestone tier.
    -- min=max intentionally keeps the qualification curve deterministic.
    episode_task_tier1_min_gems = 10000,
    episode_task_tier1_max_gems = 10000,
    episode_task_tier2_min_gems = 18500,
    episode_task_tier2_max_gems = 18500,
    episode_task_tier3_min_gems = 27000,
    episode_task_tier3_max_gems = 27000,
    episode_task_tier4_min_gems = 40500,
    episode_task_tier4_max_gems = 40500,

    -- Ten daily wallet boosts remain available; each is US$0.20 before
    -- regional conversion and lifetime damping.
    wallet_redpacket_min_gems = 2000,
    wallet_redpacket_max_gems = 2000,
    updated_at = NOW()
WHERE id = 1;

-- The Gold milestone is the final balancing component of the rounded curve.
-- Requirements and all episode multipliers are intentionally unchanged.
UPDATE achievement_policy
SET reward_gems = 67000
WHERE code = 'GOLD';

INSERT INTO schema_migrations (version)
VALUES ('033_v1704_rounded_290usd_curve')
ON CONFLICT (version) DO NOTHING;

COMMIT;
