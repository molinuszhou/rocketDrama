from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
import uuid

BASE_URL = os.environ.get("REWARDS_TEST_BASE_URL", "http://127.0.0.1:8110").rstrip("/")
ADMIN_TOKEN = os.environ.get("REWARDS_TEST_ADMIN_TOKEN", "")
USER_ID = f"http-v164-smoke-{uuid.uuid4()}"


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


def rewarded(placement: str, target_id: str | None = None, episode_index: int | None = None) -> tuple[int, dict, str]:
    ad_event_id = event_id(placement)
    payload = {"user_id": USER_ID, "ad_event_id": ad_event_id, "placement_id": placement, "proof": "TEST_PANGLE_REWARD"}
    if target_id is not None:
        payload["target_id"] = target_id
    if episode_index is not None:
        payload["episode_index"] = episode_index
    status, body = post("/v2/ads/rewarded/complete", payload)
    return status, body, ad_event_id


def run() -> None:
    _, initial = post("/v2/bootstrap", {"user_id": USER_ID})
    snapshot = initial["data"]
    assert "welcome" in snapshot, initial
    assert snapshot["welcome"]["available"] is True
    assert snapshot["wallet"]["cash_mode"] is True
    assert snapshot["welcome"]["cash"] == "10.00"
    assert snapshot["episode_unlock"]["free_episodes_count"] == 3
    assert snapshot["episode_unlock"]["next_episode_rewarded_enabled"] is True
    assert snapshot["chest"]["available_count"] == 1
    assert snapshot["interstitial"]["mid_episode_enabled"] is True
    assert snapshot["interstitial"]["seek_enabled"] is True
    assert snapshot["interstitial"]["speed_enabled"] is True
    assert snapshot["interstitial"]["mid_episode_trigger_seconds"] == 120
    assert snapshot["interstitial"]["cooldown_seconds"] == 60
    assert snapshot["interstitial"]["rewarded_skip_threshold"] == 3
    assert snapshot["wallet"]["release"]["boost_cap"] == 10

    # 欢迎奖励和签到均通过激励广告落账，不能直接绕过。
    _, welcome, _ = rewarded("rv_welcome_bonus")
    assert welcome["data"]["result"]["action"] == "WELCOME_BONUS"
    assert welcome["data"]["result"]["cash_added"] == "10.00"
    _, signed, _ = rewarded("rv_checkin_complete")
    assert signed["data"]["result"]["action"] == "SIGN_IN"

    # 奖励广告完成后重新开始插屏 CD；拒绝三次奖励广告后服务端记录可用的兜底触发条件。
    _, cooling = post("/v2/interstitials/shown", {"user_id": USER_ID, "event_id": event_id("cooldown"), "placement_id": "int_episode_midroll"}, expected_status=409)
    assert cooling["code"] == 2002
    for _ in range(3):
        _, skipped = post("/v2/ads/rewarded/skipped", {"user_id": USER_ID, "context": "http_unlock_declined"})
    assert skipped["data"]["snapshot"]["interstitial"]["rewarded_skip_count"] == 3

    # 前 3 集免费；第 4 集通过激励广告获得解锁权益。
    _, free_rejection = post(
        "/v2/ads/rewarded/complete",
        {"user_id": USER_ID, "ad_event_id": event_id("free"), "placement_id": "rv_episode_pass", "target_id": "episode-3", "episode_index": 3, "proof": "TEST_PANGLE_REWARD"},
        expected_status=409,
    )
    assert free_rejection["code"] == 1100
    _, unlocked, _ = rewarded("rv_episode_pass", "episode-4", 4)
    assert unlocked["data"]["result"]["action"] == "EPISODE_PASS"

    # 低频广告版本关闭单集翻倍，避免单集完成后再叠加额外激励广告位。
    _, boost_disabled = post(
        "/v2/ads/rewarded/complete",
        {"user_id": USER_ID, "ad_event_id": event_id("boost"), "placement_id": "rv_episode_reward_boost", "target_id": "episode-1", "proof": "TEST_PANGLE_REWARD"},
        expected_status=409,
    )
    assert boost_disabled["code"] == 1214

    base_rewards: list[int] = []
    for episode_index in range(1, 4):
        _, watched = post(
            "/v2/watch-events",
            {"user_id": USER_ID, "event_id": event_id("watch"), "drama_id": "http-demo-drama", "episode_id": f"episode-{episode_index}", "watched_seconds": 60},
        )
        reward = watched["data"]["result"]["episode_reward"]
        assert reward["gems"] > 0
        base_rewards.append(reward["gems"])

    _, authorization, auth_event_id = rewarded("rv_episode_rewards_claim")
    assert authorization["data"]["result"]["action"] == "EPISODE_REWARDS_CLAIM_AUTH"
    _, claimed = post("/v2/episode-rewards/claim-all", {"user_id": USER_ID, "reference_id": event_id("claim-all"), "ad_event_id": auth_event_id})
    assert claimed["data"]["result"]["action"] == "EPISODE_REWARDS_CLAIM_ALL"
    assert claimed["data"]["result"]["claimed_count"] == 3
    assert claimed["data"]["result"]["boosted_count"] == 0
    assert float(claimed["data"]["result"]["cash_added"]) > 0

    _, task_claim = post("/v2/episode-tasks/claim", {"user_id": USER_ID, "milestone_episode_count": 3, "reference_id": event_id("episode-task")})
    assert task_claim["data"]["result"]["action"] == "EPISODE_TASK_CLAIM"
    assert float(task_claim["data"]["result"]["cash_added"]) > 0

    available = claimed["data"]["snapshot"]["chest"]["available_count"]
    assert available == 1
    for _ in range(available):
        _, opened, _ = rewarded("rv_random_chest_open")
        amount = opened["data"]["result"]["gems_added"]
        assert snapshot["chest"]["min_gems"] <= amount <= snapshot["chest"]["max_gems"]

    # 皇冠广告升级：以广告前的基线计算，连续两条激励广告至少增加一集等效追剧。
    _, before_crown = post("/v2/bootstrap", {"user_id": USER_ID})
    baseline_equivalent = before_crown["data"]["crown_progress"][0]["ad_equivalent_rounds"]
    _, _, _ = rewarded("rv_crown_upgrade")
    _, crown_two, _ = rewarded("rv_crown_upgrade")
    assert crown_two["data"]["snapshot"]["crown_progress"][0]["ad_equivalent_rounds"] >= baseline_equivalent + 1

    if ADMIN_TOKEN:
        _, policy = request("GET", "/v2/admin/policy", admin=True)
        assert len(policy["data"]["crown_multipliers"]) == 5
        _, updated = request(
            "PUT",
            "/v2/admin/policy/operating",
            {
                "random_chest_min_gems": 400,
                "random_chest_max_gems": 900,
                "speed_interstitial_enabled": True,
                "speed_interstitial_hourly_limit": 0,
                "interstitial_cooldown_seconds": 30,
                "interstitial_rewarded_skip_threshold": 2,
                "crown_rewarded_ads_per_episode": 2,
                "release_boost_percent": 0.2,
                "release_boost_daily_limit": 0,
            },
            admin=True,
        )
        assert updated["data"]["random_chest_min_gems"] == 400
        assert updated["data"]["speed_interstitial_enabled"] is True
        assert updated["data"]["interstitial_cooldown_seconds"] == 30
        assert updated["data"]["interstitial_rewarded_skip_threshold"] == 2
        assert updated["data"]["crown_rewarded_ads_per_episode"] == 2
        _, crown = request("PUT", "/v2/admin/crown-multiplier", {"code": "BRONZE", "episode_reward_multiplier": 1.20}, admin=True)
        assert crown["data"]["crown_multipliers"][0]["episode_reward_multiplier"] == 1.2

    print("HTTP API v1.6.4 smoke test passed; direct cash, milestones, cooldown ads, chests, Crown Road and operating policy verified")


if __name__ == "__main__":
    run()
