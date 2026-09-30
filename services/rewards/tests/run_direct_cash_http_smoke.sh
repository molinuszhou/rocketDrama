#!/usr/bin/env bash
set -euo pipefail

PG_BIN="/usr/lib/postgresql/16/bin"
PG_DATA="/tmp/joyedrama-direct-cash-http-pg"
PG_PORT="55434"
HTTP_PORT="8111"
DB_NAME="joyedrama_direct_cash_http_smoke"
REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
REWARDS_DIR="$REPO_ROOT/services/rewards"
API_PID=""

cleanup() {
  if [[ -n "$API_PID" ]] && kill -0 "$API_PID" 2>/dev/null; then
    kill "$API_PID" 2>/dev/null || true
    wait "$API_PID" 2>/dev/null || true
  fi
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
TOPON_REWARDED_S2S_SECRET=http-smoke-topon-s2s-secret \
TOPON_REWARDED_PLACEMENT_ID=topon-rewarded-http-smoke \
ADMIN_DASHBOARD_TOKEN=smoke-admin-token \
uvicorn app.main:app --host 127.0.0.1 --port "$HTTP_PORT" > /tmp/joyedrama-direct-cash-http.log 2>&1 &
API_PID=$!
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:$HTTP_PORT/health" >/dev/null; then
    break
  fi
  sleep 1
done
curl -fsS "http://127.0.0.1:$HTTP_PORT/health" >/dev/null
REWARDS_TEST_BASE_URL="http://127.0.0.1:$HTTP_PORT" \
REWARDS_TEST_ADMIN_TOKEN=smoke-admin-token \
python3 tests/http_api_smoke.py
