-- v8：本集翻倍预授权、播放器广告默认时机与钱包广告加速体验调整。
-- 0 代表钱包广告加速不设置每日次数上限；金额始终受实际钱包余额限制。
CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version = '007_v8_player_reward_polish') THEN
        UPDATE reward_policy
        SET mid_episode_interstitial_trigger_seconds = 30,
            release_boost_daily_limit = 0,
            updated_at = NOW()
        WHERE id = 1;

        INSERT INTO schema_migrations (version) VALUES ('007_v8_player_reward_polish');
    END IF;
END
$migration$;

COMMIT;
