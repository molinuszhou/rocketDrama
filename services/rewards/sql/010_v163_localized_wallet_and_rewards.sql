-- v1.6.3：全渠道四档宝石阻尼、200 万 Gems 生命周期上限、区域本币钱包和 45 秒剧中插屏默认值。
-- 本迁移不改写已入账现金；既有用户继续使用 DEFAULT（USD）市场，以避免已存在本币账本发生混币。
BEGIN;

CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS market_code TEXT NOT NULL DEFAULT 'DEFAULT';

ALTER TABLE reward_policy
    ADD COLUMN IF NOT EXISTS gem_damping_tier3_threshold BIGINT NOT NULL DEFAULT 1200000
        CHECK (gem_damping_tier3_threshold >= 0),
    ADD COLUMN IF NOT EXISTS gem_damping_tier4_multiplier NUMERIC(6,4) NOT NULL DEFAULT 0.2500
        CHECK (gem_damping_tier4_multiplier >= 0 AND gem_damping_tier4_multiplier <= 1);

-- 金额在账本中仍以“当地货币 × 1,000,000”的微单位存储；不同用户的钱包不混用币种。
CREATE TABLE IF NOT EXISTS market_wallet_policy (
    market_code TEXT PRIMARY KEY,
    currency_code TEXT NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
    currency_symbol TEXT NOT NULL,
    currency_fraction_digits SMALLINT NOT NULL DEFAULT 2 CHECK (currency_fraction_digits BETWEEN 0 AND 6),
    currency_micros_per_10000_gems BIGINT NOT NULL CHECK (currency_micros_per_10000_gems > 0),
    usd_micros_per_10000_gems BIGINT NOT NULL DEFAULT 1000000 CHECK (usd_micros_per_10000_gems > 0),
    withdrawal_threshold_micros BIGINT NOT NULL CHECK (withdrawal_threshold_micros > 0),
    welcome_bonus_gems BIGINT NOT NULL CHECK (welcome_bonus_gems >= 0),
    daily_release_micros BIGINT NOT NULL CHECK (daily_release_micros > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO market_wallet_policy (
    market_code, currency_code, currency_symbol, currency_fraction_digits,
    currency_micros_per_10000_gems, usd_micros_per_10000_gems,
    withdrawal_threshold_micros, welcome_bonus_gems, daily_release_micros
) VALUES
    -- 未设置市场均使用美元：10,000 Gems = US$1.00。
    ('DEFAULT', 'USD', '$', 2, 1000000, 1000000, 1000000, 200000, 20000),
    -- 巴西默认：10,000 Gems = R$5.00，3,000,000 Gems = R$1,500；仍提供 US$1.00 参考值。
    ('BR', 'BRL', 'R$', 2, 5000000, 1000000, 1500000000, 200000, 60000),
    -- 印尼默认：10,000 Gems = Rp20,000，3,000,000 Gems = Rp6,000,000；仍提供 US$1.00 参考值。
    ('ID', 'IDR', 'Rp', 0, 20000000000, 1000000, 6000000000000, 200000, 200000000)
ON CONFLICT (market_code) DO UPDATE SET
    currency_code = EXCLUDED.currency_code,
    currency_symbol = EXCLUDED.currency_symbol,
    currency_fraction_digits = EXCLUDED.currency_fraction_digits,
    currency_micros_per_10000_gems = EXCLUDED.currency_micros_per_10000_gems,
    usd_micros_per_10000_gems = EXCLUDED.usd_micros_per_10000_gems,
    withdrawal_threshold_micros = EXCLUDED.withdrawal_threshold_micros,
    welcome_bonus_gems = EXCLUDED.welcome_bonus_gems,
    daily_release_micros = EXCLUDED.daily_release_micros,
    updated_at = NOW();

DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version = '010_v163_localized_wallet_and_rewards') THEN
        -- 2,000,000 / 1,500,000 = 4 / 3：奖励基数按同一比例四舍五入至整数。
        UPDATE reward_policy
        SET episode_reward_gems = 800,
            r0_gems = 10667,
            floor_gems = 400,
            ten_round_bonus_floor_gems = 400,
            random_chest_min_gems = 400,
            random_chest_max_gems = 1600,
            gems_earned_cap = 2000000,
            gem_damping_tier1_threshold = 500000,
            gem_damping_tier2_threshold = 900000,
            gem_damping_tier3_threshold = 1200000,
            gem_damping_tier1_multiplier = 1.0000,
            gem_damping_tier2_multiplier = 0.7000,
            gem_damping_tier3_multiplier = 0.4500,
            gem_damping_tier4_multiplier = 0.2500,
            mid_episode_interstitial_trigger_seconds = 45,
            updated_at = NOW()
        WHERE id = 1;

        -- 既有签到档位、尚未领取欢迎奖励和未来皇冠奖励同步上调；已入账奖励不回写。
        UPDATE signin_reward_tiers SET gems = ROUND(gems * 4.0 / 3.0)::BIGINT;
        UPDATE welcome_rewards SET gems = 200000 WHERE state = 'PENDING';
        UPDATE achievement_policy SET reward_gems = ROUND(reward_gems * 4.0 / 3.0)::BIGINT;
        UPDATE geo_wallet_policy
        SET gems_to_usd_rate = 10000,
            welcome_bonus_gems = 200000,
            updated_at = NOW();

        INSERT INTO schema_migrations (version) VALUES ('010_v163_localized_wallet_and_rewards');
    END IF;
END
$migration$;

COMMIT;
