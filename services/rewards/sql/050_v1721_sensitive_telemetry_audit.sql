BEGIN;

-- Sensitive operational diagnostics are deliberately isolated from regular user,
-- reporting, and behavior tables. Payloads are application-layer encrypted before
-- insertion; no decrypted payload is eligible for CSV export.
CREATE TABLE IF NOT EXISTS sensitive_telemetry_audit_events (
    audit_id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    audit_type TEXT NOT NULL CHECK (audit_type IN ('BOOTSTRAP_IP', 'APPSFLYER_CONVERSION')),
    payload_fingerprint TEXT NOT NULL CHECK (char_length(payload_fingerprint) = 64),
    encrypted_payload TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (user_id, audit_type, payload_fingerprint)
);

CREATE INDEX IF NOT EXISTS idx_sensitive_telemetry_audit_events_expiry
    ON sensitive_telemetry_audit_events (expires_at);

CREATE INDEX IF NOT EXISTS idx_sensitive_telemetry_audit_events_user_received
    ON sensitive_telemetry_audit_events (user_id, received_at DESC);

-- The IP fingerprint is keyed HMAC rather than a bare hash. It supports a bounded
-- 180-day repeat-IP diagnostic without storing a plaintext address beyond 14 days.
CREATE TABLE IF NOT EXISTS sensitive_telemetry_ip_hash_observations (
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    ip_fingerprint TEXT NOT NULL CHECK (char_length(ip_fingerprint) = 64),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, ip_fingerprint)
);

CREATE INDEX IF NOT EXISTS idx_sensitive_telemetry_ip_hash_observations_expiry
    ON sensitive_telemetry_ip_hash_observations (expires_at);

COMMIT;
