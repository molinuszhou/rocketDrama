from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

from pydantic import BaseModel, Field

from app.models import Availability, ContentSource, DramaCard, Episode, EntitlementState, PlaybackMode
from app.providers import FreeDramaRecord


class OwnedEpisodeInput(BaseModel):
    id: str
    index: int = Field(ge=1)
    title: str
    durationSeconds: int = Field(ge=1)
    posterUrl: str
    videoProvider: str
    videoAssetId: str
    subtitleLanguages: list[str] = Field(default_factory=list)


class OwnedDramaInput(BaseModel):
    id: str
    title: str
    summary: str
    coverUrl: str
    genres: list[str] = Field(default_factory=list)
    language: str
    originRegions: list[str] = Field(default_factory=list)
    availableRegions: list[str]
    availableFrom: datetime | None = None
    availableUntil: datetime | None = None
    allowedMonetization: list[str] = Field(default_factory=list)
    freeEpisodes: int = Field(ge=0)
    episodes: list[OwnedEpisodeInput] = Field(min_length=1)


class OwnedCatalogInput(BaseModel):
    version: int
    dramas: list[OwnedDramaInput] = Field(min_length=1)


def load_owned_catalog(path: str) -> tuple[FreeDramaRecord, ...]:
    """从经审核的本地 JSON 元数据加载自有剧。

    只读取元数据和视频资产标识；不读取源视频文件，不将视频源 URL 写入目录。
    """

    catalog_file = Path(path)
    if not catalog_file.is_file():
        raise FileNotFoundError(f"OWNED_CATALOG_PATH 不存在或不是文件：{catalog_file}")
    payload = OwnedCatalogInput.model_validate(json.loads(catalog_file.read_text(encoding="utf-8")))
    records: list[FreeDramaRecord] = []
    for item in payload.dramas:
        episodes = tuple(
            Episode(
                id=episode.id,
                drama_id=item.id,
                index=episode.index,
                title=episode.title,
                duration_seconds=episode.durationSeconds,
                cover_url=episode.posterUrl,
                entitlement=EntitlementState.FREE if episode.index <= item.freeEpisodes else EntitlementState.AD_UNLOCK,
                playback_mode=PlaybackMode.SIGNED_URL,
                media_asset_id=episode.videoAssetId,
                media_provider=episode.videoProvider,
            )
            for episode in sorted(item.episodes, key=lambda entry: entry.index)
        )
        card = DramaCard(
            id=item.id,
            source=ContentSource.FREE_DRAMA,
            title=item.title,
            summary=item.summary,
            cover_url=item.coverUrl,
            genre=item.genres,
            language=item.language,
            origin_regions=item.originRegions,
            total_episodes=len(episodes),
            free_episodes=item.freeEpisodes,
            playback_mode=PlaybackMode.SIGNED_URL,
            availability=Availability(
                available=True,
                available_regions=item.availableRegions,
                available_from=item.availableFrom,
                available_until=item.availableUntil,
            ),
            badge="自有剧",
        )
        records.append(FreeDramaRecord(drama=card, episodes=episodes))
    return tuple(records)
