from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Annotated

from pydantic import BaseModel, Field, HttpUrl, StringConstraints


RegionCode = Annotated[str, StringConstraints(pattern=r"^[A-Z]{2}$")]
LanguageCode = Annotated[str, StringConstraints(pattern=r"^[a-z]{2}(-[A-Z]{2})?$")]


class ContentSource(str, Enum):
    FREE_DRAMA = "FREE_DRAMA"
    PANGLE_DRAMA = "PANGLE_DRAMA"


class PlaybackMode(str, Enum):
    SIGNED_URL = "SIGNED_URL"
    PANGLE_SDK = "PANGLE_SDK"


class EntitlementState(str, Enum):
    FREE = "FREE"
    AD_UNLOCK = "AD_UNLOCK"
    COIN_UNLOCK = "COIN_UNLOCK"
    UNAVAILABLE = "UNAVAILABLE"


class UnlockMethod(str, Enum):
    AD_REWARD = "AD_REWARD"
    COIN = "COIN"


class Availability(BaseModel):
    available: bool
    reason: str | None = None
    available_regions: list[RegionCode] = Field(default_factory=list)
    available_from: datetime | None = None
    available_until: datetime | None = None


class DramaCard(BaseModel):
    id: str
    source: ContentSource
    title: str
    summary: str
    cover_url: str
    genre: list[str] = Field(default_factory=list)
    language: LanguageCode
    origin_regions: list[RegionCode] = Field(default_factory=list)
    total_episodes: int = Field(ge=1)
    free_episodes: int = Field(ge=0)
    playback_mode: PlaybackMode
    availability: Availability
    badge: str | None = None


class Episode(BaseModel):
    id: str
    drama_id: str
    index: int = Field(ge=1)
    title: str
    duration_seconds: int = Field(ge=1)
    cover_url: str
    entitlement: EntitlementState
    playback_mode: PlaybackMode
    media_asset_id: str | None = None
    media_provider: str | None = None


class DramaDetail(DramaCard):
    episodes: list[Episode] = Field(default_factory=list)


class FeedPage(BaseModel):
    items: list[DramaCard]
    next_cursor: str | None = None


class PlaybackSessionRequest(BaseModel):
    region: RegionCode
    user_id: str | None = Field(default=None, max_length=128)
    device_id: str | None = Field(default=None, max_length=128)


class PlaybackSessionResponse(BaseModel):
    episode_id: str
    source: ContentSource
    playback_mode: PlaybackMode
    expires_at: datetime | None = None
    stream_url: HttpUrl | None = None
    sdk_route: str | None = None
    sdk_payload: dict[str, str] | None = None
    entitlement: EntitlementState
    message: str | None = None


class UnlockRequest(BaseModel):
    region: RegionCode
    method: UnlockMethod
    user_id: str = Field(min_length=1, max_length=128)
    proof: str | None = Field(default=None, max_length=4096)


class UnlockResponse(BaseModel):
    episode_id: str
    unlocked: bool
    entitlement: EntitlementState
    message: str


class ProviderHealth(BaseModel):
    provider: ContentSource
    enabled: bool
    healthy: bool
    detail: str
    last_checked_at: datetime
