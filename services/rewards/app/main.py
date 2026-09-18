from __future__ import annotations

from fastapi import FastAPI, Header, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse

from app.admin_dashboard import DASHBOARD_HTML
from app.config import Settings
from app.migrations import apply_runtime_migrations
from app.schemas import (
    ApiEnvelope,
    BootstrapRequest,
    CheckinRequest,
    ClaimAllEpisodeRewardsRequest,
    CrownMultiplierUpdateRequest,
    DampingUpdateRequest,
    EpisodeTaskClaimRequest,
    EpisodeRewardClaimRequest,
    GemsExchangeRequest,
    HealthResponse,
    MarketWalletPolicyUpdateRequest,
    InterstitialShownRequest,
    RewardedCompleteRequest,
    RewardPolicyUpdateRequest,
    RewardedSkippedRequest,
    ReviewRewardClaimRequest,
    TenRoundClaimRequest,
    WelcomeBonusRequest,
    WatchEventRequest,
    WithdrawalPreflightRequest,
)
from app.service import RewardsService, ServiceError

settings = Settings.from_environment()
apply_runtime_migrations(settings)
service = RewardsService(settings)

app = FastAPI(
    title="DramaFlow Rewards Service",
    version="0.1.0",
    description="独立 PostgreSQL 宝石积分、广告结算、签到和权益服务。",
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=False,
    allow_methods=["GET", "POST", "PUT"],
    allow_headers=["Content-Type", "Authorization", "X-Device-Id"],
)


@app.exception_handler(ServiceError)
async def service_error_handler(_: Request, error: ServiceError) -> JSONResponse:
    return JSONResponse(status_code=error.status_code, content={"code": error.code, "msg": error.message})


@app.get("/health", response_model=HealthResponse)
def health() -> dict:
    return service.health()


@app.post("/v2/bootstrap", response_model=ApiEnvelope)
def bootstrap(payload: BootstrapRequest) -> dict:
    return {"code": 0, "data": service.bootstrap(payload.user_id, payload.region)}


@app.post("/v2/ads/rewarded/complete", response_model=ApiEnvelope)
def complete_rewarded_ad(payload: RewardedCompleteRequest) -> dict:
    return {
        "code": 0,
        "data": service.complete_rewarded_ad(
            user_id=payload.user_id,
            ad_event_id=payload.ad_event_id,
            placement_id=payload.placement_id,
            target_id=payload.target_id,
            episode_index=payload.episode_index,
            proof=payload.proof,
            timestamp_ms=payload.timestamp_ms,
        ),
    }


@app.post("/v2/watch-events", response_model=ApiEnvelope)
def watch_event(payload: WatchEventRequest) -> dict:
    return {
        "code": 0,
        "data": service.record_watch_event(
            user_id=payload.user_id,
            event_id=payload.event_id,
            drama_id=payload.drama_id,
            episode_id=payload.episode_id,
            watched_seconds=payload.watched_seconds,
        ),
    }


@app.post("/v2/episode-tasks/claim", response_model=ApiEnvelope)
def claim_episode_task(payload: EpisodeTaskClaimRequest) -> dict:
    return {
        "code": 0,
        "data": service.claim_episode_task(
            payload.user_id,
            payload.milestone_episode_count,
            payload.reference_id,
        ),
    }


@app.post("/v2/ads/rewarded/skipped", response_model=ApiEnvelope)
def rewarded_skipped(payload: RewardedSkippedRequest) -> dict:
    return {"code": 0, "data": service.record_rewarded_skipped(payload.user_id, payload.context)}


@app.post("/v2/checkins", response_model=ApiEnvelope)
def checkin(payload: CheckinRequest) -> dict:
    return {"code": 0, "data": service.checkin(payload.user_id)}


@app.post("/v2/welcome/claim", response_model=ApiEnvelope)
def claim_welcome_bonus(payload: WelcomeBonusRequest) -> dict:
    return {"code": 0, "data": service.claim_welcome_bonus(payload.user_id)}


@app.post("/v2/reviews/claim", response_model=ApiEnvelope)
def claim_review_reward(payload: ReviewRewardClaimRequest) -> dict:
    return {"code": 0, "data": service.claim_review_reward(payload.user_id, payload.reference_id)}


@app.post("/v2/episode-rewards/claim", response_model=ApiEnvelope)
def claim_episode_reward(payload: EpisodeRewardClaimRequest) -> dict:
    return {"code": 0, "data": service.claim_episode_reward(payload.user_id, payload.episode_id, payload.reference_id)}


@app.post("/v2/episode-rewards/claim-all", response_model=ApiEnvelope)
def claim_all_episode_rewards(payload: ClaimAllEpisodeRewardsRequest) -> dict:
    return {"code": 0, "data": service.claim_all_episode_rewards(payload.user_id, payload.reference_id, payload.ad_event_id)}


@app.post("/v2/ten-round-rewards/claim", response_model=ApiEnvelope)
def claim_ten_round_reward(payload: TenRoundClaimRequest) -> dict:
    # 旧客户端兼容端点；v6 不再创建新的十集加奖。
    return {"code": 0, "data": service.claim_ten_round_bonus(payload.user_id, payload.cycle_number, payload.reference_id)}


@app.get("/v2/admin/policy", response_model=ApiEnvelope)
def admin_reward_policy(authorization: str | None = Header(default=None)) -> dict:
    return {"code": 0, "data": service.admin_reward_policy(authorization)}


@app.put("/v2/admin/policy/damping", response_model=ApiEnvelope)
def update_damping(payload: DampingUpdateRequest, authorization: str | None = Header(default=None)) -> dict:
    return {"code": 0, "data": service.update_damping(authorization, payload.decay_alpha)}


@app.put("/v2/admin/policy/operating", response_model=ApiEnvelope)
def update_operating_policy(payload: RewardPolicyUpdateRequest, authorization: str | None = Header(default=None)) -> dict:
    return {"code": 0, "data": service.update_operating_policy(authorization, payload.model_dump())}


@app.put("/v2/admin/policy/market-wallet", response_model=ApiEnvelope)
def update_market_wallet_policy(payload: MarketWalletPolicyUpdateRequest, authorization: str | None = Header(default=None)) -> dict:
    return {"code": 0, "data": service.update_market_wallet_policy(authorization, payload.model_dump())}


@app.put("/v2/admin/crown-multiplier", response_model=ApiEnvelope)
def update_crown_multiplier(payload: CrownMultiplierUpdateRequest, authorization: str | None = Header(default=None)) -> dict:
    return {"code": 0, "data": service.update_crown_multiplier(authorization, payload.code, payload.episode_reward_multiplier)}


@app.get("/admin", response_class=HTMLResponse, include_in_schema=False)
def admin_dashboard() -> str:
    return DASHBOARD_HTML


@app.post("/v2/wallet/exchange", response_model=ApiEnvelope)
def exchange_gems(payload: GemsExchangeRequest) -> dict:
    return {"code": 0, "data": service.exchange_gems(payload.user_id, payload.gems, payload.reference_id)}


@app.post("/v2/interstitials/shown", response_model=ApiEnvelope)
def interstitial_shown(payload: InterstitialShownRequest) -> dict:
    return {"code": 0, "data": service.record_interstitial(payload.user_id, payload.event_id, payload.placement_id, payload.episode_id)}


@app.post("/v2/withdrawals/preflight", response_model=ApiEnvelope)
def withdrawal_preflight(payload: WithdrawalPreflightRequest) -> dict:
    return {"code": 0, "data": service.withdrawal_preflight(payload.user_id)}
