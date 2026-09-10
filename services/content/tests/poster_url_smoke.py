import json
import os
from pathlib import Path

from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[1]
catalog_path = ROOT / "runtime" / "poster-url-smoke-catalog.json"
catalog_path.parent.mkdir(parents=True, exist_ok=True)
catalog_path.write_text(json.dumps({
    "version": 1,
    "dramas": [{
        "id": "owned:poster-url-smoke",
        "title": "Poster URL Smoke",
        "summary": "poster url",
        "genres": [],
        "language": "en",
        "availableRegions": ["SG"],
        "freeEpisodes": 1,
        "episodes": [{
            "id": "owned:poster-url-smoke:ep:1",
            "index": 1,
            "title": "Episode 1",
            "durationSeconds": 1,
            "posterUrl": "https://example.com/poster.png",
            "videoProvider": "local_filesystem",
            "videoAssetId": "poster-url-smoke/ep01.mp4",
        }],
    }],
}), encoding="utf-8")
os.environ.update({
    "OWNED_CATALOG_PATH": str(catalog_path),
    "LOCAL_MEDIA_ENABLED": "false",
    "ENVIRONMENT": "development",
    "PLAYBACK_SIGNING_KEY": "poster-url-smoke-secret",
})

from app.main import app

client = TestClient(app)
feed = client.get("/v1/feed", params={"region": "SG"})
assert feed.status_code == 200, feed.text
item = feed.json()["items"][0]
assert item["cover_url"] == "https://example.com/poster.png", item
print("POSTER URL SMOKE TEST PASSED")
