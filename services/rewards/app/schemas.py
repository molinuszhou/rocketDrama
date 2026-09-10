from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field


RewardedPlacement = Literal[
    "rv_episode_pass",
    "rv_claim_reward",
    "rv_double_reward",
    "rv_episode_reward_double",
    "rv_chest_task",
    "rv_signin_double",
    "rv_release_boost",
]
InterstitialPlacement = Literal["int_timer", "int_pause", "int_next_episode_unlock", "int_pause_native"]


class BootstrapRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)
    device_id: str | None = Field(default=None, max_length=256)


class RewardedCompleteRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)
    ad_event_id: str = Field(min_length=8, max_length=160)
    placement_id: RewardedPlacement
    target_id: str | None = Field(default=None, max_length=256)
    episode_index: int | None = Field(default=None, ge=1, le=10000)
    timestamp_ms: int | None = Field(default=None, ge=0)
    proof: str | None = Field(default=None, max_length=2048)


class WatchEventRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)
    event_id: str = Field(min_length=8, max_length=160)
    drama_id: str = Field(min_length=1, max_length=256)
    episode_id: str = Field(min_length=1, max_length=256)
    watched_seconds: int = Field(ge=1, le=86_400)


class CheckinRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)


class WelcomeBonusRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)


class EpisodeRewardClaimRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)
    episode_id: str = Field(min_length=1, max_length=256)
    reference_id: str = Field(min_length=8, max_length=160)


class DampingUpdateRequest(BaseModel):
    decay_alpha: float = Field(ge=0.0, le=5.0)


class TenRoundClaimRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)
    cycle_number: int = Field(ge=1, le=100_000)
    reference_id: str = Field(min_length=8, max_length=160)


class GemsExchangeRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)
    gems: int = Field(ge=1, le=10_000_000_000)
    reference_id: str = Field(min_length=8, max_length=160)


class InterstitialShownRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)
    event_id: str = Field(min_length=8, max_length=160)
    placement_id: InterstitialPlacement


class WithdrawalPreflightRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)


class ApiEnvelope(BaseModel):
    code: int = 0
    data: dict


class ApiError(BaseModel):
    code: int
    msg: str


class HealthResponse(BaseModel):
    status: str
    database: str
    environment: str
    now: datetime
