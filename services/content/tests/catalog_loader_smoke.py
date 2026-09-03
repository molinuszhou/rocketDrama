from pathlib import Path
import sys

from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.catalog_loader import load_owned_catalog
from app.main import app
from app.providers import FreeDramaProvider, PangleDramaProvider
from app.service import ContentOrchestrator


def main() -> None:
    records = load_owned_catalog(str(ROOT / "content" / "owned_catalog.example.json"))
    orchestrator = ContentOrchestrator(FreeDramaProvider(records=records), PangleDramaProvider())
    page = orchestrator.feed(region="SG", language=None, cursor=None, limit=20)
    assert len(page.items) == 1
    assert page.items[0].id == "owned:replace-with-stable-drama-id"
    detail = orchestrator.drama_detail(page.items[0].id, region="SG", user_id="test-user")
    assert detail.episodes[0].media_asset_id == "replace-with-private-video-asset-id"
    assert detail.episodes[0].playback_mode.value == "SIGNED_URL"
    print("CATALOG LOADER TEST PASSED: owned catalog replaces built-in source safely")


if __name__ == "__main__":
    main()
