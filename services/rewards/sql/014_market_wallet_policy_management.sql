BEGIN;

-- v1.6.3.4：区域钱包策略通用管理。
-- enabled=false 表示停止给新用户分配该市场；已有用户仍可读取原策略，避免账本币种混用。
ALTER TABLE market_wallet_policy
    ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS disabled_at TIMESTAMPTZ;

UPDATE market_wallet_policy
SET enabled = TRUE,
    disabled_at = NULL
WHERE market_code = 'DEFAULT';

CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO schema_migrations (version)
VALUES ('014_market_wallet_policy_management')
ON CONFLICT (version) DO NOTHING;

COMMIT;
