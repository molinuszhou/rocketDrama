from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Protocol

from app.models import (
    Availability,
    ContentSource,
    DramaCard,
    Episode,
    EntitlementState,
    PlaybackMode,
    ProviderHealth,
)


class ContentProvider(Protocol):
    source: ContentSource

    def list_dramas(self, region: str, language: str | None = None) -> list[DramaCard]: ...

    def get_drama(self, drama_id: str, region: str, user_id: str | None = None) -> DramaCard | None: ...

    def get_episode(self, episode_id: str, region: str, user_id: str | None = None) -> Episode | None: ...

    def health(self) -> ProviderHealth: ...


@dataclass(frozen=True)
class FreeDramaRecord:
    drama: DramaCard
    episodes: tuple[Episode, ...]


class FreeDramaProvider:
    """自有或已获得海外分发授权的自由剧内容源示例。

    生产版本应替换为 MySQL/PostgreSQL 元数据仓库、对象存储/CDN 和版权管理后台。
    """

    source = ContentSource.FREE_DRAMA

    def __init__(self, records: tuple[FreeDramaRecord, ...] | None = None) -> None:
        self._records = records or self._seed_records()
        self._unlocked: set[tuple[str, str]] = set()

    def list_dramas(self, region: str, language: str | None = None) -> list[DramaCard]:
        records = [record.drama for record in self._records if self._is_available(record.drama, region)]
        if language:
            records = [drama for drama in records if drama.language == language]
        return records

    def get_drama(self, drama_id: str, region: str, user_id: str | None = None) -> DramaCard | None:
        record = self._record_by_drama_id(drama_id)
        return record.drama if record and self._is_available(record.drama, region) else None

    def get_episode(self, episode_id: str, region: str, user_id: str | None = None) -> Episode | None:
        record = self._record_by_episode_id(episode_id)
        if record is None or not self._is_available(record.drama, region):
            return None
        episode = next(episode for episode in record.episodes if episode.id == episode_id)
        if user_id and (user_id, episode_id) in self._unlocked:
            return episode.model_copy(update={"entitlement": EntitlementState.FREE})
        return episode

    def mark_unlocked(self, user_id: str, episode_id: str) -> bool:
        if not self._record_by_episode_id(episode_id):
            return False
        self._unlocked.add((user_id, episode_id))
        return True

    def health(self) -> ProviderHealth:
        return ProviderHealth(
            provider=self.source,
            enabled=True,
            healthy=True,
            detail="本地自由剧示例库已加载；生产环境请接入版权元数据、CDN 和播放令牌服务。",
            last_checked_at=datetime.now(UTC),
        )

    @staticmethod
    def _is_available(drama: DramaCard, region: str) -> bool:
        return drama.availability.available and region in drama.availability.available_regions

    def _record_by_drama_id(self, drama_id: str) -> FreeDramaRecord | None:
        return next((record for record in self._records if record.drama.id == drama_id), None)

    def _record_by_episode_id(self, episode_id: str) -> FreeDramaRecord | None:
        return next((record for record in self._records if any(episode.id == episode_id for episode in record.episodes)), None)

    @staticmethod
    def _seed_records() -> tuple[FreeDramaRecord, ...]:
        regions = ["US", "CA", "GB", "SG", "TH", "ID", "PH", "MY", "MX", "BR", "AE"]
        card = DramaCard(
            id="free:shadow-contract",
            source=ContentSource.FREE_DRAMA,
            title="契约迷局（自由剧示例）",
            summary="仅用于服务端联调的自由剧元数据示例。替换为完成地域授权的真实片单后即可上线。",
            cover_url="https://cdn.example.invalid/dramaflow/free/shadow-contract/cover.jpg",
            genre=["爱情", "悬疑"],
            language="zh-CN",
            origin_regions=["SG"],
            total_episodes=12,
            free_episodes=2,
            playback_mode=PlaybackMode.SIGNED_URL,
            availability=Availability(available=True, available_regions=regions),
            badge="自由剧",
        )
        episodes = tuple(
            Episode(
                id=f"free:shadow-contract:ep:{index}",
                drama_id=card.id,
                index=index,
                title=f"第 {index} 集",
                duration_seconds=90,
                cover_url=f"https://cdn.example.invalid/dramaflow/free/shadow-contract/ep-{index}.jpg",
                entitlement=EntitlementState.FREE if index <= card.free_episodes else EntitlementState.AD_UNLOCK,
                playback_mode=PlaybackMode.SIGNED_URL,
            )
            for index in range(1, card.total_episodes + 1)
        )
        return (FreeDramaRecord(card, episodes),)


class PangleDramaProvider:
    """Pangle 内容源边界。

    未取得内容 SDK、应用配置和地区授权时，该适配器不返回真实内容或播放地址。
    获取正式资料后，只替换该类，不改变 Android 统一接口或自由剧逻辑。
    """

    source = ContentSource.PANGLE_DRAMA

    def __init__(self, enabled: bool = False) -> None:
        self.enabled = enabled

    def list_dramas(self, region: str, language: str | None = None) -> list[DramaCard]:
        return []

    def get_drama(self, drama_id: str, region: str, user_id: str | None = None) -> DramaCard | None:
        return None

    def get_episode(self, episode_id: str, region: str, user_id: str | None = None) -> Episode | None:
        return None

    def health(self) -> ProviderHealth:
        detail = (
            "等待 Pangle 内容 SDK、内容应用配置和目标地区授权；当前不会返回虚构剧集或播放地址。"
            if not self.enabled
            else "Pangle 内容适配器已启用，但尚未实现合作方 SDK 同步逻辑。"
        )
        return ProviderHealth(
            provider=self.source,
            enabled=self.enabled,
            healthy=not self.enabled,
            detail=detail,
            last_checked_at=datetime.now(UTC),
        )
