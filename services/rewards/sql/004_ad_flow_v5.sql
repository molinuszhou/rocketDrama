BEGIN;

-- v5：下一集开始前的插屏解锁、用户主动额外解锁一集、以及可随时关闭的暂停原生广告。
CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE reward_policy
    ADD COLUMN IF NOT EXISTS next_episode_interstitial_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS next_episode_interstitial_hourly_limit INTEGER NOT NULL DEFAULT 24
        CHECK (next_episode_interstitial_hourly_limit BETWEEN 0 AND 48),
    ADD COLUMN IF NOT EXISTS next_episode_interstitial_cooldown_seconds INTEGER NOT NULL DEFAULT 0
        CHECK (next_episode_interstitial_cooldown_seconds >= 0),
    ADD COLUMN IF NOT EXISTS extra_episode_unlock_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS pause_ad_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS pause_ad_min_play_seconds INTEGER NOT NULL DEFAULT 90
        CHECK (pause_ad_min_play_seconds >= 0),
    ADD COLUMN IF NOT EXISTS pause_ad_cooldown_seconds INTEGER NOT NULL DEFAULT 300
        CHECK (pause_ad_cooldown_seconds >= 0);

DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version = '004_ad_flow_v5') THEN
        UPDATE reward_policy
        SET next_episode_interstitial_enabled = TRUE,
            -- 每个锁定分集均尝试广告解锁；设置为每小时 24 次以避免短剧连续播放被服务端误拦截。
            next_episode_interstitial_hourly_limit = 24,
            next_episode_interstitial_cooldown_seconds = 0,
            extra_episode_unlock_enabled = TRUE,
            pause_ad_enabled = TRUE,
            pause_ad_min_play_seconds = 90,
            pause_ad_cooldown_seconds = 300,
            pause_interstitial_hourly_limit = 2,
            updated_at = NOW()
        WHERE id = 1;

        INSERT INTO schema_migrations (version) VALUES ('004_ad_flow_v5');
    END IF;
END
$migration$;

ALTER TABLE interstitial_events DROP CONSTRAINT IF EXISTS interstitial_events_placement_id_check;
ALTER TABLE interstitial_events
    ADD CONSTRAINT interstitial_events_placement_id_check
    CHECK (placement_id IN ('int_timer', 'int_pause', 'int_next_episode_unlock', 'int_pause_native'));

COMMIT;
