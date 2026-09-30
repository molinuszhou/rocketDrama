#!/usr/bin/env bash
set -euo pipefail

PG_BIN="/usr/lib/postgresql/16/bin"
PG_DATA="/tmp/joyedrama-direct-cash-pg"
PG_PORT="55433"
DB_NAME="joyedrama_direct_cash_smoke"
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

psql "postgresql://postgres@127.0.0.1:$PG_PORT/$DB_NAME" -v ON_ERROR_STOP=1 -f "$REWARDS_DIR/sql/001_init.sql" >/dev/null
cd "$REWARDS_DIR"
DATABASE_URL="postgresql://postgres@127.0.0.1:$PG_PORT/$DB_NAME" \
ALLOW_DEBUG_AD_PROOF=true \
TOPON_REWARDED_S2S_SECRET=postgres-smoke-topon-s2s-secret \
TOPON_REWARDED_PLACEMENT_ID=topon-rewarded-postgres-smoke \
ADMIN_DASHBOARD_TOKEN=smoke-admin-token \
python3 tests/rewards_postgres_smoke.py
DATABASE_URL="postgresql://postgres@127.0.0.1:$PG_PORT/$DB_NAME" \
ALLOW_DEBUG_AD_PROOF=true \
ADMIN_DASHBOARD_TOKEN=smoke-admin-token \
python3 tests/v163_runtime_upgrade_smoke.py
