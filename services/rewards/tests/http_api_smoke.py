from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
import uuid

BASE_URL = os.environ.get("REWARDS_TEST_BASE_URL", "http://127.0.0.1:8110").rstrip("/")
USER_ID = f"http-v4-smoke-{uuid.uuid4()}"


def post(path: str, payload: dict, expected_status: int = 200) -> tuple[int, dict]:
    request = urllib.request.Request(
        BASE_URL + path,
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            status = response.status
            body = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        status = error.code
        body = json.loads(error.read().decode("utf-8"))
    assert status == expected_status, (path, status, body)
    return status, body


def event_id(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4()}"


def run() -> None:
    _, initial = post("/v2/bootstrap", {"user_id": USER_ID})
    assert initial["data"]["welcome"]["available"] is True
    assert initial["data"]["checkin"]["today_streak"] == 1
    assert initial["data"]["episode_unlock"]["free_episodes_count"] == 3
    assert initial["data"]["episode_unlock"]["placement_id"] == "int_next_episode_unlock"
    assert initial["data"]["interstitial"]["pause_ad_enabled"] is True

    # 下一集插屏解锁与暂停原生广告的频控事件采用独立 placement。
    _, next_interstitial = post(
        "/v2/interstitials/shown",
        {"user_id": USER_ID, "event_id": event_id("next-interstitial"), "placement_id": "int_next_episode_unlock"},
    )
    assert next_interstitial["data"]["idempotent"] is False
    _, pause_native = post(
        "/v2/interstitials/shown",
        {"user_id": USER_ID, "event_id": event_id("pause-native"), "placement_id": "int_pause_native"},
    )
    assert pause_native["data"]["idempotent"] is False

    # 前 3 集免费；服务端拒绝把免费集广告误记为内容解锁。
    _, free_rejection = post(
        "/v2/ads/rewarded/complete",
        {"user_id": USER_ID, "ad_event_id": event_id("free"), "placement_id": "rv_episode_pass", "target_id": "episode-3", "episode_index": 3, "proof": "TEST_PANGLE_REWARD"},
        expected_status=409,
    )
    assert free_rejection["code"] == 1100

    # 第 4 集广告只提供内容通行权益；看完后才形成待领取宝石。
    _, passed = post(
        "/v2/ads/rewarded/complete",
        {"user_id": USER_ID, "ad_event_id": event_id("pass"), "placement_id": "rv_episode_pass", "target_id": "episode-4", "episode_index": 4, "proof": "TEST_PANGLE_REWARD"},
    )
    assert passed["data"]["result"]["action"] == "EPISODE_PASS"

    for episode_index in range(1, 11):
        _, watched = post(
            "/v2/watch-events",
            {"user_id": USER_ID, "event_id": event_id("watch"), "drama_id": "http-demo-drama", "episode_id": f"episode-{episode_index}", "watched_seconds": 60},
        )
        assert watched["data"]["result"]["gems_added"] == 0
        assert watched["data"]["result"]["episode_reward"]["gems"] > 0

    snapshot = watched["data"]["snapshot"]
    assert len(snapshot["episode_reward_claims"]) == 10
    assert snapshot["ring"]["claimable"] is True
    assert snapshot["ring"]["reward_gems"] > 0
    assert len(snapshot["pending_claims"]) == 1

    _, claimed_episode = post(
        "/v2/episode-rewards/claim",
        {"user_id": USER_ID, "episode_id": "episode-1", "reference_id": event_id("episode-claim")},
    )
    assert claimed_episode["data"]["result"]["action"] == "EPISODE_REWARD_CLAIM"
    base_gems = claimed_episode["data"]["result"]["gems_added"]

    _, doubled_episode = post(
        "/v2/ads/rewarded/complete",
        {"user_id": USER_ID, "ad_event_id": event_id("episode-double"), "placement_id": "rv_episode_reward_double", "target_id": "episode-1", "proof": "TEST_PANGLE_REWARD"},
    )
    assert doubled_episode["data"]["result"]["gems_added"] == base_gems

    _, claimed_ten_round = post(
        "/v2/ten-round-rewards/claim",
        {"user_id": USER_ID, "cycle_number": 1, "reference_id": event_id("ten-round-claim")},
    )
    assert claimed_ten_round["data"]["result"]["action"] == "TEN_ROUND_CLAIM"
    print("HTTP API v4 smoke test passed; unlock, per-episode claim/double and cumulative ten-round reward verified")


if __name__ == "__main__":
    run()
