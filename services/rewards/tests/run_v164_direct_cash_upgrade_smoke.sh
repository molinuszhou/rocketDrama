#!/usr/bin/env bash
set -euo pipefail

PG_BIN="/usr/lib/postgresql/16/bin"
PG_DATA="/tmp/joyedrama-direct-cash-upgrade-pg"
PG_PORT="55435"
DB_NAME="joyedrama_direct_cash_upgrade_smoke"
REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
REWARDS_DIR="$REPO_ROOT/services/rewards"

cleanup() {
  if sudo -u postgres "$PG_BIN/pg_ctl" -D "$PG_DATA" status >/dev/null 2>&1; then
    sudo -u postgres "$PG_BIN/pg_ctl" -D "$PG_DATA" -m fast stop >/dev/null
  fi
}
trap cleanup EXIT

sudo rm -rf "$PG_DATA"
sudo -u postgres "$PG_BIN/initdb" -D "$PG_DATA" --auth-host=trust --auth-local=trust --encoding=UTF8 --locale=C.UTF-8 >/dev/null
sudo -u postgres "$PG_BIN/pg_ctl" -D "$PG_DATA" -l "$PG_DATA/postgres.log" -o "-p $PG_PORT -h 127.0.0.1" -w start >/dev/null
sudo -u postgres "$PG_BIN/createdb" -h 127.0.0.1 -p "$PG_PORT" "$DB_NAME"

for migration in "$REWARDS_DIR"/sql/{001_init.sql,002_monetization_v3.sql,003_rewards_v4.sql,004_ad_flow_v5.sql,005_random_chest_and_reward_v6.sql,006_rewarded_actions_and_player_v7.sql,007_v8_player_reward_polish.sql,008_v81_ad_fatigue_and_crown_curve.sql,009_v162_reward_control.sql,010_v163_localized_wallet_and_rewards.sql,011_v163_round_gem_values.sql,012_v163_round_gem_values_v2.sql,013_v163_damping_thresholds_v3.sql,014_market_wallet_policy_management.sql,015_app_review_reward.sql,016_crown_road_effective_rounds.sql,017_v1635_checkin_200k_five_tier_damping.sql,018_low_frequency_ad_policy.sql}; do
  psql "postgresql://postgres@127.0.0.1:$PG_PORT/$DB_NAME" -v ON_ERROR_STOP=1 -f "$migration" >/dev/null
done

cd "$REWARDS_DIR"
DATABASE_URL="postgresql://postgres@127.0.0.1:$PG_PORT/$DB_NAME" \
ALLOW_DEBUG_AD_PROOF=true \
ADMIN_DASHBOARD_TOKEN=smoke-admin-token \
python3 tests/v164_direct_cash_upgrade_smoke.py
