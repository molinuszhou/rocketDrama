BEGIN;

-- v1.7.1.1: 用户可保存一个提现通道账户；平台只保存账户标识，不保存支付密码或卡信息。
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS withdrawal_accounts (
    user_id TEXT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    provider TEXT NOT NULL CHECK (provider IN ('PAYPAL', 'STRIPE', 'LINK')),
    account_identifier TEXT NOT NULL CHECK (char_length(account_identifier) BETWEEN 3 AND 256),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS withdrawal_requests (
    request_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    provider TEXT NOT NULL,
    amount_micros BIGINT NOT NULL CHECK (amount_micros > 0),
    status TEXT NOT NULL DEFAULT 'PENDING_REVIEW' CHECK (status IN ('PENDING_REVIEW', 'PROCESSING', 'PAID', 'REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_withdrawal_requests_user_created
    ON withdrawal_requests (user_id, created_at DESC);

COMMIT;
