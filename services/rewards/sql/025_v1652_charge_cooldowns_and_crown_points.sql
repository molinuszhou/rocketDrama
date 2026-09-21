BEGIN;

-- v1.6.5.2: seek and speed controls remain immediate player actions without
-- interruption. Existing horizontal-switch, rewarded-fallback and pause-ad
-- exception policies are deliberately retained.
ALTER TABLE reward_policy
    ADD COLUMN IF NOT EXISTS crown_episode_progress_points INTEGER NOT NULL DEFAULT 2
        CHECK (crown_episode_progress_points BETWEEN 1 AND 100),
    ADD COLUMN IF NOT EXISTS crown_rewarded_ad_progress_points INTEGER NOT NULL DEFAULT 1
        CHECK (crown_rewarded_ad_progress_points BETWEEN 1 AND 100),
    ADD COLUMN IF NOT EXISTS crown_rewarded_cooldown_seconds INTEGER NOT NULL DEFAULT 30
        CHECK (crown_rewarded_cooldown_seconds BETWEEN 0 AND 3600),
    ADD COLUMN IF NOT EXISTS release_boost_cooldown_seconds INTEGER NOT NULL DEFAULT 30
        CHECK (release_boost_cooldown_seconds BETWEEN 0 AND 3600);

UPDATE reward_policy
SET seek_interstitial_enabled = FALSE,
    speed_interstitial_enabled = FALSE,
    reward_claim_interstitial_enabled = FALSE,
    next_episode_interstitial_enabled = FALSE,
    crown_episode_progress_points = 2,
    crown_rewarded_ad_progress_points = 1,
    crown_rewarded_cooldown_seconds = 30,
    release_boost_cooldown_seconds = 30,
    updated_at = NOW()
WHERE id = 1;

CREATE INDEX IF NOT EXISTS idx_rewarded_ad_events_user_placement_processed
    ON rewarded_ad_events(user_id, placement_id, processed_at DESC);

INSERT INTO schema_migrations (version)
VALUES ('025_v1652_charge_cooldowns_and_crown_points')
ON CONFLICT (version) DO NOTHING;

COMMIT;
