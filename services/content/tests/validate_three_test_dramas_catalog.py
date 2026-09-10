from __future__ import annotations

import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from app.catalog_loader import load_owned_catalog


CATALOG_PATH = PROJECT_ROOT / "content" / "owned_catalog.three-test-dramas.json"
EXPECTED_DRAMA_PATHS = {
    "owned:love-in-cyberpunk-2077": "Love in Cyberpunk 2077",
    "owned:storage-war-they-sold-me-trash-i-bought-their-kingdom": "Storage War They Sold Me Trash. I Bought Their Kingdom",
    "owned:wasteland-wolf-king": "Wasteland Wolf King",
}


def run() -> None:
    payload = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
    records = load_owned_catalog(str(CATALOG_PATH))

    assert len(payload["dramas"]) == 3
    assert len(records) == 3
    assert {record.drama.id for record in records} == set(EXPECTED_DRAMA_PATHS)

    for drama in payload["dramas"]:
        expected_directory = EXPECTED_DRAMA_PATHS[drama["id"]]
        assert drama["freeEpisodes"] == 1  # 保留原测试片单原始字段，加载器统一按前三集免费输出。
        loaded = next(record.drama for record in records if record.drama.id == drama["id"])
        assert loaded.free_episodes == 3
        assert [episode.entitlement.value for episode in next(record.episodes for record in records if record.drama.id == drama["id"])] == ["FREE", "FREE", "FREE", "AD_UNLOCK", "AD_UNLOCK"]
        assert len(drama["episodes"]) == 5
        for index, episode in enumerate(drama["episodes"], start=1):
            assert episode["index"] == index
            assert episode["videoProvider"] == "local_filesystem"
            assert not Path(episode["videoAssetId"]).is_absolute()
            assert ".." not in Path(episode["videoAssetId"]).parts
            assert episode["videoAssetId"] == f"{expected_directory}/ep{index:02d}.mp4"

    print("three-test-dramas catalog validation passed: 3 dramas, 15 local MP4 episodes")


if __name__ == "__main__":
    run()
