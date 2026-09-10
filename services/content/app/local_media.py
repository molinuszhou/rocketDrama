from __future__ import annotations

import secrets
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from threading import Lock

from fastapi import HTTPException, status


class LocalMediaStore:
    """仅开发环境使用的本地媒体访问保护层。

    不允许绝对路径、父目录跳转或媒体根目录以外的文件访问。生产环境必须使用
    对象存储/CDN/VOD 的短期播放授权，不能启用本类对应的 HTTP 文件服务。
    """

    _ALLOWED_EXTENSIONS = {".mp4", ".m4v", ".webm", ".m3u8", ".mpd"}

    def __init__(self, root: str) -> None:
        media_root = Path(root).expanduser().resolve()
        if not media_root.is_dir():
            raise RuntimeError(f"LOCAL_MEDIA_ROOT 不存在或不是目录：{media_root}")
        self._root = media_root

    def resolve(self, relative_path: str) -> Path:
        normalized = relative_path.replace("\\", "/").strip()
        requested = Path(normalized)
        if not normalized or requested.is_absolute() or ".." in requested.parts:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="本地媒体路径必须是媒体根目录下的相对路径",
            )

        # 片单历史上同时存在多种安全布局：
        # 1) <LOCAL_MEDIA_ROOT>/<剧目录>/ep01.mp4
        # 2) <LOCAL_MEDIA_ROOT>/owned/<剧目录>/ep01.mp4
        # 3) videoAssetId 带有 media/ 或 content/media/ 前缀。
        # Windows 测试包兼容这些写法，避免目录能加载但播放时因路径前缀重复而 404。
        candidates = [requested]
        parts = requested.parts
        if len(parts) >= 2 and parts[0].lower() == "content" and parts[1].lower() == "media":
            candidates.append(Path(*parts[2:]))
        elif parts and parts[0].lower() == "media":
            candidates.append(Path(*parts[1:]))
        if requested.parts and requested.parts[0].lower() != "owned":
            candidates.append(Path("owned") / requested)
        elif len(requested.parts) > 1:
            candidates.append(Path(*requested.parts[1:]))

        for candidate in candidates:
            resolved = (self._root / candidate).resolve()
            if self._root not in resolved.parents or not resolved.is_file():
                continue
            if resolved.suffix.lower() not in self._ALLOWED_EXTENSIONS:
                raise HTTPException(
                    status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
                    detail="本地测试媒体格式不受支持",
                )
            return resolved

        fallback_paths = " 或 ".join(str(path).replace("\\\\", "/") for path in candidates)
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"本地测试媒体文件不存在。请在 LOCAL_MEDIA_ROOT 下放置：{fallback_paths}",
        )


@dataclass(frozen=True)
class LocalPlaybackGrant:
    episode_id: str
    relative_path: str
    expires_at: datetime


class LocalPlaybackSessionStore:
    """内存态短时播放授权，仅用于本地开发和真机局域网联调。"""

    def __init__(self) -> None:
        self._grants: dict[str, LocalPlaybackGrant] = {}
        self._lock = Lock()

    def issue(self, episode_id: str, relative_path: str, ttl_minutes: int = 10) -> tuple[str, datetime]:
        expires_at = datetime.now(UTC) + timedelta(minutes=ttl_minutes)
        token = secrets.token_urlsafe(24)
        with self._lock:
            self._discard_expired_locked()
            self._grants[token] = LocalPlaybackGrant(
                episode_id=episode_id,
                relative_path=relative_path,
                expires_at=expires_at,
            )
        return token, expires_at

    def resolve(self, token: str, episode_id: str) -> str:
        with self._lock:
            self._discard_expired_locked()
            grant = self._grants.get(token)
        if grant is None or grant.episode_id != episode_id:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="本地播放会话无效或已过期，请重新请求播放会话",
            )
        return grant.relative_path

    def _discard_expired_locked(self) -> None:
        now = datetime.now(UTC)
        expired_tokens = [token for token, grant in self._grants.items() if grant.expires_at <= now]
        for token in expired_tokens:
            self._grants.pop(token, None)
