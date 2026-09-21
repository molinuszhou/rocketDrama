BEGIN;

-- v1.6.5.1: watch tasks reset every business day and remain capped at 90 episodes.
-- Four random-redpacket tiers preserve the established 5/4/3-day reward progression
-- while making higher daily milestones visibly more valuable.
ALTER TABLE reward_policy
    ADD COLUMN IF NOT EXISTS episode_task_tier1_min_gems BIGINT NOT NULL DEFAULT 800 CHECK (episode_task_tier1_min_gems >= 0),
    ADD COLUMN IF NOT EXISTS episode_task_tier1_max_gems BIGINT NOT NULL DEFAULT 1200 CHECK (episode_task_tier1_max_gems >= 0),
    ADD COLUMN IF NOT EXISTS episode_task_tier2_min_gems BIGINT NOT NULL DEFAULT 1200 CHECK (episode_task_tier2_min_gems >= 0),
    ADD COLUMN IF NOT EXISTS episode_task_tier2_max_gems BIGINT NOT NULL DEFAULT 1800 CHECK (episode_task_tier2_max_gems >= 0),
    ADD COLUMN IF NOT EXISTS episode_task_tier3_min_gems BIGINT NOT NULL DEFAULT 2000 CHECK (episode_task_tier3_min_gems >= 0),
    ADD COLUMN IF NOT EXISTS episode_task_tier3_max_gems BIGINT NOT NULL DEFAULT 3000 CHECK (episode_task_tier3_max_gems >= 0),
    ADD COLUMN IF NOT EXISTS episode_task_tier4_min_gems BIGINT NOT NULL DEFAULT 3000 CHECK (episode_task_tier4_min_gems >= 0),
    ADD COLUMN IF NOT EXISTS episode_task_tier4_max_gems BIGINT NOT NULL DEFAULT 4000 CHECK (episode_task_tier4_max_gems >= 0);

ALTER TABLE reward_policy DROP CONSTRAINT IF EXISTS reward_policy_episode_task_max_episode_check;
ALTER TABLE reward_policy
    ADD CONSTRAINT reward_policy_episode_task_max_episode_check
    CHECK (episode_task_max_episode >= 18 AND episode_task_max_episode <= 90);

UPDATE reward_policy
SET episode_task_max_episode = 90,
    episode_task_post18_interval = 6,
    episode_task_tier1_min_gems = 800,
    episode_task_tier1_max_gems = 1200,
    episode_task_tier2_min_gems = 1200,
    episode_task_tier2_max_gems = 1800,
    episode_task_tier3_min_gems = 2000,
    episode_task_tier3_max_gems = 3000,
    episode_task_tier4_min_gems = 3000,
    episode_task_tier4_max_gems = 4000,
    updated_at = NOW()
WHERE id = 1;

-- Historical one-time milestone claims are retained as prior-day history so they
-- never block a user's fresh daily task line after upgrade.
ALTER TABLE episode_task_claims
    ADD COLUMN IF NOT EXISTS business_date DATE;
UPDATE episode_task_claims
SET business_date = CURRENT_DATE - 1
WHERE business_date IS NULL;
ALTER TABLE episode_task_claims
    ALTER COLUMN business_date SET NOT NULL;
ALTER TABLE episode_task_claims
    DROP CONSTRAINT IF EXISTS episode_task_claims_pkey;
ALTER TABLE episode_task_claims
    ADD PRIMARY KEY (user_id, business_date, milestone_episode_count);
DROP INDEX IF EXISTS idx_episode_task_claims_user_state;
CREATE INDEX IF NOT EXISTS idx_episode_task_claims_user_date_state
    ON episode_task_claims(user_id, business_date, state, milestone_episode_count);

INSERT INTO schema_migrations (version)
VALUES ('024_v1651_daily_task_tiers')
ON CONFLICT (version) DO NOTHING;

COMMIT;
