BEGIN;

CREATE TABLE IF NOT EXISTS reward_policy (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    exchange_rate_gems BIGINT NOT NULL CHECK (exchange_rate_gems > 0),
    r0_gems BIGINT NOT NULL CHECK (r0_gems >= 0),
    decay_alpha NUMERIC(8,4) NOT NULL CHECK (decay_alpha >= 0),
    floor_gems BIGINT NOT NULL CHECK (floor_gems >= 0),
    valid_watch_seconds INTEGER NOT NULL CHECK (valid_watch_seconds > 0),
    free_episodes_count INTEGER NOT NULL CHECK (free_episodes_count >= 0),
    rv_daily_limit INTEGER NOT NULL CHECK (rv_daily_limit > 0),
    claim_round_size INTEGER NOT NULL CHECK (claim_round_size > 0),
    chest_reward_gems BIGINT NOT NULL CHECK (chest_reward_gems >= 0),
    interstitial_cooldown_seconds INTEGER NOT NULL CHECK (interstitial_cooldown_seconds >= 0),
    interstitial_hourly_limit INTEGER NOT NULL CHECK (interstitial_hourly_limit >= 0),
    pause_interstitial_hourly_limit INTEGER NOT NULL CHECK (pause_interstitial_hourly_limit >= 0),
    withdrawal_threshold_micros BIGINT NOT NULL CHECK (withdrawal_threshold_micros >= 0),
    withdrawal_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO reward_policy (
    id, exchange_rate_gems, r0_gems, decay_alpha, floor_gems, valid_watch_seconds,
    free_episodes_count, rv_daily_limit, claim_round_size, chest_reward_gems,
    interstitial_cooldown_seconds, interstitial_hourly_limit, pause_interstitial_hourly_limit,
    withdrawal_threshold_micros, withdrawal_enabled
) VALUES (1, 150000, 7500, 1.35, 80, 60, 3, 20, 10, 2000, 90, 3, 2, 5000000, FALSE)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS signin_reward_tiers (
    day_number INTEGER PRIMARY KEY CHECK (day_number BETWEEN 1 AND 30),
    gems BIGINT NOT NULL CHECK (gems >= 0)
);

INSERT INTO signin_reward_tiers (day_number, gems) VALUES
    (1, 500), (2, 800), (3, 1200), (4, 1800), (5, 2500), (6, 3500), (7, 15000),
    (8, 500), (9, 500), (10, 500), (11, 500), (12, 500), (13, 500), (14, 20000),
    (15, 500), (16, 500), (17, 500), (18, 500), (19, 500), (20, 500), (21, 500),
    (22, 500), (23, 500), (24, 500), (25, 500), (26, 500), (27, 500), (28, 500),
    (29, 500), (30, 50000)
ON CONFLICT (day_number) DO NOTHING;

CREATE TABLE IF NOT EXISTS achievement_policy (
    code TEXT PRIMARY KEY,
    window_hours INTEGER NOT NULL CHECK (window_hours > 0),
    required_rounds INTEGER NOT NULL CHECK (required_rounds > 0),
    required_signin_days INTEGER NOT NULL DEFAULT 0 CHECK (required_signin_days >= 0),
    required_rv_count INTEGER NOT NULL DEFAULT 0 CHECK (required_rv_count >= 0),
    reward_gems BIGINT NOT NULL CHECK (reward_gems >= 0),
    virtual_entitlement TEXT NOT NULL
);

INSERT INTO achievement_policy (code, window_hours, required_rounds, required_signin_days, required_rv_count, reward_gems, virtual_entitlement) VALUES
    ('BRONZE', 24, 30, 0, 0, 10000, 'bronze_crown_avatar'),
    ('SILVER', 48, 60, 2, 0, 15000, 'silver_crown_exclusive_drama'),
    ('GOLD', 72, 100, 3, 10, 25000, 'gold_crown_triple_claim_card')
ON CONFLICT (code) DO NOTHING;

CREATE TABLE IF NOT EXISTS users (
    user_id TEXT PRIMARY KEY,
    gems_balance BIGINT NOT NULL DEFAULT 0 CHECK (gems_balance >= 0),
    total_gems_earned BIGINT NOT NULL DEFAULT 0 CHECK (total_gems_earned >= 0),
    cash_wallet_micros BIGINT NOT NULL DEFAULT 0 CHECK (cash_wallet_micros >= 0),
    available_balance_micros BIGINT NOT NULL DEFAULT 0 CHECK (available_balance_micros >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS daily_counters (
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    business_date DATE NOT NULL,
    rv_count INTEGER NOT NULL DEFAULT 0 CHECK (rv_count >= 0),
    interstitial_count INTEGER NOT NULL DEFAULT 0 CHECK (interstitial_count >= 0),
    pause_interstitial_count INTEGER NOT NULL DEFAULT 0 CHECK (pause_interstitial_count >= 0),
    episode_count INTEGER NOT NULL DEFAULT 0 CHECK (episode_count >= 0),
    completed_rounds INTEGER NOT NULL DEFAULT 0 CHECK (completed_rounds >= 0),
    claimed_cycles INTEGER NOT NULL DEFAULT 0 CHECK (claimed_cycles >= 0),
    last_rv_at TIMESTAMPTZ,
    last_interstitial_at TIMESTAMPTZ,
    PRIMARY KEY (user_id, business_date)
);

CREATE TABLE IF NOT EXISTS rewarded_ad_events (
    ad_event_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    business_date DATE NOT NULL,
    placement_id TEXT NOT NULL CHECK (placement_id IN ('rv_episode_pass', 'rv_claim_reward', 'rv_double_reward', 'rv_chest_task', 'rv_signin_double')),
    target_id TEXT,
    ad_timestamp TIMESTAMPTZ NOT NULL,
    verification_method TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    result_code TEXT NOT NULL DEFAULT 'ACCEPTED',
    result_payload JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_rewarded_ad_events_user_day ON rewarded_ad_events(user_id, business_date, processed_at DESC);

CREATE TABLE IF NOT EXISTS watch_events (
    event_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    business_date DATE NOT NULL,
    drama_id TEXT NOT NULL,
    episode_id TEXT NOT NULL,
    watched_seconds INTEGER NOT NULL CHECK (watched_seconds > 0),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_watch_events_user_episode ON watch_events(user_id, episode_id, recorded_at DESC);

CREATE TABLE IF NOT EXISTS episode_rounds (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    business_date DATE NOT NULL,
    round_number INTEGER NOT NULL CHECK (round_number > 0),
    episode_id TEXT NOT NULL,
    rewarded_event_id TEXT NOT NULL REFERENCES rewarded_ad_events(ad_event_id),
    watch_event_id TEXT UNIQUE REFERENCES watch_events(event_id),
    base_reward_gems BIGINT NOT NULL DEFAULT 0 CHECK (base_reward_gems >= 0),
    state TEXT NOT NULL CHECK (state IN ('PENDING_WATCH', 'COMPLETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    UNIQUE (user_id, business_date, round_number),
    UNIQUE (user_id, episode_id, business_date)
);
CREATE INDEX IF NOT EXISTS idx_episode_rounds_completed ON episode_rounds(user_id, business_date, state, round_number);

CREATE TABLE IF NOT EXISTS pending_claims (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    business_date DATE NOT NULL,
    cycle_number INTEGER NOT NULL CHECK (cycle_number > 0),
    round_from INTEGER NOT NULL,
    round_to INTEGER NOT NULL,
    base_reward_gems BIGINT NOT NULL CHECK (base_reward_gems >= 0),
    state TEXT NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'CLAIMED', 'DOUBLED')),
    claim_ad_event_id TEXT UNIQUE REFERENCES rewarded_ad_events(ad_event_id),
    double_ad_event_id TEXT UNIQUE REFERENCES rewarded_ad_events(ad_event_id),
    claimed_at TIMESTAMPTZ,
    doubled_at TIMESTAMPTZ,
    UNIQUE (user_id, business_date, cycle_number)
);

CREATE TABLE IF NOT EXISTS checkins (
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    business_date DATE NOT NULL,
    streak_day INTEGER NOT NULL CHECK (streak_day BETWEEN 1 AND 30),
    base_gems BIGINT NOT NULL CHECK (base_gems >= 0),
    double_gems BIGINT NOT NULL DEFAULT 0 CHECK (double_gems >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, business_date)
);

CREATE TABLE IF NOT EXISTS chest_claims (
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    business_date DATE NOT NULL,
    ad_event_id TEXT UNIQUE REFERENCES rewarded_ad_events(ad_event_id),
    gems BIGINT NOT NULL CHECK (gems >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, business_date)
);

CREATE TABLE IF NOT EXISTS user_achievements (
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    code TEXT NOT NULL REFERENCES achievement_policy(code),
    gems BIGINT NOT NULL CHECK (gems >= 0),
    virtual_entitlement TEXT NOT NULL,
    earned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, code)
);

CREATE TABLE IF NOT EXISTS interstitial_events (
    event_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    business_date DATE NOT NULL,
    placement_id TEXT NOT NULL CHECK (placement_id IN ('int_timer', 'int_pause')),
    shown_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_interstitial_events_user_time ON interstitial_events(user_id, shown_at DESC);

CREATE TABLE IF NOT EXISTS ledger_entries (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    entry_type TEXT NOT NULL,
    gems_delta BIGINT NOT NULL DEFAULT 0,
    cash_wallet_delta_micros BIGINT NOT NULL DEFAULT 0,
    available_balance_delta_micros BIGINT NOT NULL DEFAULT 0,
    reference_type TEXT NOT NULL,
    reference_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (reference_type, reference_id)
);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_user_time ON ledger_entries(user_id, created_at DESC);

COMMIT;
