from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="dramaflow-rewards-") as temp_dir:
        runtime = Path(temp_dir)
        os.environ.update(
            {
                "ENVIRONMENT": "development",
                "REWARD_RULES_PATH": str(runtime / "reward_rules.json"),
                "REWARD_LEDGER_PATH": str(runtime / "reward_ledger.sqlite3"),
                "ADMIN_CONFIG_TOKEN": "test-admin-token",
                "LOCAL_MEDIA_ENABLED": "false",
            }
        )
        sys.modules.pop("app.main", None)
        from fastapi.testclient import TestClient
        from app.main import app

        client = TestClient(app)
        user_id = "reward-test-user"
        bootstrap = client.post("/v1/rewards/bootstrap", json={"user_id": user_id, "region": "SG"})
        assert bootstrap.status_code == 200, bootstrap.text
        assert bootstrap.json()["rule_version"] == "development-v3-tiered-episode-rewards"
        assert len(bootstrap.json()["tasks"]) == 3
        assert all(item["type"] == "watch_episode" for item in bootstrap.json()["tasks"])

        for index in range(1, 4):
            event = client.post(
                "/v1/rewards/events",
                json={
                    "event_id": f"watch-event-{index:04d}",
                    "user_id": user_id,
                    "type": "watch_episode",
                    "drama_id": "demo-drama",
                    "episode_id": f"demo-drama:{index}",
                    "valid_watch_seconds": 60,
                },
            )
            assert event.status_code == 200, event.text

        after_watch = client.post("/v1/rewards/bootstrap", json={"user_id": user_id, "region": "SG"})
        task = next(item for item in after_watch.json()["tasks"] if item["id"] == "watch_3_episodes_daily")
        assert task["status"] == "CLAIMABLE", task
        fifth_tier = next(item for item in after_watch.json()["tasks"] if item["id"] == "watch_5_episodes_daily")
        assert fifth_tier["progress"] == 3 and fifth_tier["status"] == "IN_PROGRESS", fifth_tier

        ad_claim = client.post(
            "/v1/rewards/ad-claims",
            json={
                "request_id": "task-double-request-0001",
                "user_id": user_id,
                "purpose": "task_double",
                "target_id": "watch_3_episodes_daily",
                "proof": "TEST_PANGLE_REWARD",
            },
        )
        assert ad_claim.status_code == 200, ad_claim.text
        token = ad_claim.json()["ad_token"]
        doubled_claim = client.post(
            "/v1/rewards/claims",
            json={
                "claim_id": "task-claim-0001",
                "user_id": user_id,
                "type": "task",
                "target_id": "watch_3_episodes_daily",
                "mode": "ad_double",
                "ad_token": token,
            },
        )
        assert doubled_claim.status_code == 200, doubled_claim.text
        assert doubled_claim.json()["wallet"]["coins"] == 120

        duplicate_claim = client.post(
            "/v1/rewards/claims",
            json={
                "claim_id": "task-claim-0001",
                "user_id": user_id,
                "type": "task",
                "target_id": "watch_3_episodes_daily",
                "mode": "ad_double",
                "ad_token": token,
            },
        )
        assert duplicate_claim.status_code == 200, duplicate_claim.text
        assert duplicate_claim.json()["wallet"]["coins"] == 120

        unlock_ad = client.post(
            "/v1/rewards/ad-claims",
            json={
                "request_id": "unlock-request-0001",
                "user_id": user_id,
                "purpose": "episode_unlock",
                "target_id": "demo-drama:2",
                "proof": "TEST_PANGLE_REWARD",
            },
        )
        assert unlock_ad.status_code == 200, unlock_ad.text
        unlock = client.post(
            "/v1/rewards/claims",
            json={
                "claim_id": "unlock-claim-0001",
                "user_id": user_id,
                "type": "episode_unlock",
                "target_id": "demo-drama:2",
                "ad_token": unlock_ad.json()["ad_token"],
            },
        )
        assert unlock.status_code == 200, unlock.text
        assert "demo-drama:2" in unlock.json()["unlocked_episode_targets"]
        ledger = client.get("/v1/rewards/ledger", params={"user_id": user_id})
        assert ledger.status_code == 200, ledger.text
        assert any(item["kind"] == "episode_unlock" for item in ledger.json()["items"])

        headers = {"Authorization": "Bearer test-admin-token"}
        config = client.get("/v1/admin/rewards/config", headers=headers)
        assert config.status_code == 200, config.text
        updated = config.json()
        updated["version"] = "development-v2"
        updated["unlock"]["daily_rewarded_unlock_limit"] = 3
        replace = client.put("/v1/admin/rewards/config", headers=headers, json=updated)
        assert replace.status_code == 200, replace.text
        assert replace.json()["version"] == "development-v2"

    print("Rewards smoke test passed: bootstrap, progress, ad token, claim, unlock, ledger, admin config")


if __name__ == "__main__":
    main()
