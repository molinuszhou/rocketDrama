BEGIN;

-- v1.6.5.0: Keep episode rewards manually claimable at any amount, but only
-- interrupt the viewer with the automatic claim dialog once their pending
-- reward reaches the equivalent of US$5.00 (50,000 internal reward units).
ALTER TABLE reward_policy
    ADD COLUMN IF NOT EXISTS episode_reward_claim_prompt_threshold_gems BIGINT NOT NULL DEFAULT 50000;

UPDATE reward_policy
SET episode_reward_claim_prompt_threshold_gems = 50000,
    updated_at = NOW()
WHERE id = 1;

COMMIT;
