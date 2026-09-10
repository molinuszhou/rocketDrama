BEGIN;

-- v7：主动领取/签到/欢迎/解锁改为激励广告；被动播放操作保留插屏；
-- 同时增加皇冠广告等效追剧、倍速插屏和按日基础转入比例计算的钱包广告加速。
CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE reward_policy
    ADD COLUMN IF NOT EXISTS speed_interstitial_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS speed_interstitial_hourly_limit INTEGER NOT NULL DEFAULT 0
        CHECK (speed_interstitial_hourly_limit BETWEEN 0 AND 240),
    ADD COLUMN IF NOT EXISTS crown_rewarded_ads_per_episode INTEGER NOT NULL DEFAULT 2
        CHECK (crown_rewarded_ads_per_episode BETWEEN 1 AND 10),
    ADD COLUMN IF NOT EXISTS release_boost_percent NUMERIC(5,4) NOT NULL DEFAULT 0.2000
        CHECK (release_boost_percent > 0 AND release_boost_percent <= 1.0000);

-- 一条“领取全部宝石”激励广告只可授权一次全量领取，避免事件 ID 被重放。
CREATE TABLE IF NOT EXISTS episode_reward_claim_authorizations (
    ad_event_id TEXT PRIMARY KEY REFERENCES rewarded_ad_events(ad_event_id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_episode_reward_claim_authorizations_user
    ON episode_reward_claim_authorizations(user_id, used_at);

DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version = '006_rewarded_actions_and_player_v7') THEN
        UPDATE reward_policy
        SET
            -- 主动领取改为激励广告；保留字段仅用于兼容旧快照，不再触发插屏。
            reward_claim_interstitial_enabled = FALSE,
            -- 0 表示不设置服务端小时上限：每次拖拽或调速都应尝试请求广告。
            seek_interstitial_enabled = TRUE,
            seek_interstitial_hourly_limit = 0,
            mid_episode_interstitial_enabled = TRUE,
            mid_episode_interstitial_hourly_limit = 0,
            speed_interstitial_enabled = TRUE,
            speed_interstitial_hourly_limit = 0,
            crown_rewarded_ads_per_episode = 2,
            release_boost_percent = 0.2000,
            release_boost_daily_limit = 3,
            updated_at = NOW()
        WHERE id = 1;

        -- 降低五级皇冠达成门槛；每 2 条完整激励广告可抵 1 集有效追剧。
        UPDATE achievement_policy SET
            window_hours = 876000,
            required_rounds = CASE code
                WHEN 'BRONZE' THEN 10
                WHEN 'SILVER' THEN 40
                WHEN 'GOLD' THEN 120
                WHEN 'DIAMOND' THEN 300
                WHEN 'CROWN' THEN 800
                ELSE required_rounds
            END,
            required_signin_days = CASE code
                WHEN 'BRONZE' THEN 1
                WHEN 'SILVER' THEN 4
                WHEN 'GOLD' THEN 10
                WHEN 'DIAMOND' THEN 25
                WHEN 'CROWN' THEN 60
                ELSE required_signin_days
            END,
            required_rv_count = CASE code
                WHEN 'BRONZE' THEN 1
                WHEN 'SILVER' THEN 6
                WHEN 'GOLD' THEN 18
                WHEN 'DIAMOND' THEN 50
                WHEN 'CROWN' THEN 120
                ELSE required_rv_count
            END
        WHERE code IN ('BRONZE', 'SILVER', 'GOLD', 'DIAMOND', 'CROWN');

        INSERT INTO schema_migrations (version) VALUES ('006_rewarded_actions_and_player_v7');
    END IF;
END
$migration$;

ALTER TABLE rewarded_ad_events DROP CONSTRAINT IF EXISTS rewarded_ad_events_placement_id_check;
ALTER TABLE rewarded_ad_events
    ADD CONSTRAINT rewarded_ad_events_placement_id_check
    CHECK (placement_id IN (
        'rv_episode_pass', 'rv_claim_reward', 'rv_double_reward', 'rv_episode_reward_double',
        'rv_chest_task', 'rv_signin_double', 'rv_release_boost',
        'rv_random_chest_open', 'rv_episode_reward_boost',
        'rv_welcome_bonus', 'rv_checkin_complete', 'rv_episode_rewards_claim', 'rv_crown_upgrade'
    ));

ALTER TABLE interstitial_events DROP CONSTRAINT IF EXISTS interstitial_events_placement_id_check;
ALTER TABLE interstitial_events
    ADD CONSTRAINT interstitial_events_placement_id_check
    CHECK (placement_id IN (
        'int_timer', 'int_pause', 'int_next_episode_unlock', 'int_pause_native',
        'int_reward_claim', 'int_episode_midroll', 'int_seek', 'int_speed'
    ));

COMMIT;
