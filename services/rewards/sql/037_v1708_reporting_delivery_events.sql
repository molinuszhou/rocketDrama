BEGIN;

-- v1.7.0.8: retain a privacy-minimised server-side delivery trail for the
-- AppsFlyer / Firebase / Xuanyu reporting callbacks observed by the client.
-- No raw install/user ID, click ID, site ID, request URL or response body is stored.
CREATE TABLE IF NOT EXISTS reporting_delivery_events (
    client_event_id TEXT PRIMARY KEY,
    install_id_hash CHAR(64) NOT NULL CHECK (install_id_hash ~ '^[0-9a-f]{64}$'),
    provider TEXT NOT NULL CHECK (provider IN ('AppsFlyer', 'Firebase', 'Xuanyu')),
    event_name TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN (
        'SKIPPED_DISABLED', 'SKIPPED_NOT_CONFIGURED', 'STARTED', 'QUEUED',
        'SUCCESS', 'RETRYING', 'FAILED', 'FINAL_FAILED'
    )),
    logical_placement TEXT NOT NULL DEFAULT '',
    ad_unit_id TEXT NOT NULL DEFAULT '',
    error_code TEXT NOT NULL DEFAULT '',
    currency VARCHAR(3) NOT NULL DEFAULT '',
    ecpm NUMERIC(20, 8),
    reported_value NUMERIC(20, 12) NOT NULL DEFAULT 0,
    client_occurred_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_reporting_delivery_events_occurred
    ON reporting_delivery_events (client_occurred_at DESC, provider, status);
CREATE INDEX IF NOT EXISTS idx_reporting_delivery_events_dimension
    ON reporting_delivery_events (provider, event_name, logical_placement, client_occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_reporting_delivery_events_install
    ON reporting_delivery_events (install_id_hash, client_occurred_at DESC);

COMMIT;
