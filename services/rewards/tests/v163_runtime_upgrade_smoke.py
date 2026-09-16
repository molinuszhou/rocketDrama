from __future__ import annotations

import sys
import uuid
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from fastapi.testclient import TestClient

from app.config import Settings
from app.migrations import apply_runtime_migrations
from app.service import RewardsService


def run() -> None:
    settings = Settings.from_environment()
    apply_runtime_migrations(settings)
    service = RewardsService(settings)

    with service._transaction() as connection:  # noqa: SLF001 - upgrade verification needs direct migration marker access
        marker = service._one(  # noqa: SLF001
            connection,
            "SELECT version FROM schema_migrations WHERE version = '010_v163_localized_wallet_and_rewards'",
        )
        assert marker is not None
        columns = service._all(  # noqa: SLF001
            connection,
            """
            SELECT column_name FROM information_schema.columns
            WHERE table_name = 'reward_policy'
              AND column_name IN ('gem_damping_tier3_threshold', 'gem_damping_tier4_multiplier')
            ORDER BY column_name
            """,
        )
        assert {row["column_name"] for row in columns} == {
            "gem_damping_tier3_threshold",
            "gem_damping_tier4_multiplier",
        }

    user_id = f"v163-runtime-br-{uuid.uuid4()}"
    snapshot = service.bootstrap(user_id, "pt-BR")
    assert snapshot["user"]["market_code"] == "BR"
    assert snapshot["wallet"]["currency"]["code"] == "BRL"
    assert snapshot["gem_damping"]["scope"] == "ALL_GEM_SOURCES"
    assert snapshot["interstitial"]["mid_episode_trigger_seconds"] == 45

    updated = service.update_market_wallet_policy(
        "Bearer smoke-admin-token",
        {
            "market_code": "BR",
            "currency_code": "BRL",
            "currency_symbol": "R$",
            "currency_fraction_digits": 2,
            "currency_micros_per_10000_gems": 3_000_000,
            "usd_micros_per_10000_gems": 1_000_000,
            "withdrawal_threshold_micros": 3_000_000,
            "welcome_bonus_gems": 200_000,
            "daily_release_micros": 60_000,
        },
    )
    br = next(item for item in updated["market_wallet_policies"] if item["market_code"] == "BR")
    assert br["currency_micros_per_10000_gems"] == 3_000_000

    # Android 已在 bootstrap 请求中发送 region；HTTP 契约应无 422 并返回匹配市场的本币元数据。
    from app.main import app
    with TestClient(app) as client:
        response = client.post("/v2/bootstrap", json={"user_id": f"v163-http-br-{uuid.uuid4()}", "region": "BR"})
        assert response.status_code == 200
        data = response.json()["data"]
        assert data["wallet"]["currency"]["code"] == "BRL"
        assert data["wallet"]["exchange_rate_gems"] == 10_000
        assert data["interstitial"]["mid_episode_trigger_seconds"] == 45

    print("PostgreSQL v1.6.3 runtime-upgrade smoke test passed; incremental migration and market-wallet admin update verified")


if __name__ == "__main__":
    run()
