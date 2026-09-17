BEGIN;
-- v1.6.3.5: One-time store-review reward. The amount remains runtime-configurable through the operator dashboard.
ALTER TABLE reward_policy
    ADD COLUMN IF NOT EXISTS review_reward_gems BIGINT NOT NULL DEFAULT 100000
        CHECK (review_reward_gems >= 0);

CREATE TABLE IF NOT EXISTS app_review_rewards (
    user_id TEXT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    gems BIGINT NOT NULL CHECK (gems >= 0),
    reference_id TEXT NOT NULL UNIQUE,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO schema_migrations (version) VALUES ('015_app_review_reward') ON CONFLICT (version) DO NOTHING;
COMMIT;
