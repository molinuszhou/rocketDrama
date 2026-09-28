BEGIN;
-- v1.6.5.7: align the newcomer cash promise and first cash-out target with the
-- reward-center product review. Existing users who already claimed are untouched.
UPDATE market_wallet_policy
SET welcome_bonus_gems = 600000,
    withdrawal_threshold_micros = CASE market_code
        WHEN 'BR' THEN 900000000       -- R$900.00
        WHEN 'ID' THEN 3000000000000   -- Rp3,000,000
        ELSE 300000000                 -- US$300.00
    END,
    updated_at = NOW();

UPDATE welcome_rewards
SET gems = 600000
WHERE state = 'PENDING';
COMMIT;
