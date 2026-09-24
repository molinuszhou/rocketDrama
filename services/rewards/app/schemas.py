from __future__ import annotations

from datetime import date, datetime
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
    "rv_episode_task_claim",
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
    "int_rewarded_fallback",
    "int_drama_switch",
]


class BootstrapRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)
    # Android 已随 bootstrap 发送该字段；服务端将 BR、ID 映射到本币策略，其他地区回退 DEFAULT/USD。
    region: str | None = Field(default=None, min_length=2, max_length=32)
    device_id: str | None = Field(default=None, max_length=256)


class RewardedCompleteRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)
    ad_event_id: str = Field(min_length=8, max_length=160)
    placement_id: RewardedPlacement
    target_id: str | None = Field(default=None, max_length=256)
    episode_index: int | None = Field(default=None, ge=1, le=10000)
    timestamp_ms: int | None = Field(default=None, ge=0)
    proof: str | None = Field(default=None, max_length=2048)


class RewardedFallbackSettlementRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)
    interstitial_event_id: str = Field(min_length=8, max_length=160)
    original_placement_id: RewardedPlacement
    target_id: str | None = Field(default=None, max_length=256)
    episode_index: int | None = Field(default=None, ge=1, le=10000)


class WatchEventRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)
    event_id: str = Field(min_length=8, max_length=160)
    drama_id: str = Field(min_length=1, max_length=256)
    episode_id: str = Field(min_length=1, max_length=256)
    watched_seconds: int = Field(ge=1, le=86_400)


class EpisodeTaskClaimRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)
    milestone_episode_count: int = Field(ge=1, le=100_000)
    reference_id: str = Field(min_length=8, max_length=160)


class RewardedSkippedRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)
    context: str = Field(default="reward_prompt", min_length=1, max_length=64)


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
    episode_reward_gems: int | None = Field(default=None, ge=0, le=10_000_000)
    episodes_per_rewarded_unlock: int | None = Field(default=None, ge=1, le=100)
    episode_reward_boost_enabled: bool | None = None
    episode_reward_claim_prompt_threshold_gems: int | None = Field(default=None, ge=0, le=1_000_000_000)
    episode_task_max_episode: int | None = Field(default=None, ge=18, le=90)
    episode_task_post18_interval: int | None = Field(default=None, ge=1, le=1_000)
    episode_task_redpacket_min_gems: int | None = Field(default=None, ge=0, le=10_000_000)
    episode_task_redpacket_max_gems: int | None = Field(default=None, ge=0, le=10_000_000)
    episode_task_tier1_min_gems: int | None = Field(default=None, ge=0, le=10_000_000)
    episode_task_tier1_max_gems: int | None = Field(default=None, ge=0, le=10_000_000)
    episode_task_tier2_min_gems: int | None = Field(default=None, ge=0, le=10_000_000)
    episode_task_tier2_max_gems: int | None = Field(default=None, ge=0, le=10_000_000)
    episode_task_tier3_min_gems: int | None = Field(default=None, ge=0, le=10_000_000)
    episode_task_tier3_max_gems: int | None = Field(default=None, ge=0, le=10_000_000)
    episode_task_tier4_min_gems: int | None = Field(default=None, ge=0, le=10_000_000)
    episode_task_tier4_max_gems: int | None = Field(default=None, ge=0, le=10_000_000)
    wallet_redpacket_min_gems: int | None = Field(default=None, ge=0, le=10_000_000)
    wallet_redpacket_max_gems: int | None = Field(default=None, ge=0, le=10_000_000)
    random_chest_min_gems: int | None = Field(default=None, ge=0, le=10_000_000)
    random_chest_max_gems: int | None = Field(default=None, ge=0, le=10_000_000)
    daily_random_chest_count: int | None = Field(default=None, ge=0, le=10)
    episodes_per_random_chest: int | None = Field(default=None, ge=1, le=100)
    reward_claim_interstitial_enabled: bool | None = None
    mid_episode_interstitial_enabled: bool | None = None
    mid_episode_interstitial_trigger_seconds: int | None = Field(default=None, ge=0, le=3600)
    mid_episode_interstitial_repeat_seconds: int | None = Field(default=None, ge=0, le=3600)
    mid_episode_interstitial_hourly_limit: int | None = Field(default=None, ge=0, le=120)
    seek_interstitial_enabled: bool | None = None
    seek_interstitial_hourly_limit: int | None = Field(default=None, ge=0, le=120)
    next_episode_interstitial_enabled: bool | None = None
    speed_interstitial_enabled: bool | None = None
    speed_interstitial_hourly_limit: int | None = Field(default=None, ge=0, le=240)
    action_interstitial_reward_threshold_gems: int | None = Field(default=None, ge=0, le=1_000_000_000)
    crown_rewarded_ads_per_episode: int | None = Field(default=None, ge=1, le=10)
    crown_rewarded_daily_limit: int | None = Field(default=None, ge=0, le=100)
    crown_episode_progress_points: int | None = Field(default=None, ge=1, le=100)
    crown_rewarded_ad_progress_points: int | None = Field(default=None, ge=1, le=100)
    crown_rewarded_cooldown_seconds: int | None = Field(default=None, ge=0, le=3600)
    release_boost_percent: float | None = Field(default=None, gt=0.0, le=1.0)
    release_boost_daily_limit: int | None = Field(default=None, ge=0, le=100)
    release_boost_cooldown_seconds: int | None = Field(default=None, ge=0, le=3600)
    rv_daily_limit: int | None = Field(default=None, ge=0, le=1000)
    episode_interstitial_limit: int | None = Field(default=None, ge=0, le=10)
    interstitial_global_hourly_limit: int | None = Field(default=None, ge=0, le=120)
    interstitial_cooldown_seconds: int | None = Field(default=None, ge=0, le=3600)
    interstitial_rewarded_skip_threshold: int | None = Field(default=None, ge=0, le=50)
    drama_switch_interstitial_enabled: bool | None = None
    drama_switch_interstitial_every_count: int | None = Field(default=None, ge=1, le=50)
    drama_switch_interstitial_hourly_limit: int | None = Field(default=None, ge=0, le=120)
    rewarded_fallback_interstitial_enabled: bool | None = None
    gems_earned_cap: int | None = Field(default=None, ge=0, le=10_000_000_000)
    gem_damping_tier1_threshold: int | None = Field(default=None, ge=0, le=10_000_000_000)
    gem_damping_tier2_threshold: int | None = Field(default=None, ge=0, le=10_000_000_000)
    gem_damping_tier3_threshold: int | None = Field(default=None, ge=0, le=10_000_000_000)
    gem_damping_tier4_threshold: int | None = Field(default=None, ge=0, le=10_000_000_000)
    gem_damping_tier5_threshold: int | None = Field(default=None, ge=0, le=10_000_000_000)
    gem_damping_tier1_multiplier: float | None = Field(default=None, ge=0.0, le=1.0)
    gem_damping_tier2_multiplier: float | None = Field(default=None, ge=0.0, le=1.0)
    gem_damping_tier3_multiplier: float | None = Field(default=None, ge=0.0, le=1.0)
    gem_damping_tier4_multiplier: float | None = Field(default=None, ge=0.0, le=1.0)
    gem_damping_tier5_multiplier: float | None = Field(default=None, ge=0.0, le=1.0)
    gem_damping_tier6_multiplier: float | None = Field(default=None, ge=0.0, le=1.0)


class MarketWalletPolicyUpdateRequest(BaseModel):
    market_code: str = Field(min_length=2, max_length=16, pattern=r"^[A-Za-z_]+$")
    currency_code: str = Field(min_length=3, max_length=3, pattern=r"^[A-Za-z]{3}$")
    currency_symbol: str = Field(min_length=1, max_length=8)
    currency_fraction_digits: int = Field(ge=0, le=6)
    currency_micros_per_10000_gems: int = Field(gt=0, le=10_000_000_000_000)
    usd_micros_per_10000_gems: int = Field(gt=0, le=10_000_000_000_000)
    withdrawal_threshold_micros: int = Field(gt=0, le=10_000_000_000_000)
    welcome_bonus_gems: int = Field(ge=0, le=10_000_000_000)
    daily_release_micros: int = Field(gt=0, le=10_000_000_000_000)
    enabled: bool = True


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
    episode_id: str | None = Field(default=None, max_length=256)


class AdMediationEventRequest(BaseModel):
    """A non-financial client mirror of an observed TopOn mediation callback.

    ``install_id_hash`` is SHA-256 calculated on device; raw installation and user
    identifiers are intentionally not accepted by the operations ledger.
    """

    client_event_id: str = Field(min_length=8, max_length=160)
    install_id_hash: str = Field(min_length=64, max_length=64, pattern=r"^[0-9a-f]{64}$")
    event_type: Literal["SHOWN", "PAID_REVENUE"]
    logical_placement: str = Field(min_length=1, max_length=80)
    topon_placement_id: str = Field(min_length=1, max_length=160)
    ad_format: str = Field(min_length=1, max_length=32)
    ad_network: str = Field(min_length=1, max_length=160)
    ad_source_id: str = Field(default="", max_length=256)
    app_version: str = Field(min_length=1, max_length=64)
    currency: str = Field(default="", max_length=3, pattern=r"^[A-Za-z]{0,3}$")
    ecpm: float | None = Field(default=None, ge=0.0, le=10_000_000.0)
    occurred_at_ms: int = Field(ge=0, le=4_102_444_800_000)


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
