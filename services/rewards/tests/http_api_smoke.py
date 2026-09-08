from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE_URL = os.environ.get("REWARDS_TEST_BASE_URL", "http://127.0.0.1:8110").rstrip("/")
USER_ID = f"http-smoke-{uuid.uuid4()}"


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

    _, welcome = post("/v2/welcome/claim", {"user_id": USER_ID})
    assert welcome["data"]["result"]["action"] == "WELCOME_BONUS"
    assert welcome["data"]["snapshot"]["welcome"]["claimed"] is True

    _, exchange = post(
        "/v2/wallet/exchange",
        {"user_id": USER_ID, "gems": 10000, "reference_id": event_id("exchange")},
    )
    assert exchange["data"]["result"]["action"] == "GEMS_EXCHANGE"
    assert float(exchange["data"]["snapshot"]["wallet"]["wallet_locked"]) > 0

    # 前三集为免费体验，服务端拒绝将其计为集间通行激励广告。
    _, free_rejection = post(
        "/v2/ads/rewarded/complete",
        {
            "user_id": USER_ID,
            "ad_event_id": event_id("free"),
            "placement_id": "rv_episode_pass",
            "target_id": "episode-3",
            "episode_index": 3,
            "proof": "TEST_PANGLE_REWARD",
        },
        expected_status=409,
    )
    assert free_rejection["code"] == 1100

    _, passed = post(
        "/v2/ads/rewarded/complete",
        {
            "user_id": USER_ID,
            "ad_event_id": event_id("pass"),
            "placement_id": "rv_episode_pass",
            "target_id": "episode-4",
            "episode_index": 4,
            "proof": "TEST_PANGLE_REWARD",
        },
    )
    assert passed["code"] == 0 and passed["data"]["result"]["action"] == "EPISODE_PASS"

    _, watched = post(
        "/v2/watch-events",
        {
            "user_id": USER_ID,
            "event_id": event_id("watch"),
            "drama_id": "http-demo-drama",
            "episode_id": "episode-4",
            "watched_seconds": 60,
        },
    )
    assert watched["data"]["result"]["round_completed"] is True
    assert watched["data"]["snapshot"]["today"]["completed_rounds"] == 1
    print("HTTP API v3 smoke test passed; welcome, exchange, free-episode protection and episode-pass settlement verified")


if __name__ == "__main__":
    run()
