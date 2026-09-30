BEGIN;

-- 注册于客户端实际加载 TopOn 激励广告之前的业务意图。intent_id 同时作为
-- TopOn USER_CUSTOM_DATA，服务端激励回调只可结算一条仍有效的意图。
CREATE TABLE IF NOT EXISTS topon_rewarded_intents (
    intent_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    business_date DATE NOT NULL,
    placement_id TEXT NOT NULL,
    target_id TEXT,
    episode_index INTEGER,
    status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SETTLED', 'REJECTED', 'EXPIRED')),
    topon_trans_id TEXT UNIQUE,
    topon_placement_id TEXT,
    adsource_id TEXT,
    network_firm_id TEXT,
    scenario_id TEXT,
    reward_name TEXT,
    reward_amount TEXT,
    ilrd TEXT,
    result_payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    settled_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '30 minutes'
);

CREATE INDEX IF NOT EXISTS idx_topon_rewarded_intents_user_status
    ON topon_rewarded_intents(user_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_topon_rewarded_intents_expiry
    ON topon_rewarded_intents(status, expires_at);

-- TopOn 以 trans_id 重试回调；该表保留签名已通过的回调事件并由唯一 trans_id 去重。
CREATE TABLE IF NOT EXISTS topon_rewarded_callback_events (
    trans_id TEXT PRIMARY KEY,
    intent_id TEXT NOT NULL REFERENCES topon_rewarded_intents(intent_id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    callback_payload JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMIT;
