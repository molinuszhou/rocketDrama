BEGIN;
-- v1.7.1.4: v1.6.4.9 previously deleted HK policy rows. Restore a disabled
-- compatibility row when upgrading an existing database so legacy users can
-- still resolve their wallet. New users remain on DEFAULT/BR/ID only.
-- The compatibility row uses the current USD-default policy because the former
-- HK local-currency settings are no longer part of the product configuration.
INSERT INTO market_wallet_policy (
    market_code, currency_code, currency_symbol, currency_fraction_digits,
    currency_micros_per_10000_gems, usd_micros_per_10000_gems,
    withdrawal_threshold_micros, welcome_bonus_gems, daily_release_micros,
    enabled, disabled_at
)
SELECT
    'HK', currency_code, currency_symbol, currency_fraction_digits,
    currency_micros_per_10000_gems, usd_micros_per_10000_gems,
    withdrawal_threshold_micros, welcome_bonus_gems, daily_release_micros,
    FALSE, NOW()
FROM market_wallet_policy
WHERE market_code = 'DEFAULT'
ON CONFLICT (market_code) DO UPDATE SET
    enabled = FALSE,
    disabled_at = COALESCE(market_wallet_policy.disabled_at, NOW()),
    updated_at = NOW();
COMMIT;
