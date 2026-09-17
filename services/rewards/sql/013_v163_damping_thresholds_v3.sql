BEGIN;

-- v1.6.3.3：按运营确认更新四档阻尼边界。
-- 档位倍率保持 1.00x / 0.70x / 0.45x / 0.25x；历史已入账 Gems 不回溯修改。
CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version = '013_v163_damping_thresholds_v3') THEN
        UPDATE reward_policy
        SET gem_damping_tier1_threshold = 700000,
            gem_damping_tier2_threshold = 1400000,
            gem_damping_tier3_threshold = 1900000,
            updated_at = NOW()
        WHERE id = 1;

        INSERT INTO schema_migrations (version) VALUES ('013_v163_damping_thresholds_v3');
    END IF;
END
$migration$;

COMMIT;
