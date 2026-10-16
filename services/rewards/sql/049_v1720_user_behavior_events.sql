BEGIN;

-- Anonymous, privacy-minimised client behaviour telemetry. The properties JSON
-- is bounded and sanitised by RewardsService; it intentionally excludes raw IP,
-- advertising click IDs and AppsFlyer conversion payloads. User-visible content
-- titles are retained for 90-day first-five-video funnel diagnostics.
CREATE TABLE IF NOT EXISTS user_behavior_events (
    event_id TEXT PRIMARY KEY CHECK (char_length(event_id) BETWEEN 8 AND 160),
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    session_id TEXT NOT NULL CHECK (char_length(session_id) BETWEEN 8 AND 80),
    event_category TEXT NOT NULL CHECK (event_category IN (
        'APP', 'SDK', 'ONBOARDING', 'PLAYBACK', 'AD', 'NETWORK', 'REWARDS'
    )),
    event_name TEXT NOT NULL CHECK (event_name ~ '^[A-Z][A-Z0-9_]{1,79}$'),
    properties JSONB NOT NULL DEFAULT '{}'::jsonb,
    app_version TEXT NOT NULL CHECK (char_length(app_version) BETWEEN 1 AND 64),
    client_occurred_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_behavior_events_occurred
    ON user_behavior_events (client_occurred_at DESC, event_category, event_name);

CREATE INDEX IF NOT EXISTS idx_user_behavior_events_user_occurred
    ON user_behavior_events (user_id, client_occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_behavior_events_accepted
    ON user_behavior_events (accepted_at);

COMMIT;
