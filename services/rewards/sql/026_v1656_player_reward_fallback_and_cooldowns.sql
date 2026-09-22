BEGIN;

-- v1.6.5.6: the prior one-time store-rating reward becomes part of the
-- newcomer reward. Only unclaimed welcome rewards are adjusted, so users
-- who already claimed remain untouched.
UPDATE market_wallet_policy
SET welcome_bonus_gems = welcome_bonus_gems + COALESCE((SELECT review_reward_gems FROM reward_policy WHERE id = 1), 0),
    updated_at = NOW();

UPDATE welcome_rewards
SET gems = gems + COALESCE((SELECT review_reward_gems FROM reward_policy WHERE id = 1), 0)
WHERE state = 'PENDING';

UPDATE reward_policy
SET review_reward_gems = 0,
    crown_rewarded_cooldown_seconds = 60,
    release_boost_cooldown_seconds = 60,
    updated_at = NOW()
WHERE id = 1;

-- An interstitial presented because a rewarded placement was unavailable is
-- not a substitute. If it genuinely displays, it may settle the original
-- placement exactly once. This ledger preserves idempotency and auditability.
CREATE TABLE IF NOT EXISTS rewarded_fallback_grants (
    interstitial_event_id TEXT PRIMARY KEY REFERENCES interstitial_events(event_id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    original_placement_id TEXT NOT NULL,
    target_id TEXT,
    episode_index INTEGER,
    result_payload JSONB NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rewarded_fallback_grants_user
    ON rewarded_fallback_grants(user_id, granted_at DESC);

COMMIT;
