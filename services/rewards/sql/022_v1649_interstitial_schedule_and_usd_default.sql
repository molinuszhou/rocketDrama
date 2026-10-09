BEGIN;

-- v1.6.4.9: deterministic mid-roll schedule and USD fallback.
-- First mid-roll after 3 minutes of real content watch time; subsequent
-- mid-rolls after 6 more minutes. Seek/speed/switch/fallback rules remain
-- independently governed by their existing cooldown and eligibility checks.
ALTER TABLE reward_policy
    ADD COLUMN IF NOT EXISTS mid_episode_interstitial_repeat_seconds INTEGER NOT NULL DEFAULT 360;

UPDATE reward_policy
SET mid_episode_interstitial_enabled = TRUE,
    mid_episode_interstitial_trigger_seconds = 180,
    mid_episode_interstitial_repeat_seconds = 360,
    updated_at = NOW()
WHERE id = 1;

-- USD is the single default display currency. BR and ID stay as explicitly
-- configured localized markets. Retain a disabled compatibility row for legacy
-- HK users instead of deleting a policy that may still be referenced by users.
UPDATE market_wallet_policy
SET enabled = FALSE, disabled_at = COALESCE(disabled_at, NOW()), updated_at = NOW()
WHERE market_code = 'HK';

-- A rebuilt database starts with a consistent US$200 first withdrawal target.
UPDATE market_wallet_policy
SET withdrawal_threshold_micros = CASE market_code
        WHEN 'BR' THEN 600000000       -- R$600.00
        WHEN 'ID' THEN 2000000000000   -- Rp2,000,000
        ELSE 200000000                 -- US$200.00
    END,
    updated_at = NOW();

COMMIT;
