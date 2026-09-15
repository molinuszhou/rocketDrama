-- v1.6.2：降低广告打扰并对追剧宝石实施可调节的生命周期阻尼与上限。
-- 激励广告默认不限次；仅钱包广告加速保留每日 10 次独立上限。
BEGIN;

CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 0 表示不限次数。早期初始化表的约束只允许正数，迁移后显式放宽。
ALTER TABLE reward_policy DROP CONSTRAINT IF EXISTS reward_policy_rv_daily_limit_check;
ALTER TABLE reward_policy
    ADD CONSTRAINT reward_policy_rv_daily_limit_check CHECK (rv_daily_limit >= 0);

ALTER TABLE reward_policy
    ADD COLUMN IF NOT EXISTS episode_interstitial_limit INTEGER NOT NULL DEFAULT 1
        CHECK (episode_interstitial_limit >= 0 AND episode_interstitial_limit <= 10),
    ADD COLUMN IF NOT EXISTS drama_switch_interstitial_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS drama_switch_interstitial_every_count INTEGER NOT NULL DEFAULT 3
        CHECK (drama_switch_interstitial_every_count >= 1 AND drama_switch_interstitial_every_count <= 50),
    ADD COLUMN IF NOT EXISTS drama_switch_interstitial_hourly_limit INTEGER NOT NULL DEFAULT 6
        CHECK (drama_switch_interstitial_hourly_limit >= 0 AND drama_switch_interstitial_hourly_limit <= 120),
    ADD COLUMN IF NOT EXISTS rewarded_fallback_interstitial_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS gems_earned_cap BIGINT NOT NULL DEFAULT 1500000
        CHECK (gems_earned_cap >= 0),
    ADD COLUMN IF NOT EXISTS gem_damping_tier1_threshold BIGINT NOT NULL DEFAULT 200000
        CHECK (gem_damping_tier1_threshold >= 0),
    ADD COLUMN IF NOT EXISTS gem_damping_tier2_threshold BIGINT NOT NULL DEFAULT 600000
        CHECK (gem_damping_tier2_threshold >= 0),
    ADD COLUMN IF NOT EXISTS gem_damping_tier1_multiplier NUMERIC(6,4) NOT NULL DEFAULT 1.0000
        CHECK (gem_damping_tier1_multiplier >= 0 AND gem_damping_tier1_multiplier <= 1),
    ADD COLUMN IF NOT EXISTS gem_damping_tier2_multiplier NUMERIC(6,4) NOT NULL DEFAULT 0.5000
        CHECK (gem_damping_tier2_multiplier >= 0 AND gem_damping_tier2_multiplier <= 1),
    ADD COLUMN IF NOT EXISTS gem_damping_tier3_multiplier NUMERIC(6,4) NOT NULL DEFAULT 0.2500
        CHECK (gem_damping_tier3_multiplier >= 0 AND gem_damping_tier3_multiplier <= 1);

ALTER TABLE interstitial_events
    ADD COLUMN IF NOT EXISTS episode_id TEXT;
CREATE INDEX IF NOT EXISTS idx_interstitial_events_user_episode
    ON interstitial_events(user_id, episode_id, shown_at DESC)
    WHERE episode_id IS NOT NULL;

ALTER TABLE interstitial_events DROP CONSTRAINT IF EXISTS interstitial_events_placement_id_check;
ALTER TABLE interstitial_events
    ADD CONSTRAINT interstitial_events_placement_id_check
    CHECK (placement_id IN (
        'int_timer', 'int_pause', 'int_next_episode_unlock', 'int_pause_native',
        'int_reward_claim', 'int_episode_midroll', 'int_seek', 'int_speed',
        'int_rewarded_fallback', 'int_drama_switch'
    ));

DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version = '009_v162_reward_control') THEN
        UPDATE reward_policy
        SET rv_daily_limit = 0,
            release_boost_daily_limit = 10,
            episode_interstitial_limit = 1,
            drama_switch_interstitial_enabled = TRUE,
            drama_switch_interstitial_every_count = 3,
            drama_switch_interstitial_hourly_limit = 6,
            rewarded_fallback_interstitial_enabled = TRUE,
            gems_earned_cap = 1500000,
            gem_damping_tier1_threshold = 200000,
            gem_damping_tier2_threshold = 600000,
            gem_damping_tier1_multiplier = 1.0000,
            gem_damping_tier2_multiplier = 0.5000,
            gem_damping_tier3_multiplier = 0.2500,
            updated_at = NOW()
        WHERE id = 1;

        INSERT INTO schema_migrations (version) VALUES ('009_v162_reward_control');
    END IF;
END
$migration$;

COMMIT;
