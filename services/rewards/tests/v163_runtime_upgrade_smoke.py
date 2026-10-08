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
        withdrawal_marker = service._one(  # noqa: SLF001
            connection,
            "SELECT version FROM schema_migrations WHERE version = '040_v1711_withdrawal_accounts'",
        )
        assert withdrawal_marker is not None
        ad_unavailable_marker = service._one(  # noqa: SLF001
            connection,
            "SELECT version FROM schema_migrations WHERE version = '041_v1712_ad_unavailable_episode_unlocks'",
        )
        assert ad_unavailable_marker is not None

    user_id = f"v163-runtime-br-{uuid.uuid4()}"
    snapshot = service.bootstrap(user_id, "pt-BR")
    assert snapshot["user"]["market_code"] == "BR"
    assert snapshot["wallet"]["currency"]["code"] == "BRL"
    assert snapshot["wallet"]["cash_mode"] is True
    assert float(snapshot["welcome"]["cash"]) > 0
    assert snapshot["review"]["available"] is False
    assert snapshot["gem_damping"]["scope"] == "ALL_GEM_SOURCES"
    assert snapshot["gem_damping"]["tier5_threshold"] > 0
    assert 0 < snapshot["gem_damping"]["tier6_multiplier"] <= 1
    assert snapshot["interstitial"]["mid_episode_trigger_seconds"] > 0
    assert snapshot["interstitial"]["mid_episode_repeat_seconds"] > 0
    assert snapshot["interstitial"]["cooldown_seconds"] >= 0
    assert snapshot["ring"]["claim_prompt_threshold_gems"] > 0
    assert snapshot["interstitial"]["seek_enabled"] is False
    assert snapshot["interstitial"]["speed_enabled"] is False
    assert snapshot["crown_progress"][0]["episode_progress_points"] > 0
    assert snapshot["crown_progress"][0]["rewarded_ad_progress_points"] > 0
    assert snapshot["wallet"]["release"]["charge_cooldown_seconds"] > 0
    assert snapshot["pending_claims"][-1]["target"] >= 18
    assert snapshot["pending_claims"][0]["reward_min_gems"] > 0

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

        unlock_user = f"v1712-ad-unavailable-{uuid.uuid4()}"
        for episode_index in range(1, 4):
            allowed = client.post(
                "/v2/episodes/ad-unavailable-unlock",
                json={"user_id": unlock_user, "episode_id": f"episode-{episode_index}", "episode_index": episode_index},
            )
            assert allowed.status_code == 200
            assert allowed.json()["data"]["daily_used"] == episode_index
        duplicate = client.post(
            "/v2/episodes/ad-unavailable-unlock",
            json={"user_id": unlock_user, "episode_id": "episode-3", "episode_index": 3},
        )
        assert duplicate.status_code == 200
        assert duplicate.json()["data"]["idempotent"] is True
        capped = client.post(
            "/v2/episodes/ad-unavailable-unlock",
            json={"user_id": unlock_user, "episode_id": "episode-4", "episode_index": 4},
        )
        assert capped.status_code == 429

        attribution_user = f"v1707-http-attribution-{uuid.uuid4()}"
        attribution_response = client.post(
            "/v2/attribution",
            json={
                "user_id": attribution_user,
                "appsflyer_uid": "af-http-smoke",
                "af_status": "Non-organic",
                "media_source": "googleadwords_int",
                "campaign": "launch",
                "channel": "paid-search",
            },
        )
        assert attribution_response.status_code == 200
        assert attribution_response.json()["data"]["attribution"]["surface"] == "B"
        bootstrap_with_attribution = client.post(
            "/v2/bootstrap",
            json={
                "user_id": attribution_user,
                "attribution": {"af_status": "Organic", "media_source": "af_organic"},
            },
        )
        assert bootstrap_with_attribution.status_code == 200
        stored = bootstrap_with_attribution.json()["data"]["user"]["attribution"]
        assert stored["surface"] == "A"
        assert stored["campaign"] == "launch"

        withdrawal_user = f"v1711-http-withdrawal-{uuid.uuid4()}"
        account_response = client.post(
            "/v2/withdrawals/account",
            json={
                "user_id": withdrawal_user,
                "provider": "PAYPAL",
                "account_identifier": "withdrawal-smoke@example.com",
            },
        )
        assert account_response.status_code == 200
        assert account_response.json()["data"]["linked"] is True
        withdrawal_snapshot = client.post(
            "/v2/bootstrap",
            json={"user_id": withdrawal_user, "region": "US"},
        )
        assert withdrawal_snapshot.status_code == 200
        withdrawal = withdrawal_snapshot.json()["data"]["withdrawal"]
        assert withdrawal["account_linked"] is True
        assert withdrawal["account_provider"] == "PAYPAL"
        assert "account_identifier" not in withdrawal

        admin_headers = {"Authorization": "Bearer smoke-admin-token"}
        user_rows = client.get(
            "/v2/admin/users",
            params={"query": attribution_user, "attribution_surface": "A"},
            headers=admin_headers,
        )
        assert user_rows.status_code == 200
        rows_payload = user_rows.json()["data"]
        assert rows_payload["total"] == 1
        assert rows_payload["rows"][0]["attribution"]["campaign"] == "launch"
        assert rows_payload["rows"][0]["attribution"]["appsflyer_uid"] == "af-http-smoke"

        reporting_event = client.post(
            "/v2/operations/reporting-delivery-events",
            json={
                "client_event_id": f"reporting-http-{uuid.uuid4()}",
                "user_uid": "user-uid-reporting-smoke",
                "install_id_hash": "e" * 64,
                "provider": "AppsFlyer",
                "event_name": "ad_impression",
                "status": "SUCCESS",
                "logical_placement": "int_episode_midroll",
                "ad_unit_id": "topon-placement",
                "currency": "USD",
                "ecpm": 12.5,
                "value": 0.0125,
                "occurred_at_ms": int(__import__("time").time() * 1000),
            },
        )
        assert reporting_event.status_code == 200
        reporting_rows = client.get(
            "/v2/admin/operations/reporting-delivery",
            headers=admin_headers,
        )
        assert reporting_rows.status_code == 200
        report_payload = reporting_rows.json()["data"]
        assert report_payload["totals"]["success_count"] >= 1
        assert any(row["provider"] == "AppsFlyer" for row in report_payload["rows"])
        assert any(row["user_uid"] == "user-uid-reporting-smoke" for row in report_payload["rows"])

        ad_event = client.post(
            "/v2/operations/ad-mediation-events",
            json={
                "client_event_id": f"mediation-http-{uuid.uuid4()}",
                "install_id_hash": "f" * 64,
                "event_type": "PAID_REVENUE",
                "logical_placement": "int_episode_midroll",
                "topon_placement_id": "topon-placement",
                "ad_format": "interstitial",
                "ad_network": "MAX > Google AdMob",
                "ad_source_id": "admob-unit",
                "app_version": "1.7.1.16",
                "currency": "USD",
                "ecpm": 12.5,
                "occurred_at_ms": int(__import__("time").time() * 1000),
            },
        )
        assert ad_event.status_code == 200
        ad_rows = client.get("/v2/admin/operations/ad-mediation-daily", headers=admin_headers)
        assert ad_rows.status_code == 200
        assert any(row["event_type"] == "PAID_REVENUE" for row in ad_rows.json()["data"]["recent_events"])

    print("PostgreSQL runtime-upgrade smoke test passed; fallback settlement, withdrawal accounts and capped ad-unavailable episode passes verified")


if __name__ == "__main__":
    run()
