from __future__ import annotations

import sys
import tempfile
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from app.local_media import LocalMediaStore


def run() -> None:
    with tempfile.TemporaryDirectory() as temporary_directory:
        media_root = Path(temporary_directory) / "media"

        # 三部剧片单目前使用这种平铺相对路径：Love in Cyberpunk 2077/ep01.mp4。
        flat_file = media_root / "Love in Cyberpunk 2077" / "ep01.mp4"
        flat_file.parent.mkdir(parents=True)
        flat_file.write_bytes(b"flat-layout")

        # Windows 用户也常将剧目录放在 media\\owned 下；服务端应安全兼容这一层。
        owned_file = media_root / "owned" / "Wasteland Wolf King" / "ep01.mp4"
        owned_file.parent.mkdir(parents=True)
        owned_file.write_bytes(b"owned-layout")

        store = LocalMediaStore(str(media_root))
        assert store.resolve("Love in Cyberpunk 2077/ep01.mp4") == flat_file.resolve()
        assert store.resolve("Wasteland Wolf King/ep01.mp4") == owned_file.resolve()
        assert store.resolve("owned/Wasteland Wolf King/ep01.mp4") == owned_file.resolve()

    print("local media path fallback smoke test passed")


if __name__ == "__main__":
    run()
