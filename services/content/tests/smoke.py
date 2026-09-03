from pathlib import Path
import sys

from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.main import app

client = TestClient(app)


def expect(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> None:
    health = client.get("/health")
    expect(health.status_code == 200, "health endpoint failed")

    feed = client.get("/v1/feed", params={"region": "SG", "limit": 20})
    expect(feed.status_code == 200, f"feed failed: {feed.text}")
    feed_body = feed.json()
    expect(any(item["source"] == "FREE_DRAMA" for item in feed_body["items"]), "free drama missing from feed")

    unavailable_feed = client.get("/v1/feed", params={"region": "CN", "limit": 20})
    expect(unavailable_feed.status_code == 200, "regional feed should return valid empty response")
    expect(unavailable_feed.json()["items"] == [], "CN should not receive sample overseas free drama")

    detail = client.get("/v1/dramas/free:shadow-contract", params={"region": "SG", "user_id": "u-100"})
    expect(detail.status_code == 200, f"detail failed: {detail.text}")
    episodes = detail.json()["episodes"]
    expect(episodes[0]["entitlement"] == "FREE", "first episode should be free")
    expect(episodes[2]["entitlement"] == "AD_UNLOCK", "third episode should require ad unlock")

    locked_playback = client.post(
        "/v1/episodes/free:shadow-contract:ep:3/playback-session",
        json={"region": "SG", "user_id": "u-100", "device_id": "d-100"},
    )
    expect(locked_playback.status_code == 402, "locked episode should reject playback before unlock")

    unlock = client.post(
        "/v1/episodes/free:shadow-contract:ep:3/unlock",
        json={"region": "SG", "user_id": "u-100", "method": "AD_REWARD", "proof": "TEST_REWARD_PROOF"},
    )
    expect(unlock.status_code == 200, f"unlock failed: {unlock.text}")
    expect(unlock.json()["unlocked"] is True, "unlock response should confirm success")

    playback = client.post(
        "/v1/episodes/free:shadow-contract:ep:3/playback-session",
        json={"region": "SG", "user_id": "u-100", "device_id": "d-100"},
    )
    expect(playback.status_code == 200, f"playback failed after unlock: {playback.text}")
    expect(playback.json()["playback_mode"] == "SIGNED_URL", "free drama should use signed URL mode")

    providers = client.get("/v1/providers/health")
    expect(providers.status_code == 200, "provider health failed")
    pangle = next(item for item in providers.json() if item["provider"] == "PANGLE_DRAMA")
    expect(pangle["enabled"] is False, "Pangle content must remain disabled without a content SDK")

    print("SMOKE TEST PASSED: feed, region, detail, unlock, playback and provider boundary verified")


if __name__ == "__main__":
    main()
