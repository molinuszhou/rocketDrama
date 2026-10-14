BEGIN;

-- Episode unlocks are durable content entitlements, not one-process UI state.
-- The target format is <drama-id>:<one-based-episode-number>.
CREATE TABLE IF NOT EXISTS episode_unlock_entitlements (
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    episode_target TEXT NOT NULL CHECK (char_length(episode_target) BETWEEN 1 AND 256),
    source TEXT NOT NULL CHECK (source IN ('AD_REWARDED', 'AD_UNAVAILABLE', 'AD_REWARDED_LEGACY')),
    source_event_id TEXT,
    unlocked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, episode_target)
);

CREATE INDEX IF NOT EXISTS idx_episode_unlock_entitlements_user_recent
    ON episode_unlock_entitlements (user_id, unlocked_at DESC);

-- Preserve prior rewarded episode passes. Older Android clients used either
-- <drama-id>-episode-<number> or an already-normalized colon suffix.
WITH historical_unlocks AS (
    SELECT
        user_id,
        ad_event_id,
        COALESCE(processed_at, NOW()) AS unlocked_at,
        regexp_match(target_id, '^(.*)(?:-episode-|:)([0-9]+)$') AS target_parts,
        GREATEST(
            1,
            CASE
                WHEN COALESCE(result_payload ->> 'unlock_episode_count', '') ~ '^[0-9]+$'
                    THEN (result_payload ->> 'unlock_episode_count')::INTEGER
                ELSE 1
            END
        ) AS unlock_count
    FROM rewarded_ad_events
    WHERE placement_id = 'rv_episode_pass'
      AND target_id IS NOT NULL
), normalized_unlocks AS (
    SELECT user_id, ad_event_id, unlocked_at, target_parts[1] AS drama_id,
           target_parts[2]::INTEGER AS first_episode_number, unlock_count
    FROM historical_unlocks
    WHERE target_parts IS NOT NULL
)
INSERT INTO episode_unlock_entitlements
    (user_id, episode_target, source, source_event_id, unlocked_at)
SELECT
    user_id,
    drama_id || ':' || (first_episode_number + episode_offset)::TEXT,
    'AD_REWARDED_LEGACY',
    ad_event_id,
    unlocked_at
FROM normalized_unlocks
CROSS JOIN LATERAL generate_series(0, unlock_count - 1) AS series(episode_offset)
ON CONFLICT (user_id, episode_target) DO NOTHING;

-- Preserve the previously server-approved no-fill passes as well. These carry
-- no cash reward, but they are still durable playback rights for the episode.
WITH historical_no_fill_unlocks AS (
    SELECT
        user_id,
        created_at AS unlocked_at,
        regexp_match(episode_id, '^(.*)(?:-episode-|:)([0-9]+)$') AS target_parts
    FROM ad_unavailable_episode_unlocks
), normalized_no_fill_unlocks AS (
    SELECT user_id, unlocked_at, target_parts[1] AS drama_id,
           target_parts[2]::INTEGER AS episode_number
    FROM historical_no_fill_unlocks
    WHERE target_parts IS NOT NULL
)
INSERT INTO episode_unlock_entitlements
    (user_id, episode_target, source, source_event_id, unlocked_at)
SELECT
    user_id,
    drama_id || ':' || episode_number::TEXT,
    'AD_UNAVAILABLE',
    NULL,
    unlocked_at
FROM normalized_no_fill_unlocks
ON CONFLICT (user_id, episode_target) DO NOTHING;

COMMIT;
