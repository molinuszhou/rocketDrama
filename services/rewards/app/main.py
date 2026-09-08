from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.config import Settings
from app.schemas import (
    ApiEnvelope,
    BootstrapRequest,
    CheckinRequest,
    GemsExchangeRequest,
    HealthResponse,
    InterstitialShownRequest,
    RewardedCompleteRequest,
    WelcomeBonusRequest,
    WatchEventRequest,
    WithdrawalPreflightRequest,
)
from app.service import RewardsService, ServiceError

settings = Settings.from_environment()
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
    allow_methods=["GET", "POST"],
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
    return {"code": 0, "data": service.bootstrap(payload.user_id)}


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


@app.post("/v2/checkins", response_model=ApiEnvelope)
def checkin(payload: CheckinRequest) -> dict:
    return {"code": 0, "data": service.checkin(payload.user_id)}


@app.post("/v2/welcome/claim", response_model=ApiEnvelope)
def claim_welcome_bonus(payload: WelcomeBonusRequest) -> dict:
    return {"code": 0, "data": service.claim_welcome_bonus(payload.user_id)}


@app.post("/v2/wallet/exchange", response_model=ApiEnvelope)
def exchange_gems(payload: GemsExchangeRequest) -> dict:
    return {"code": 0, "data": service.exchange_gems(payload.user_id, payload.gems, payload.reference_id)}


@app.post("/v2/interstitials/shown", response_model=ApiEnvelope)
def interstitial_shown(payload: InterstitialShownRequest) -> dict:
    return {"code": 0, "data": service.record_interstitial(payload.user_id, payload.event_id, payload.placement_id)}


@app.post("/v2/withdrawals/preflight", response_model=ApiEnvelope)
def withdrawal_preflight(payload: WithdrawalPreflightRequest) -> dict:
    return {"code": 0, "data": service.withdrawal_preflight(payload.user_id)}
