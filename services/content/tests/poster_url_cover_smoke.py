import json
import os
import sys
from pathlib import Path

from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
catalog_path = ROOT / "runtime" / "poster-url-cover-smoke.json"
catalog_path.write_text(json.dumps({
    "version": 1,
    "dramas": [{
        "id": "owned:poster-cover-smoke",
        "title": "Poster Cover Smoke",
        "summary": "poster cover",
        "coverUrl": "https://example.com/fallback-cover.jpg",
        "genres": [],
        "language": "en",
        "availableRegions": ["SG"],
        "freeEpisodes": 1,
        "episodes": [{
            "id": "owned:poster-cover-smoke:ep:1",
            "index": 1,
            "title": "Episode 1",
            "durationSeconds": 1,
            "posterUrl": "https://example.com/poster-1.jpg",
            "videoProvider": "local_filesystem",
            "videoAssetId": "Love in Cyberpunk 2077/ep01.mp4",
        }],
    }],
}), encoding="utf-8")
os.environ.update({
    "OWNED_CATALOG_PATH": str(catalog_path),
    "LOCAL_MEDIA_ENABLED": "false",
    "ENVIRONMENT": "development",
    "PLAYBACK_SIGNING_KEY": "poster-cover-smoke-secret",
})

from app.main import app

client = TestClient(app)
feed = client.get("/v1/feed", params={"region": "SG"})
assert feed.status_code == 200, feed.text
item = feed.json()["items"][0]
assert item["cover_url"] == "https://example.com/poster-1.jpg", item
print("POSTER URL COVER SMOKE TEST PASSED")
