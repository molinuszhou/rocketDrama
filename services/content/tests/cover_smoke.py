import json
import os
from pathlib import Path

from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[1]
cover_root = ROOT / "runtime" / "cover-smoke"
cover_root.mkdir(parents=True, exist_ok=True)
(cover_root / "cover.png").write_bytes(b"fake-png")
catalog_path = ROOT / "runtime" / "cover-smoke-catalog.json"
catalog_path.write_text(json.dumps({
    "version": 1,
    "dramas": [{
        "id": "owned:cover-smoke",
        "title": "Cover Smoke",
        "summary": "cover",
        "coverUrl": "cover.png",
        "genres": [],
        "language": "en",
        "availableRegions": ["SG"],
        "freeEpisodes": 1,
        "episodes": [{
            "id": "owned:cover-smoke:ep:1",
            "index": 1,
            "title": "Episode 1",
            "durationSeconds": 1,
            "posterUrl": "https://example.com/episode.png",
            "videoProvider": "local_filesystem",
            "videoAssetId": "cover-smoke/ep01.mp4",
        }],
    }],
}), encoding="utf-8")
os.environ.update({
    "OWNED_CATALOG_PATH": str(catalog_path),
    "LOCAL_COVER_ROOT": str(cover_root),
    "LOCAL_MEDIA_ENABLED": "false",
    "ENVIRONMENT": "development",
    "PLAYBACK_SIGNING_KEY": "cover-smoke-secret",
})

from app.main import app

client = TestClient(app)
feed = client.get("/v1/feed", params={"region": "SG"})
assert feed.status_code == 200, feed.text
item = feed.json()["items"][0]
assert item["cover_url"] == "/v1/covers/cover.png", item
assert client.get(item["cover_url"]).status_code == 200
assert client.get("/v1/covers/../main.py").status_code in {400, 404}
assert client.get("/v1/covers/cover.txt").status_code in {404, 415}
print("COVER SMOKE TEST PASSED")
