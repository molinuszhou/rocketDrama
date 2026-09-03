from __future__ import annotations

import os
import sys
import uuid
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from app.config import Settings
from app.service import RewardsService


def event_id(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4()}"


def run() -> None:
    settings = Settings.from_environment()
    service = RewardsService(settings)
    user_id = f"postgres-smoke-{uuid.uuid4()}"

    initial = service.bootstrap(user_id)
    assert initial["wallet"]["gems_balance"] == 0
    assert initial["checkin"]["signed_today"] is False

    checkin = service.checkin(user_id)
    assert checkin["result"]["gems_added"] == 500
    assert checkin["snapshot"]["checkin"]["signed_today"] is True

    signin_double_id = event_id("signin-double")
    signin_double = service.complete_rewarded_ad(
        user_id=user_id,
        ad_event_id=signin_double_id,
        placement_id="rv_signin_double",
        target_id=None,
        episode_index=None,
        proof="TEST_PANGLE_REWARD",
        timestamp_ms=None,
    )
    assert signin_double["result"]["gems_added"] == 500
    duplicate_signin_double = service.complete_rewarded_ad(
        user_id=user_id,
        ad_event_id=signin_double_id,
        placement_id="rv_signin_double",
        target_id=None,
        episode_index=None,
        proof="TEST_PANGLE_REWARD",
        timestamp_ms=None,
    )
    assert duplicate_signin_double["idempotent"] is True

    chest = service.complete_rewarded_ad(
        user_id=user_id,
        ad_event_id=event_id("chest"),
        placement_id="rv_chest_task",
        target_id=None,
        episode_index=None,
        proof="TEST_PANGLE_REWARD",
        timestamp_ms=None,
    )
    assert chest["result"]["gems_added"] == 2000

    total_watch_gems = 0
    for round_index in range(1, 11):
        episode_id = f"owned:smoke:ep:{round_index + 3}"
        service.complete_rewarded_ad(
            user_id=user_id,
            ad_event_id=event_id("episode-pass"),
            placement_id="rv_episode_pass",
            target_id=episode_id,
            episode_index=round_index + 3,
            proof="TEST_PANGLE_REWARD",
            timestamp_ms=None,
        )
        watch = service.record_watch_event(
            user_id=user_id,
            event_id=event_id("watch"),
            drama_id="owned:smoke",
            episode_id=episode_id,
            watched_seconds=60,
        )
        total_watch_gems += watch["result"]["gems_added"]

    after_rounds = service.bootstrap(user_id)
    assert after_rounds["today"]["completed_rounds"] == 10
    assert len(after_rounds["pending_claims"]) == 1
    assert after_rounds["pending_claims"][0]["base_reward_gems"] == total_watch_gems

    claimed = service.complete_rewarded_ad(
        user_id=user_id,
        ad_event_id=event_id("claim"),
        placement_id="rv_claim_reward",
        target_id=None,
        episode_index=None,
        proof="TEST_PANGLE_REWARD",
        timestamp_ms=None,
    )
    assert claimed["result"]["gems_added"] == total_watch_gems
    doubled = service.complete_rewarded_ad(
        user_id=user_id,
        ad_event_id=event_id("double"),
        placement_id="rv_double_reward",
        target_id=None,
        episode_index=None,
        proof="TEST_PANGLE_REWARD",
        timestamp_ms=None,
    )
    assert doubled["result"]["gems_added"] == total_watch_gems

    final = service.bootstrap(user_id)
    expected = 500 + 500 + 2000 + total_watch_gems * 3
    assert final["wallet"]["gems_balance"] == expected
    assert final["today"]["rv_count"] == 14
    assert final["pending_claims"][0]["state"] == "DOUBLED"
    assert final["withdrawal"]["enabled"] is False
    assert final["wallet"]["cash_wallet"] == f"{expected / 150000:.2f}"
    print(f"PostgreSQL rewards smoke test passed; gems={expected}; round_reward_sum={total_watch_gems}")


if __name__ == "__main__":
    run()
