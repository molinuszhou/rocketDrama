from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path

from fastapi.testclient import TestClient

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))


def run() -> None:
    project_root = PROJECT_ROOT
    with tempfile.TemporaryDirectory() as temporary_directory:
        workspace = Path(temporary_directory)
        media_root = workspace / "media"
        video_path = media_root / "owned" / "local-test-drama" / "ep-001.mp4"
        video_path.parent.mkdir(parents=True)
        video_path.write_bytes(b"local-media-test-bytes")

        catalog_path = project_root / "content" / "owned_catalog.local-media.example.json"
        os.environ["ENVIRONMENT"] = "development"
        os.environ["LOCAL_MEDIA_ENABLED"] = "true"
        os.environ["LOCAL_MEDIA_ROOT"] = str(media_root)
        os.environ["OWNED_CATALOG_PATH"] = str(catalog_path)
        os.environ["PANGLE_CONTENT_ENABLED"] = "false"

        # 需要在环境变量准备完成后才导入应用模块。
        from app.main import app

        client = TestClient(app)
        health = client.get("/health")
        assert health.status_code == 200 and health.json()["local_media"] == "enabled"

        free_episode = "owned:local-test-drama:ep:1"
        session = client.post(
            f"/v1/episodes/{free_episode}/playback-session",
            json={"region": "SG", "user_id": "local-user", "device_id": "test-device"},
        )
        assert session.status_code == 200, session.text
        stream_url = session.json()["stream_url"]
        media_response = client.get(stream_url)
        assert media_response.status_code == 200
        assert media_response.content == b"local-media-test-bytes"

        missing_token = client.get(f"/v1/dev/local-media/{free_episode}?token=invalid-token-value-1234")
        assert missing_token.status_code == 403

        locked_episode = "owned:local-test-drama:ep:2"
        locked_session = client.post(
            f"/v1/episodes/{locked_episode}/playback-session",
            json={"region": "SG", "user_id": "local-user", "device_id": "test-device"},
        )
        assert locked_session.status_code == 402

    print("local media smoke test passed")


if __name__ == "__main__":
    run()
