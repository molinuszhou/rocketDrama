-- v8.1：降低插屏打扰；拖动/倍速插屏仅在待领取宝石达到运营阈值后触发。
-- 皇冠成长继续沿用五档，但默认倍率改为少量高倍率档位，增强长期激励。
BEGIN;

CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE reward_policy
    ADD COLUMN IF NOT EXISTS action_interstitial_reward_threshold_gems BIGINT NOT NULL DEFAULT 3000
        CHECK (action_interstitial_reward_threshold_gems >= 0);

DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version = '008_v81_ad_fatigue_and_crown_curve') THEN
        UPDATE reward_policy
        SET mid_episode_interstitial_trigger_seconds = 90,
            mid_episode_interstitial_hourly_limit = 2,
            seek_interstitial_hourly_limit = 1,
            speed_interstitial_hourly_limit = 1,
            action_interstitial_reward_threshold_gems = 3000,
            updated_at = NOW()
        WHERE id = 1;

        -- 少量高倍率档位：前两档容易获得，后两档更难但收益明显，皇冠作为长期目标。
        UPDATE achievement_policy SET
            required_rounds = CASE code
                WHEN 'BRONZE' THEN 10
                WHEN 'SILVER' THEN 40
                WHEN 'GOLD' THEN 150
                WHEN 'DIAMOND' THEN 400
                WHEN 'CROWN' THEN 1200
                ELSE required_rounds END,
            reward_gems = CASE code
                WHEN 'BRONZE' THEN 10000
                WHEN 'SILVER' THEN 20000
                WHEN 'GOLD' THEN 50000
                WHEN 'DIAMOND' THEN 120000
                WHEN 'CROWN' THEN 300000
                ELSE reward_gems END,
            episode_reward_multiplier = CASE code
                WHEN 'BRONZE' THEN 1.00
                WHEN 'SILVER' THEN 1.10
                WHEN 'GOLD' THEN 1.35
                WHEN 'DIAMOND' THEN 1.80
                WHEN 'CROWN' THEN 2.50
                ELSE episode_reward_multiplier END;

        INSERT INTO schema_migrations (version) VALUES ('008_v81_ad_fatigue_and_crown_curve');
    END IF;
END
$migration$;

COMMIT;
