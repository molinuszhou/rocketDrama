BEGIN;

-- v1.7.0.5: rebalance the rounded two-hour / three-day US$290 qualification
-- curve after raising the user-visible recurring rewards. All active reward
-- amounts remain multiples of 500 internal units.
--
-- Qualification path: one welcome reward, three daily check-ins, 80 completed
-- episodes/day, all 14 daily watch-task claims, 10 Crown Road accelerations
-- and 10 wallet boosts/day for three consecutive active days. With the normal
-- event-by-event settlement order and no damping before US$290, this settles
-- at exactly 2,900,000 units (US$290.00 in the DEFAULT/USD market).
--
-- This migration supersedes the recurring values in migration 033. Historical
-- ledger entries and already-generated claims remain immutable. The 600,000
-- unit welcome reward deliberately remains unchanged.

UPDATE reward_policy
SET
    -- US$0.50 base cash per completed episode before Crown multipliers.
    episode_reward_gems = 5000,

    -- Fixed tier values preserve predictable task rewards and three-day target.
    episode_task_tier1_min_gems = 5000,
    episode_task_tier1_max_gems = 5000,
    episode_task_tier2_min_gems = 5000,
    episode_task_tier2_max_gems = 5000,
    episode_task_tier3_min_gems = 6000,
    episode_task_tier3_max_gems = 6000,
    episode_task_tier4_min_gems = 6000,
    episode_task_tier4_max_gems = 6000,

    -- Ten daily wallet boosts remain available; each is US$0.55 before
    -- regional conversion and lifetime damping.
    wallet_redpacket_min_gems = 5500,
    wallet_redpacket_max_gems = 5500,
    updated_at = NOW()
WHERE id = 1;

-- A 1.20x Silver multiplier balances the retained welcome reward while
-- keeping every user-facing cash value in 500-unit increments.
UPDATE achievement_policy
SET episode_reward_multiplier = 1.20
WHERE code = 'SILVER';

INSERT INTO schema_migrations (version)
VALUES ('034_v1705_high_episode_wallet_rewards')
ON CONFLICT (version) DO NOTHING;

COMMIT;
