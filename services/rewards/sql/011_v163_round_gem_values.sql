BEGIN;

-- v1.6.3.1：将非整百 Gems 调整为便于展示和运营传播的整百数值。
-- 仅更新尚未领取的未来奖励；已经入账的历史奖励不回溯修改。
CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version = '011_v163_round_gem_values') THEN
        UPDATE reward_policy
        SET r0_gems = 10700,
            ten_round_bonus_floor_gems = 400,
            updated_at = NOW()
        WHERE id = 1;

        -- 30 天签到档位：700、1,100、1,600、2,400、3,300、4,700，
        -- 第 7/14/30 天分别为 20,000、26,700、66,700。
        UPDATE signin_reward_tiers
        SET gems = CASE day_number
            WHEN 1 THEN 700
            WHEN 2 THEN 1100
            WHEN 3 THEN 1600
            WHEN 4 THEN 2400
            WHEN 5 THEN 3300
            WHEN 6 THEN 4700
            WHEN 7 THEN 20000
            WHEN 14 THEN 26700
            WHEN 30 THEN 66700
            ELSE 700
        END;

        -- 皇冠达成奖励统一为整百；单集倍率不变。
        UPDATE achievement_policy
        SET reward_gems = CASE code
            WHEN 'BRONZE' THEN 13300
            WHEN 'SILVER' THEN 26700
            WHEN 'GOLD' THEN 66700
            WHEN 'DIAMOND' THEN 160000
            WHEN 'CROWN' THEN 400000
            ELSE reward_gems
        END;

        INSERT INTO schema_migrations (version) VALUES ('011_v163_round_gem_values');
    END IF;
END
$migration$;

COMMIT;
