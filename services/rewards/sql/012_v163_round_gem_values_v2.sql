BEGIN;

-- v1.6.3.2：按运营确认的吉利数值调整未来 Gems 奖励。
-- 仅更新未来签到、皇冠和十轮兼容奖励；历史已入账数据不回溯。
CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version = '012_v163_round_gem_values_v2') THEN
        UPDATE reward_policy
        SET r0_gems = 10000,
            ten_round_bonus_floor_gems = 400,
            updated_at = NOW()
        WHERE id = 1;

        UPDATE signin_reward_tiers
        SET gems = CASE day_number
            WHEN 1 THEN 600
            WHEN 2 THEN 1200
            WHEN 3 THEN 1600
            WHEN 4 THEN 2400
            WHEN 5 THEN 3000
            WHEN 6 THEN 5000
            WHEN 7 THEN 20000
            WHEN 14 THEN 26000
            WHEN 30 THEN 66000
            ELSE 600
        END;

        UPDATE achievement_policy
        SET reward_gems = CASE code
            WHEN 'BRONZE' THEN 12000
            WHEN 'SILVER' THEN 26000
            WHEN 'GOLD' THEN 66000
            WHEN 'DIAMOND' THEN 160000
            WHEN 'CROWN' THEN 400000
            ELSE reward_gems
        END;

        INSERT INTO schema_migrations (version) VALUES ('012_v163_round_gem_values_v2');
    END IF;
END
$migration$;

COMMIT;
