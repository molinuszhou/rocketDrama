from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
import uuid

BASE_URL = os.environ.get("REWARDS_TEST_BASE_URL", "http://127.0.0.1:8110").rstrip("/")
ADMIN_TOKEN = os.environ.get("REWARDS_TEST_ADMIN_TOKEN", "")
USER_ID = f"http-v6-smoke-{uuid.uuid4()}"


def request(method: str, path: str, payload: dict | None = None, expected_status: int = 200, admin: bool = False) -> tuple[int, dict]:
    headers = {"Content-Type": "application/json"}
    if admin:
        headers["Authorization"] = f"Bearer {ADMIN_TOKEN}"
    body = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(BASE_URL + path, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            status = response.status
            parsed = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        status = error.code
        parsed = json.loads(error.read().decode("utf-8"))
    assert status == expected_status, (path, status, parsed)
    return status, parsed


def post(path: str, payload: dict, expected_status: int = 200) -> tuple[int, dict]:
    return request("POST", path, payload, expected_status)


def event_id(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4()}"


def rewarded(placement: str, target_id: str | None = None) -> tuple[int, dict]:
    payload = {"user_id": USER_ID, "ad_event_id": event_id(placement), "placement_id": placement, "proof": "TEST_PANGLE_REWARD"}
    if target_id is not None:
        payload["target_id"] = target_id
    return post("/v2/ads/rewarded/complete", payload)


def run() -> None:
    _, initial = post("/v2/bootstrap", {"user_id": USER_ID})
    snapshot = initial["data"]
    assert snapshot["welcome"]["available"] is True
    assert snapshot["episode_unlock"]["free_episodes_count"] == 3
    assert snapshot["episode_unlock"]["extra_episode_unlock_enabled"] is False
    assert snapshot["chest"]["available_count"] == 1
    assert snapshot["interstitial"]["mid_episode_enabled"] is True
    assert snapshot["interstitial"]["seek_enabled"] is True

    for placement in ("int_next_episode_unlock", "int_episode_midroll", "int_seek", "int_reward_claim", "int_pause_native"):
        _, shown = post("/v2/interstitials/shown", {"user_id": USER_ID, "event_id": event_id(placement), "placement_id": placement})
        assert shown["data"]["idempotent"] is False

    # 前 3 集免费；服务端拒绝将免费集广告写成内容通行权益。
    _, free_rejection = post(
        "/v2/ads/rewarded/complete",
        {"user_id": USER_ID, "ad_event_id": event_id("free"), "placement_id": "rv_episode_pass", "target_id": "episode-3", "episode_index": 3, "proof": "TEST_PANGLE_REWARD"},
        expected_status=409,
    )
    assert free_rejection["code"] == 1100

    base_rewards: list[int] = []
    for episode_index in range(1, 4):
        _, watched = post(
            "/v2/watch-events",
            {"user_id": USER_ID, "event_id": event_id("watch"), "drama_id": "http-demo-drama", "episode_id": f"episode-{episode_index}", "watched_seconds": 60},
        )
        reward = watched["data"]["result"]["episode_reward"]
        assert reward["gems"] > 0
        base_rewards.append(reward["gems"])

    _, boosted = rewarded("rv_episode_reward_boost", "episode-1")
    assert boosted["data"]["result"]["action"] == "EPISODE_REWARD_BOOST"
    assert boosted["data"]["snapshot"]["ring"]["reward_gems"] == sum(base_rewards) + base_rewards[0]

    _, claimed = post("/v2/episode-rewards/claim-all", {"user_id": USER_ID, "reference_id": event_id("claim-all")})
    assert claimed["data"]["result"]["action"] == "EPISODE_REWARDS_CLAIM_ALL"
    assert claimed["data"]["result"]["claimed_count"] == 3
    assert claimed["data"]["result"]["boosted_count"] == 1

    available = claimed["data"]["snapshot"]["chest"]["available_count"]
    assert available == 2
    for _ in range(available):
        _, opened = rewarded("rv_random_chest_open")
        amount = opened["data"]["result"]["gems_added"]
        assert snapshot["chest"]["min_gems"] <= amount <= snapshot["chest"]["max_gems"]
    _, empty_chest = rewarded("rv_random_chest_open") if False else post(
        "/v2/ads/rewarded/complete",
        {"user_id": USER_ID, "ad_event_id": event_id("empty-chest"), "placement_id": "rv_random_chest_open", "proof": "TEST_PANGLE_REWARD"},
        expected_status=409,
    )
    assert empty_chest["code"] == 1303

    if ADMIN_TOKEN:
        _, policy = request("GET", "/v2/admin/policy", admin=True)
        assert len(policy["data"]["crown_multipliers"]) == 5
        _, updated = request("PUT", "/v2/admin/policy/operating", {"random_chest_min_gems": 400, "random_chest_max_gems": 900}, admin=True)
        assert updated["data"]["random_chest_min_gems"] == 400
        assert updated["data"]["random_chest_max_gems"] == 900
        _, crown = request("PUT", "/v2/admin/crown-multiplier", {"code": "BRONZE", "episode_reward_multiplier": 1.20}, admin=True)
        assert crown["data"]["crown_multipliers"][0]["episode_reward_multiplier"] == 1.2

    print("HTTP API v6 smoke test passed; random chests, claim-all, pre-claim boost and new ad placements verified")


if __name__ == "__main__":
    run()
