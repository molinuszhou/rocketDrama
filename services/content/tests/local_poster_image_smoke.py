import json
import os
import sys
from pathlib import Path

from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
media_root = ROOT / "runtime" / "local-poster-media"
image_path = media_root / "owned" / "Love-in-Cyberpunk-2077" / "Cover.png"
video_path = image_path.parent / "ep-001.mp4"
image_path.parent.mkdir(parents=True, exist_ok=True)
image_path.write_bytes(b"fake-png")
video_path.write_bytes(b"fake-mp4")
catalog_path = ROOT / "runtime" / "local-poster-catalog.json"
catalog_path.write_text(json.dumps({
    "version": 1,
    "dramas": [{
        "id": "owned:Love-in-Cyberpunk-2077",
        "title": "Love in Cyberpunk 2077",
        "summary": "local poster",
        "coverUrl": "owned/Love-in-Cyberpunk-2077/Cover.png",
        "genres": [],
        "language": "en",
        "availableRegions": ["SG"],
        "freeEpisodes": 1,
        "episodes": [{
            "id": "owned:Love-in-Cyberpunk-2077:ep:1",
            "index": 1,
            "title": "Episode 1",
            "durationSeconds": 1,
            "posterUrl": "owned/Love-in-Cyberpunk-2077/Cover.png",
            "videoProvider": "local_filesystem",
            "videoAssetId": "owned/Love-in-Cyberpunk-2077/ep-001.mp4",
        }],
    }],
}), encoding="utf-8")
os.environ.update({
    "OWNED_CATALOG_PATH": str(catalog_path),
    "LOCAL_MEDIA_ENABLED": "true",
    "LOCAL_MEDIA_ROOT": str(media_root),
    "ENVIRONMENT": "development",
    "PLAYBACK_SIGNING_KEY": "local-poster-smoke-secret",
})

from app.main import app

client = TestClient(app)
feed = client.get("/v1/feed", params={"region": "SG"})
assert feed.status_code == 200, feed.text
item = feed.json()["items"][0]
assert item["cover_url"] == "/v1/dev/local-image/owned/Love-in-Cyberpunk-2077/Cover.png", item
image = client.get(item["cover_url"])
assert image.status_code == 200, image.text
assert image.content == b"fake-png"
assert client.get("/v1/dev/local-image/../main.py").status_code in {400, 404}
print("LOCAL POSTER IMAGE SMOKE TEST PASSED")
