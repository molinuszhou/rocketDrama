BEGIN;

-- v6：随机宝箱替代十集加奖；每集可预先观看一次激励广告开启翻倍；
-- 同时增加剧中、拖拽和领取宝石插屏的独立运营策略。
CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE reward_policy
    ADD COLUMN IF NOT EXISTS random_chest_min_gems BIGINT NOT NULL DEFAULT 300
        CHECK (random_chest_min_gems >= 0),
    ADD COLUMN IF NOT EXISTS random_chest_max_gems BIGINT NOT NULL DEFAULT 1200
        CHECK (random_chest_max_gems >= 0),
    ADD COLUMN IF NOT EXISTS daily_random_chest_count INTEGER NOT NULL DEFAULT 1
        CHECK (daily_random_chest_count BETWEEN 0 AND 10),
    ADD COLUMN IF NOT EXISTS episodes_per_random_chest INTEGER NOT NULL DEFAULT 3
        CHECK (episodes_per_random_chest BETWEEN 1 AND 100),
    ADD COLUMN IF NOT EXISTS reward_claim_interstitial_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS mid_episode_interstitial_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS mid_episode_interstitial_trigger_seconds INTEGER NOT NULL DEFAULT 5
        CHECK (mid_episode_interstitial_trigger_seconds >= 0),
    ADD COLUMN IF NOT EXISTS mid_episode_interstitial_hourly_limit INTEGER NOT NULL DEFAULT 48
        CHECK (mid_episode_interstitial_hourly_limit BETWEEN 0 AND 120),
    ADD COLUMN IF NOT EXISTS seek_interstitial_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS seek_interstitial_hourly_limit INTEGER NOT NULL DEFAULT 48
        CHECK (seek_interstitial_hourly_limit BETWEEN 0 AND 120);

ALTER TABLE achievement_policy
    ADD COLUMN IF NOT EXISTS episode_reward_multiplier NUMERIC(6,2) NOT NULL DEFAULT 1.00
        CHECK (episode_reward_multiplier >= 1.00 AND episode_reward_multiplier <= 10.00);

CREATE TABLE IF NOT EXISTS random_chest_inventory (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    source_kind TEXT NOT NULL CHECK (source_kind IN ('DAILY', 'WATCH')),
    source_key TEXT NOT NULL,
    business_date DATE NOT NULL,
    state TEXT NOT NULL DEFAULT 'AVAILABLE' CHECK (state IN ('AVAILABLE', 'OPENED')),
    gems BIGINT CHECK (gems IS NULL OR gems >= 0),
    opened_ad_event_id TEXT UNIQUE REFERENCES rewarded_ad_events(ad_event_id),
    opened_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, source_kind, source_key)
);
CREATE INDEX IF NOT EXISTS idx_random_chest_inventory_user_state
    ON random_chest_inventory(user_id, state, id);

CREATE TABLE IF NOT EXISTS episode_reward_boosts (
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    episode_id TEXT NOT NULL,
    ad_event_id TEXT UNIQUE REFERENCES rewarded_ad_events(ad_event_id),
    boosted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, episode_id)
);

DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version = '005_random_chest_and_reward_v6') THEN
        UPDATE reward_policy
        SET free_episodes_count = 3,
            random_chest_min_gems = 300,
            random_chest_max_gems = 1200,
            daily_random_chest_count = 1,
            episodes_per_random_chest = 3,
            reward_claim_interstitial_enabled = TRUE,
            mid_episode_interstitial_enabled = TRUE,
            mid_episode_interstitial_trigger_seconds = 5,
            mid_episode_interstitial_hourly_limit = 48,
            seek_interstitial_enabled = TRUE,
            seek_interstitial_hourly_limit = 48,
            -- 下一集改为用户确认后才请求广告；不再额外赠送后一集解锁。
            next_episode_interstitial_enabled = TRUE,
            extra_episode_unlock_enabled = FALSE,
            updated_at = NOW()
        WHERE id = 1;

        -- 降低原 v4 皇冠门槛，并将头像等级同时映射为每集宝石奖励倍率。
        INSERT INTO achievement_policy
            (code, window_hours, required_rounds, required_signin_days, required_rv_count, reward_gems, virtual_entitlement, level_rank, episode_reward_multiplier)
        VALUES
            ('BRONZE', 876000, 30, 3, 3, 5000, 'bronze_crown_avatar', 1, 1.10),
            ('SILVER', 876000, 100, 10, 10, 15000, 'silver_crown_avatar', 2, 1.25),
            ('GOLD', 876000, 300, 25, 25, 40000, 'gold_crown_avatar', 3, 1.50),
            ('DIAMOND', 876000, 700, 60, 60, 100000, 'diamond_crown_avatar', 4, 1.75),
            ('CROWN', 876000, 1500, 120, 130, 250000, 'crown_avatar', 5, 2.00)
        ON CONFLICT (code) DO UPDATE SET
            window_hours = EXCLUDED.window_hours,
            required_rounds = EXCLUDED.required_rounds,
            required_signin_days = EXCLUDED.required_signin_days,
            required_rv_count = EXCLUDED.required_rv_count,
            reward_gems = EXCLUDED.reward_gems,
            virtual_entitlement = EXCLUDED.virtual_entitlement,
            level_rank = EXCLUDED.level_rank,
            episode_reward_multiplier = EXCLUDED.episode_reward_multiplier;

        INSERT INTO schema_migrations (version) VALUES ('005_random_chest_and_reward_v6');
    END IF;
END
$migration$;

ALTER TABLE rewarded_ad_events DROP CONSTRAINT IF EXISTS rewarded_ad_events_placement_id_check;
ALTER TABLE rewarded_ad_events
    ADD CONSTRAINT rewarded_ad_events_placement_id_check
    CHECK (placement_id IN (
        'rv_episode_pass', 'rv_claim_reward', 'rv_double_reward', 'rv_episode_reward_double',
        'rv_chest_task', 'rv_signin_double', 'rv_release_boost',
        'rv_random_chest_open', 'rv_episode_reward_boost'
    ));

ALTER TABLE interstitial_events DROP CONSTRAINT IF EXISTS interstitial_events_placement_id_check;
ALTER TABLE interstitial_events
    ADD CONSTRAINT interstitial_events_placement_id_check
    CHECK (placement_id IN (
        'int_timer', 'int_pause', 'int_next_episode_unlock', 'int_pause_native',
        'int_reward_claim', 'int_episode_midroll', 'int_seek'
    ));

COMMIT;
