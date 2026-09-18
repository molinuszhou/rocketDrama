BEGIN;

-- v1.6.3.6: Low-frequency ad map calibrated for 1h viewing:
-- 20–30 rewarded ads/hour, 6–10 interstitials/hour, and roughly 1.9m Gems in five days.
ALTER TABLE reward_policy
    ADD COLUMN IF NOT EXISTS episodes_per_rewarded_unlock INTEGER NOT NULL DEFAULT 1
        CHECK (episodes_per_rewarded_unlock BETWEEN 1 AND 100),
    ADD COLUMN IF NOT EXISTS episode_reward_boost_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS crown_rewarded_daily_limit INTEGER NOT NULL DEFAULT 5
        CHECK (crown_rewarded_daily_limit BETWEEN 0 AND 100),
    ADD COLUMN IF NOT EXISTS interstitial_global_hourly_limit INTEGER NOT NULL DEFAULT 8
        CHECK (interstitial_global_hourly_limit BETWEEN 0 AND 120);

DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version = '018_low_frequency_ad_policy') THEN
        UPDATE reward_policy
        SET episode_reward_gems = 6000,
            episodes_per_rewarded_unlock = 3,
            episode_reward_boost_enabled = FALSE,
            random_chest_min_gems = 2000,
            random_chest_max_gems = 4800,
            daily_random_chest_count = 1,
            episodes_per_random_chest = 10,
            crown_rewarded_ads_per_episode = 2,
            crown_rewarded_daily_limit = 5,
            release_boost_daily_limit = 3,
            next_episode_interstitial_enabled = FALSE,
            -- One mid-roll candidate every 12 minutes of actual content viewing.
            mid_episode_interstitial_enabled = TRUE,
            mid_episode_interstitial_trigger_seconds = 720,
            mid_episode_interstitial_hourly_limit = 0,
            seek_interstitial_enabled = TRUE,
            seek_interstitial_hourly_limit = 0,
            speed_interstitial_enabled = TRUE,
            speed_interstitial_hourly_limit = 0,
            drama_switch_interstitial_enabled = TRUE,
            drama_switch_interstitial_every_count = 3,
            drama_switch_interstitial_hourly_limit = 0,
            rewarded_fallback_interstitial_enabled = TRUE,
            -- All playback, behavior, fallback, switch and pause interstitials share this limit.
            interstitial_global_hourly_limit = 8,
            episode_interstitial_limit = 0,
            updated_at = NOW()
        WHERE id = 1;

        INSERT INTO schema_migrations (version) VALUES ('018_low_frequency_ad_policy');
    END IF;
END $migration$;

COMMIT;
