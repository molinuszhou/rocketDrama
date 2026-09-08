BEGIN;

-- v3.0 将欢迎奖励、钱包释放和展示进度持久化在独立积分服务中。
-- 所有 ALTER 均使用 IF NOT EXISTS，便于已运行 v0.1.0 数据库的原地升级。
ALTER TABLE reward_policy
    ADD COLUMN IF NOT EXISTS welcome_bonus_gems BIGINT NOT NULL DEFAULT 150000 CHECK (welcome_bonus_gems >= 0),
    ADD COLUMN IF NOT EXISTS minimum_exchange_gems BIGINT NOT NULL DEFAULT 10000 CHECK (minimum_exchange_gems > 0),
    ADD COLUMN IF NOT EXISTS daily_release_bps INTEGER NOT NULL DEFAULT 200 CHECK (daily_release_bps BETWEEN 1 AND 10000),
    ADD COLUMN IF NOT EXISTS release_boost_bps INTEGER NOT NULL DEFAULT 40 CHECK (release_boost_bps BETWEEN 1 AND 10000),
    ADD COLUMN IF NOT EXISTS release_boost_daily_limit INTEGER NOT NULL DEFAULT 3 CHECK (release_boost_daily_limit BETWEEN 0 AND 10),
    ADD COLUMN IF NOT EXISTS claim_stride_day0 INTEGER NOT NULL DEFAULT 1 CHECK (claim_stride_day0 > 0),
    ADD COLUMN IF NOT EXISTS claim_stride_day1_3 INTEGER NOT NULL DEFAULT 2 CHECK (claim_stride_day1_3 > 0),
    ADD COLUMN IF NOT EXISTS claim_stride_steady INTEGER NOT NULL DEFAULT 3 CHECK (claim_stride_steady > 0);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS geo_tier TEXT NOT NULL DEFAULT 't3' CHECK (geo_tier IN ('t1', 't2', 't3')),
    ADD COLUMN IF NOT EXISTS welcome_claimed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_wallet_release_date DATE,
    ADD COLUMN IF NOT EXISTS pending_release_micros BIGINT NOT NULL DEFAULT 0 CHECK (pending_release_micros >= 0),
    ADD COLUMN IF NOT EXISTS pending_release_days INTEGER NOT NULL DEFAULT 0 CHECK (pending_release_days BETWEEN 0 AND 3),
    ADD COLUMN IF NOT EXISTS last_active_date DATE;

CREATE TABLE IF NOT EXISTS geo_wallet_policy (
    geo_tier TEXT PRIMARY KEY CHECK (geo_tier IN ('t1', 't2', 't3')),
    gems_to_usd_rate BIGINT NOT NULL CHECK (gems_to_usd_rate > 0),
    withdrawal_threshold_micros BIGINT NOT NULL CHECK (withdrawal_threshold_micros > 0),
    welcome_bonus_gems BIGINT NOT NULL CHECK (welcome_bonus_gems >= 0),
    daily_release_micros BIGINT NOT NULL CHECK (daily_release_micros > 0),
    release_boost_micros BIGINT NOT NULL CHECK (release_boost_micros > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO geo_wallet_policy
    (geo_tier, gems_to_usd_rate, withdrawal_threshold_micros, welcome_bonus_gems, daily_release_micros, release_boost_micros)
VALUES
    ('t1', 250000, 5000000, 25000, 100000, 20000),
    ('t2', 600000, 2500000, 60000, 50000, 10000),
    ('t3', 1500000, 1000000, 150000, 20000, 4000)
ON CONFLICT (geo_tier) DO NOTHING;

CREATE TABLE IF NOT EXISTS welcome_rewards (
    user_id TEXT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    gems BIGINT NOT NULL CHECK (gems >= 0),
    state TEXT NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'CLAIMED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at TIMESTAMPTZ
);

ALTER TABLE daily_counters
    ADD COLUMN IF NOT EXISTS release_boost_count INTEGER NOT NULL DEFAULT 0 CHECK (release_boost_count >= 0),
    ADD COLUMN IF NOT EXISTS content_seconds INTEGER NOT NULL DEFAULT 0 CHECK (content_seconds >= 0);

-- 扩展可审计的激励广告用途。旧约束名称由 PostgreSQL 自动生成时可能不同，
-- 因此只对当前 v0.1.0 约束名称处理；新建数据库直接由这条约束覆盖。
ALTER TABLE rewarded_ad_events DROP CONSTRAINT IF EXISTS rewarded_ad_events_placement_id_check;
ALTER TABLE rewarded_ad_events
    ADD CONSTRAINT rewarded_ad_events_placement_id_check
    CHECK (placement_id IN (
        'rv_episode_pass', 'rv_claim_reward', 'rv_double_reward', 'rv_chest_task',
        'rv_signin_double', 'rv_release_boost'
    ));

-- 从旧版自动映射生成的现金钱包保留为已锁定钱包额度；之后仅允许显式兑换写入该字段。
-- 新装用户不会再由获得宝石自动产生现金钱包余额。
UPDATE users
SET last_active_date = COALESCE(last_active_date, CURRENT_DATE)
WHERE last_active_date IS NULL;

COMMIT;
