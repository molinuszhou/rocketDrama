BEGIN;

-- v1.6.4: Rewards are paid directly into a single local-currency wallet.
-- Legacy Gems columns are retained only for audit/history and are not exposed to the v1.6.4 client.

ALTER TABLE reward_policy
    ADD COLUMN IF NOT EXISTS interstitial_rewarded_skip_threshold INTEGER NOT NULL DEFAULT 3
        CHECK (interstitial_rewarded_skip_threshold >= 0 AND interstitial_rewarded_skip_threshold <= 50);

ALTER TABLE daily_counters
    ADD COLUMN IF NOT EXISTS rewarded_decline_count INTEGER NOT NULL DEFAULT 0
        CHECK (rewarded_decline_count >= 0),
    ADD COLUMN IF NOT EXISTS last_rewarded_declined_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS episode_task_policy (
    milestone_episode_count INTEGER PRIMARY KEY CHECK (milestone_episode_count > 0),
    reward_gems BIGINT NOT NULL CHECK (reward_gems >= 0),
    sort_order INTEGER NOT NULL UNIQUE CHECK (sort_order > 0)
);

CREATE TABLE IF NOT EXISTS episode_task_claims (
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    milestone_episode_count INTEGER NOT NULL REFERENCES episode_task_policy(milestone_episode_count),
    state TEXT NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'CLAIMED')),
    claimed_at TIMESTAMPTZ,
    PRIMARY KEY (user_id, milestone_episode_count)
);
CREATE INDEX IF NOT EXISTS idx_episode_task_claims_user_state
    ON episode_task_claims(user_id, state, milestone_episode_count);

-- “1, 3, 6, 9, 12 …” is a transparent watch-episode task line. Rewards are
-- directly credited after a user taps Claim; they do not consume an ad placement.
INSERT INTO episode_task_policy (milestone_episode_count, reward_gems, sort_order) VALUES
    (1, 6000, 1),
    (3, 10000, 2),
    (6, 15000, 3),
    (9, 18000, 4),
    (12, 22000, 5),
    (15, 26000, 6),
    (18, 30000, 7),
    (24, 40000, 8),
    (30, 50000, 9)
ON CONFLICT (milestone_episode_count) DO UPDATE
SET reward_gems = EXCLUDED.reward_gems,
    sort_order = EXCLUDED.sort_order;

DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version = '019_direct_cash_wallet_tasks_and_ad_cooldowns') THEN
        -- Move all previously earned asset layers into one cash wallet using the existing,
        -- per-user market policy. Existing lifetime reward progress remains for damping only.
        UPDATE users u
        SET cash_wallet_micros = u.cash_wallet_micros
                + u.available_balance_micros
                + (u.gems_balance * m.currency_micros_per_10000_gems / 10000),
            available_balance_micros = 0,
            gems_balance = 0,
            pending_release_micros = 0,
            pending_release_days = 0,
            updated_at = NOW()
        FROM market_wallet_policy m
        WHERE m.market_code = COALESCE(NULLIF(u.market_code, ''), 'DEFAULT');

        -- A $10 welcome reward is 100,000 internal reward units at the current
        -- 10,000-units-per-US$1 reference. Local policies determine its local display.
        UPDATE market_wallet_policy
        SET welcome_bonus_gems = 100000,
            updated_at = NOW();
        UPDATE welcome_rewards
        SET gems = 100000
        WHERE state = 'PENDING';
        UPDATE achievement_policy
        SET required_rounds = CASE code
                WHEN 'BRONZE' THEN 30
                WHEN 'SILVER' THEN 100
                WHEN 'GOLD' THEN 200
                WHEN 'DIAMOND' THEN 500
                WHEN 'CROWN' THEN 800
                ELSE required_rounds
            END,
            required_signin_days = 0,
            required_rv_count = 0,
            reward_gems = CASE code
                WHEN 'BRONZE' THEN 12000
                WHEN 'SILVER' THEN 26000
                WHEN 'GOLD' THEN 66000
                WHEN 'DIAMOND' THEN 80000
                WHEN 'CROWN' THEN 100000
                ELSE reward_gems
            END,
            episode_reward_multiplier = CASE code
                WHEN 'BRONZE' THEN 1.10
                WHEN 'SILVER' THEN 1.25
                WHEN 'GOLD' THEN 1.50
                WHEN 'DIAMOND' THEN 2.00
                WHEN 'CROWN' THEN 2.50
                ELSE episode_reward_multiplier
            END
        WHERE code IN ('BRONZE', 'SILVER', 'GOLD', 'DIAMOND', 'CROWN');

        -- Direct cash wallet policy. Reward numbers remain internal progress units,
        -- but all client-facing payout text is localized money.
        UPDATE reward_policy
        SET episode_reward_gems = 6000,
            random_chest_min_gems = 6000,
            random_chest_max_gems = 6000,
            daily_random_chest_count = 1,
            episodes_per_random_chest = 10,
            crown_rewarded_daily_limit = 10,
            release_boost_daily_limit = 10,
            rv_daily_limit = 0,
            -- The primary interstitial rules are now cooldown based, not hourly.
            interstitial_cooldown_seconds = 60,
            interstitial_global_hourly_limit = 0,
            episode_interstitial_limit = 0,
            mid_episode_interstitial_enabled = TRUE,
            mid_episode_interstitial_trigger_seconds = 180,
            mid_episode_interstitial_hourly_limit = 0,
            seek_interstitial_enabled = TRUE,
            seek_interstitial_hourly_limit = 0,
            speed_interstitial_enabled = TRUE,
            speed_interstitial_hourly_limit = 0,
            drama_switch_interstitial_enabled = TRUE,
            drama_switch_interstitial_every_count = 3,
            drama_switch_interstitial_hourly_limit = 0,
            interstitial_rewarded_skip_threshold = 3,
            updated_at = NOW()
        WHERE id = 1;

        INSERT INTO schema_migrations (version)
        VALUES ('019_direct_cash_wallet_tasks_and_ad_cooldowns');
    END IF;
END
$migration$;

COMMIT;
