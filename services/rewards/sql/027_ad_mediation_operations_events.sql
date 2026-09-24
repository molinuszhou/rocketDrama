BEGIN;

-- 本表保存客户端已经展示或已触发 paid-impression 回调的广告运营事件。
-- 不保存设备/用户原始标识；客户端仅提交不可逆的 SHA-256 安装标识摘要。
CREATE TABLE IF NOT EXISTS ad_mediation_events (
    client_event_id TEXT PRIMARY KEY,
    install_id_hash CHAR(64) NOT NULL CHECK (install_id_hash ~ '^[0-9a-f]{64}$'),
    event_type TEXT NOT NULL CHECK (event_type IN ('SHOWN', 'PAID_REVENUE')),
    logical_placement TEXT NOT NULL,
    topon_placement_id TEXT NOT NULL,
    ad_format TEXT NOT NULL,
    ad_network TEXT NOT NULL,
    ad_source_id TEXT NOT NULL DEFAULT '',
    app_version TEXT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT '',
    ecpm NUMERIC(20, 8),
    impression_value NUMERIC(20, 12) NOT NULL DEFAULT 0,
    client_occurred_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ad_mediation_events_occurred_at
    ON ad_mediation_events (client_occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_ad_mediation_events_accepted_at
    ON ad_mediation_events (accepted_at);
CREATE INDEX IF NOT EXISTS idx_ad_mediation_events_dimension
    ON ad_mediation_events (client_occurred_at DESC, logical_placement, ad_network, ad_source_id);

-- 报表读取不再扫描明细；展示与 paid-impression 分开计数，收入只以 PAID_REVENUE 为准。
CREATE TABLE IF NOT EXISTS ad_mediation_daily_metrics (
    metric_date DATE NOT NULL,
    app_version TEXT NOT NULL,
    logical_placement TEXT NOT NULL,
    topon_placement_id TEXT NOT NULL,
    ad_format TEXT NOT NULL,
    ad_network TEXT NOT NULL,
    ad_source_id TEXT NOT NULL DEFAULT '',
    currency VARCHAR(3) NOT NULL DEFAULT '',
    shown_count BIGINT NOT NULL DEFAULT 0 CHECK (shown_count >= 0),
    paid_revenue_count BIGINT NOT NULL DEFAULT 0 CHECK (paid_revenue_count >= 0),
    revenue_value NUMERIC(20, 12) NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (
        metric_date,
        app_version,
        logical_placement,
        topon_placement_id,
        ad_format,
        ad_network,
        ad_source_id,
        currency
    )
);
CREATE INDEX IF NOT EXISTS idx_ad_mediation_daily_metrics_date
    ON ad_mediation_daily_metrics (metric_date DESC, logical_placement, ad_network);

COMMIT;
