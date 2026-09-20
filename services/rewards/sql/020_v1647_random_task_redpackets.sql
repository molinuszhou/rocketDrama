BEGIN;

-- v1.6.4.7: Replace legacy random chests with ad-gated, random cash redpacks
-- on the watch-episode task line. Internal units remain the damping/audit basis.
ALTER TABLE reward_policy
    ADD COLUMN IF NOT EXISTS episode_task_max_episode INTEGER NOT NULL DEFAULT 90
        CHECK (episode_task_max_episode >= 18 AND episode_task_max_episode <= 10000),
    ADD COLUMN IF NOT EXISTS episode_task_post18_interval INTEGER NOT NULL DEFAULT 6
        CHECK (episode_task_post18_interval >= 1 AND episode_task_post18_interval <= 1000),
    ADD COLUMN IF NOT EXISTS episode_task_redpacket_min_gems BIGINT NOT NULL DEFAULT 4000
        CHECK (episode_task_redpacket_min_gems >= 0),
    ADD COLUMN IF NOT EXISTS episode_task_redpacket_max_gems BIGINT NOT NULL DEFAULT 8000
        CHECK (episode_task_redpacket_max_gems >= 0),
    ADD COLUMN IF NOT EXISTS wallet_redpacket_min_gems BIGINT NOT NULL DEFAULT 800
        CHECK (wallet_redpacket_min_gems >= 0),
    ADD COLUMN IF NOT EXISTS wallet_redpacket_max_gems BIGINT NOT NULL DEFAULT 1600
        CHECK (wallet_redpacket_max_gems >= 0);

-- Fresh-build defaults. The service materialises the dynamic node list at bootstrap
-- and when a user watches an episode; this table remains an auditable milestone index.
UPDATE reward_policy
SET episodes_per_rewarded_unlock = 2,
    episode_task_max_episode = 90,
    episode_task_post18_interval = 6,
    episode_task_redpacket_min_gems = 4000,
    episode_task_redpacket_max_gems = 8000,
    wallet_redpacket_min_gems = 800,
    wallet_redpacket_max_gems = 1600,
    daily_random_chest_count = 0,
    -- Retained solely to satisfy the legacy CHECK constraint; chest generation is disabled in code.
    episodes_per_random_chest = 1,
    updated_at = NOW()
WHERE id = 1;

-- The task-redpacket claim is an independently auditable rewarded placement.
ALTER TABLE rewarded_ad_events DROP CONSTRAINT IF EXISTS rewarded_ad_events_placement_id_check;
ALTER TABLE rewarded_ad_events
    ADD CONSTRAINT rewarded_ad_events_placement_id_check
    CHECK (placement_id IN (
        'rv_episode_pass', 'rv_claim_reward', 'rv_double_reward', 'rv_episode_reward_double',
        'rv_chest_task', 'rv_signin_double', 'rv_release_boost',
        'rv_random_chest_open', 'rv_episode_reward_boost',
        'rv_welcome_bonus', 'rv_checkin_complete', 'rv_episode_rewards_claim',
        'rv_crown_upgrade', 'rv_episode_task_claim'
    ));

-- The old chest inventory is intentionally disabled. It is no longer populated,
-- surfaced in snapshots, or accepted as a rewarded placement by the v1.6.4.7 client.
DELETE FROM random_chest_inventory;

INSERT INTO schema_migrations (version)
VALUES ('020_v1647_random_task_redpackets')
ON CONFLICT (version) DO NOTHING;

COMMIT;
