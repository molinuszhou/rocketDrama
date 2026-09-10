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
    "rv_random_chest_open",
    "rv_episode_reward_boost",
    "rv_welcome_bonus",
    "rv_checkin_complete",
    "rv_episode_rewards_claim",
    "rv_crown_upgrade",
]
InterstitialPlacement = Literal[
    "int_timer",
    "int_pause",
    "int_next_episode_unlock",
    "int_pause_native",
    "int_reward_claim",
    "int_episode_midroll",
    "int_seek",
    "int_speed",
]


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


class ClaimAllEpisodeRewardsRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)
    reference_id: str = Field(min_length=8, max_length=160)
    ad_event_id: str = Field(min_length=8, max_length=160)


class RewardPolicyUpdateRequest(BaseModel):
    random_chest_min_gems: int | None = Field(default=None, ge=0, le=10_000_000)
    random_chest_max_gems: int | None = Field(default=None, ge=0, le=10_000_000)
    daily_random_chest_count: int | None = Field(default=None, ge=0, le=10)
    episodes_per_random_chest: int | None = Field(default=None, ge=1, le=100)
    reward_claim_interstitial_enabled: bool | None = None
    mid_episode_interstitial_enabled: bool | None = None
    mid_episode_interstitial_trigger_seconds: int | None = Field(default=None, ge=0, le=3600)
    mid_episode_interstitial_hourly_limit: int | None = Field(default=None, ge=0, le=120)
    seek_interstitial_enabled: bool | None = None
    seek_interstitial_hourly_limit: int | None = Field(default=None, ge=0, le=120)
    next_episode_interstitial_enabled: bool | None = None
    speed_interstitial_enabled: bool | None = None
    speed_interstitial_hourly_limit: int | None = Field(default=None, ge=0, le=240)
    crown_rewarded_ads_per_episode: int | None = Field(default=None, ge=1, le=10)
    release_boost_percent: float | None = Field(default=None, gt=0.0, le=1.0)
    release_boost_daily_limit: int | None = Field(default=None, ge=0, le=20)


class CrownMultiplierUpdateRequest(BaseModel):
    code: Literal["BRONZE", "SILVER", "GOLD", "DIAMOND", "CROWN"]
    episode_reward_multiplier: float = Field(ge=1.0, le=10.0)


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
