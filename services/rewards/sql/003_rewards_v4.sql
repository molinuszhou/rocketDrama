BEGIN;

-- v4：每集有效观看先形成待领取宝石；每 10 集另形成一笔受阻尼系数影响的十轮追剧奖励。
CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE reward_policy
    ADD COLUMN IF NOT EXISTS episode_reward_gems BIGINT NOT NULL DEFAULT 600 CHECK (episode_reward_gems >= 0),
    ADD COLUMN IF NOT EXISTS ten_round_size INTEGER NOT NULL DEFAULT 10 CHECK (ten_round_size BETWEEN 1 AND 100),
    ADD COLUMN IF NOT EXISTS ten_round_bonus_floor_gems BIGINT NOT NULL DEFAULT 300 CHECK (ten_round_bonus_floor_gems >= 0);

-- 历史 pending_claims 来自旧版按集领奖逻辑；v4 仅向新建的 TEN_ROUND 类型写入与展示。
ALTER TABLE pending_claims
    ADD COLUMN IF NOT EXISTS claim_kind TEXT NOT NULL DEFAULT 'LEGACY'
        CHECK (claim_kind IN ('LEGACY', 'TEN_ROUND'));
ALTER TABLE pending_claims
    DROP CONSTRAINT IF EXISTS pending_claims_user_id_business_date_cycle_number_key;
CREATE TABLE IF NOT EXISTS episode_reward_claims (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    business_date DATE NOT NULL,
    episode_id TEXT NOT NULL,
    base_reward_gems BIGINT NOT NULL CHECK (base_reward_gems >= 0),
    state TEXT NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'CLAIMED', 'DOUBLED')),
    claim_reference_id TEXT UNIQUE,
    double_ad_event_id TEXT UNIQUE REFERENCES rewarded_ad_events(ad_event_id),
    claimed_at TIMESTAMPTZ,
    doubled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, episode_id)
);
CREATE INDEX IF NOT EXISTS idx_episode_reward_claims_user_state
    ON episode_reward_claims(user_id, business_date, state, id);

ALTER TABLE achievement_policy
    ADD COLUMN IF NOT EXISTS level_rank INTEGER NOT NULL DEFAULT 99 CHECK (level_rank BETWEEN 1 AND 99);

UPDATE achievement_policy
SET level_rank = CASE code
    WHEN 'BRONZE' THEN 1
    WHEN 'SILVER' THEN 2
    WHEN 'GOLD' THEN 3
    WHEN 'DIAMOND' THEN 4
    WHEN 'CROWN' THEN 5
    ELSE 99
END;

DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version = '003_rewards_v4') THEN
        UPDATE reward_policy
        SET free_episodes_count = 3,
            claim_stride_day0 = 10,
            claim_stride_day1_3 = 10,
            claim_stride_steady = 10,
            episode_reward_gems = 600,
            ten_round_size = 10,
            r0_gems = 8000,
            decay_alpha = 1.3500,
            ten_round_bonus_floor_gems = 300,
            updated_at = NOW()
        WHERE id = 1;

        EXECUTE 'DROP INDEX IF EXISTS idx_pending_claims_ten_round_unique';
        EXECUTE 'CREATE UNIQUE INDEX idx_pending_claims_ten_round_unique ON pending_claims(user_id, cycle_number) WHERE claim_kind = ''TEN_ROUND''';

        INSERT INTO achievement_policy
            (code, window_hours, required_rounds, required_signin_days, required_rv_count, reward_gems, virtual_entitlement, level_rank)
        VALUES
            ('BRONZE', 876000, 150, 7, 8, 12000, 'bronze_crown_badge', 1),
            ('SILVER', 876000, 500, 21, 35, 35000, 'silver_crown_badge', 2),
            ('GOLD', 876000, 1200, 45, 90, 80000, 'gold_crown_badge', 3),
            ('DIAMOND', 876000, 3000, 90, 220, 200000, 'diamond_crown_badge', 4),
            ('CROWN', 876000, 8000, 180, 500, 500000, 'crown_badge', 5)
        ON CONFLICT (code) DO UPDATE SET
            window_hours = EXCLUDED.window_hours,
            required_rounds = EXCLUDED.required_rounds,
            required_signin_days = EXCLUDED.required_signin_days,
            required_rv_count = EXCLUDED.required_rv_count,
            reward_gems = EXCLUDED.reward_gems,
            virtual_entitlement = EXCLUDED.virtual_entitlement,
            level_rank = EXCLUDED.level_rank;

        INSERT INTO schema_migrations (version) VALUES ('003_rewards_v4');
    END IF;
END
$migration$;

ALTER TABLE rewarded_ad_events DROP CONSTRAINT IF EXISTS rewarded_ad_events_placement_id_check;
ALTER TABLE rewarded_ad_events
    ADD CONSTRAINT rewarded_ad_events_placement_id_check
    CHECK (placement_id IN (
        'rv_episode_pass', 'rv_claim_reward', 'rv_double_reward', 'rv_episode_reward_double',
        'rv_chest_task', 'rv_signin_double', 'rv_release_boost'
    ));

CREATE TABLE IF NOT EXISTS reward_policy_admin_audit (
    id BIGSERIAL PRIMARY KEY,
    actor TEXT NOT NULL,
    field_name TEXT NOT NULL,
    old_value TEXT NOT NULL,
    new_value TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMIT;
