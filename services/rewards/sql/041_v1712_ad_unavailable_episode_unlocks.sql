BEGIN;

-- A no-fill/error must never create a cash reward. It can, however, grant a
-- tightly capped playback entitlement so an unavailable ad does not trap a viewer.
ALTER TABLE reward_policy
    ADD COLUMN IF NOT EXISTS ad_unavailable_episode_unlock_daily_limit INTEGER NOT NULL DEFAULT 3
        CHECK (ad_unavailable_episode_unlock_daily_limit BETWEEN 0 AND 20);

CREATE TABLE IF NOT EXISTS ad_unavailable_episode_unlocks (
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    business_date DATE NOT NULL,
    episode_id TEXT NOT NULL CHECK (char_length(episode_id) BETWEEN 1 AND 256),
    episode_index INTEGER NOT NULL CHECK (episode_index > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, business_date, episode_id)
);

CREATE INDEX IF NOT EXISTS idx_ad_unavailable_episode_unlocks_user_day
    ON ad_unavailable_episode_unlocks (user_id, business_date, created_at DESC);

COMMIT;
