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
            "SELECT version FROM schema_migrations WHERE version = '026_v1656_player_reward_fallback_and_cooldowns'",
        )
        assert marker is not None
        columns = service._all(  # noqa: SLF001
            connection,
            """
            SELECT column_name FROM information_schema.columns
            WHERE table_name = 'reward_policy'
              AND column_name IN ('gem_damping_tier3_threshold', 'gem_damping_tier4_multiplier', 'gem_damping_tier5_threshold', 'gem_damping_tier6_multiplier', 'interstitial_rewarded_skip_threshold', 'episode_reward_claim_prompt_threshold_gems', 'episode_task_tier1_min_gems', 'episode_task_tier4_max_gems', 'crown_episode_progress_points', 'crown_rewarded_ad_progress_points', 'crown_rewarded_cooldown_seconds', 'release_boost_cooldown_seconds')
            ORDER BY column_name
            """,
        )
        assert {row["column_name"] for row in columns} == {
            "gem_damping_tier3_threshold",
            "gem_damping_tier4_multiplier",
            "gem_damping_tier5_threshold",
            "gem_damping_tier6_multiplier",
            "interstitial_rewarded_skip_threshold",
            "episode_reward_claim_prompt_threshold_gems",
            "episode_task_tier1_min_gems",
            "episode_task_tier4_max_gems",
            "crown_episode_progress_points",
            "crown_rewarded_ad_progress_points",
            "crown_rewarded_cooldown_seconds",
            "release_boost_cooldown_seconds",
        }

    user_id = f"v163-runtime-br-{uuid.uuid4()}"
    snapshot = service.bootstrap(user_id, "pt-BR")
    assert snapshot["user"]["market_code"] == "BR"
    assert snapshot["wallet"]["currency"]["code"] == "BRL"
    assert snapshot["wallet"]["cash_mode"] is True
    assert snapshot["welcome"]["cash"] == "60.00"
    assert snapshot["review"]["available"] is False
    assert snapshot["gem_damping"]["scope"] == "ALL_GEM_SOURCES"
    assert snapshot["gem_damping"]["tier5_threshold"] == 1_995_000
    assert snapshot["gem_damping"]["tier6_multiplier"] == 0.01
    assert snapshot["interstitial"]["mid_episode_trigger_seconds"] == 180
    assert snapshot["interstitial"]["mid_episode_repeat_seconds"] == 360
    assert snapshot["interstitial"]["cooldown_seconds"] == 60
    assert snapshot["ring"]["claim_prompt_threshold_gems"] == 50_000
    assert snapshot["interstitial"]["seek_enabled"] is False
    assert snapshot["interstitial"]["speed_enabled"] is False
    assert snapshot["crown_progress"][0]["episode_progress_points"] == 2
    assert snapshot["crown_progress"][0]["rewarded_ad_progress_points"] == 1
    assert snapshot["wallet"]["release"]["charge_cooldown_seconds"] == 60
    assert snapshot["pending_claims"][-1]["target"] == 90
    assert snapshot["pending_claims"][0]["reward_min_gems"] == 800

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
            "welcome_bonus_gems": 100_000,
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
        assert data["wallet"]["cash_mode"] is True
        assert data["wallet"]["currency"]["code"] == "BRL"
        assert data["interstitial"]["mid_episode_trigger_seconds"] == 180
        assert data["interstitial"]["mid_episode_repeat_seconds"] == 360

    print("PostgreSQL v1.6.6.2 runtime-upgrade smoke test passed; welcome consolidation, fallback settlement and 60-second charge cooldowns verified")


if __name__ == "__main__":
    run()
