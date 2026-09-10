from __future__ import annotations

from fastapi import FastAPI, Header, HTTPException, Query, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse

from app.catalog_loader import load_owned_catalog
from app.config import Settings
from app.local_media import LocalCoverStore, LocalMediaStore, LocalPlaybackSessionStore
from app.models import (
    DramaDetail,
    FeedPage,
    PlaybackSessionRequest,
    PlaybackSessionResponse,
    ProviderHealth,
    UnlockRequest,
    UnlockResponse,
)
from app.providers import FreeDramaProvider, PangleDramaProvider
from app.rewards import (
    AdClaimRequest,
    BootstrapRequest,
    ClaimRequest,
    RewardConfigStore,
    RewardEventRequest,
    RewardLedgerStore,
    RewardRules,
    RewardsService,
)
from app.service import ContentOrchestrator

settings = Settings.from_environment()
if settings.local_media_enabled and not settings.local_media_root:
    raise RuntimeError("启用 LOCAL_MEDIA_ENABLED 时必须同时设置 LOCAL_MEDIA_ROOT")

local_media_store = LocalMediaStore(settings.local_media_root) if settings.local_media_enabled else None
local_cover_store = LocalCoverStore(settings.local_cover_root) if settings.local_cover_root else None
local_playback_sessions = LocalPlaybackSessionStore() if settings.local_media_enabled else None
owned_records = load_owned_catalog(settings.owned_catalog_path) if settings.owned_catalog_path else None
orchestrator = ContentOrchestrator(
    free_drama=FreeDramaProvider(records=owned_records),
    pangle_drama=PangleDramaProvider(enabled=settings.pangle_content_enabled),
    local_playback_sessions=local_playback_sessions,
)
rewards_service = RewardsService(
    config_store=RewardConfigStore(settings.reward_rules_path),
    ledger_store=RewardLedgerStore(settings.reward_ledger_path),
    environment=settings.environment,
)

app = FastAPI(
    title="DramaFlow Content Service",
    version="0.2.0",
    description="统一编排自有剧与 Pangle 短剧内容来源的 API 框架。",
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=False,
    allow_methods=["GET", "POST", "PUT"],
    allow_headers=["Content-Type", "Authorization", "X-Device-Id"],
)


@app.get("/health")
def health() -> dict[str, str]:
    return {
        "status": "ok",
        "environment": settings.environment,
        "local_media": "enabled" if settings.local_media_enabled else "disabled",
        "local_covers": "enabled" if local_cover_store is not None else "disabled",
    }


@app.get("/v1/feed", response_model=FeedPage)
def get_feed(
    region: str = Query(min_length=2, max_length=2, pattern=r"^[A-Z]{2}$"),
    language: str | None = Query(default=None, min_length=2, max_length=8),
    cursor: str | None = Query(default=None),
    limit: int = Query(default=20, ge=1, le=50),
) -> FeedPage:
    return orchestrator.feed(region=region, language=language, cursor=cursor, limit=limit)


@app.get("/v1/dramas/{drama_id}", response_model=DramaDetail)
def get_drama(
    drama_id: str,
    region: str = Query(min_length=2, max_length=2, pattern=r"^[A-Z]{2}$"),
    user_id: str | None = Query(default=None, max_length=128),
) -> DramaDetail:
    return orchestrator.drama_detail(drama_id=drama_id, region=region, user_id=user_id)


@app.post("/v1/episodes/{episode_id}/playback-session", response_model=PlaybackSessionResponse)
def create_playback_session(
    episode_id: str,
    payload: PlaybackSessionRequest,
    request: Request,
) -> PlaybackSessionResponse:
    return orchestrator.playback_session(
        episode_id=episode_id,
        region=payload.region,
        user_id=payload.user_id,
        device_id=payload.device_id,
        request_base_url=str(request.base_url).rstrip("/"),
    )


@app.get("/v1/covers/{cover_path:path}")
def serve_local_cover(cover_path: str) -> FileResponse:
    """开发阶段提供片单相对封面的局域网访问。"""
    if local_cover_store is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="本地封面服务未启用")
    file_path = local_cover_store.resolve(cover_path)
    media_types = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp", ".gif": "image/gif"}
    return FileResponse(path=file_path, media_type=media_types.get(file_path.suffix.lower()), content_disposition_type="inline")


@app.get("/v1/dev/local-media/{episode_id}")
def stream_local_media(episode_id: str, token: str = Query(min_length=16)) -> FileResponse:
    """仅开发环境的本地媒体端点；不可在生产环境或公网常驻部署中启用。"""
    if local_media_store is None or local_playback_sessions is None:
        from fastapi import HTTPException, status

        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="本地媒体测试模式未启用")
    relative_path = local_playback_sessions.resolve(token=token, episode_id=episode_id)
    file_path = local_media_store.resolve(relative_path)
    return FileResponse(
        path=file_path,
        media_type="video/mp4" if file_path.suffix.lower() in {".mp4", ".m4v"} else None,
        filename=file_path.name,
        content_disposition_type="inline",
    )


@app.post("/v1/episodes/{episode_id}/unlock", response_model=UnlockResponse)
def unlock_episode(episode_id: str, request: UnlockRequest) -> UnlockResponse:
    return orchestrator.unlock_episode(
        episode_id=episode_id,
        region=request.region,
        user_id=request.user_id,
        method=request.method,
        proof=request.proof,
    )


@app.get("/v1/providers/health", response_model=list[ProviderHealth])
def provider_health() -> list[ProviderHealth]:
    return orchestrator.provider_health()


@app.post("/v1/rewards/bootstrap")
def rewards_bootstrap(payload: BootstrapRequest) -> dict:
    """获取当前用户的规则版本、钱包、任务、签到、圆环和奖励流水。"""
    return rewards_service.bootstrap(payload)


@app.post("/v1/rewards/events")
def record_reward_event(payload: RewardEventRequest) -> dict:
    """记录可验证的观看事件；使用 event_id 幂等，网络重试不会重复计入进度。"""
    return rewards_service.event(payload)


@app.post("/v1/rewards/ad-claims")
def create_ad_claim(payload: AdClaimRequest) -> dict:
    """将激励广告完成结果兑换为短期、一次性的服务端权益令牌。"""
    return rewards_service.ad_claim(payload)


@app.post("/v1/rewards/claims")
def claim_reward(payload: ClaimRequest) -> dict:
    """领取任务、签到、圆环奖励或执行广告解锁；服务端以规则版本和账本结算。"""
    return rewards_service.claim(payload)


@app.get("/v1/rewards/ledger")
def reward_ledger(user_id: str = Query(min_length=1, max_length=128)) -> dict:
    return {"items": rewards_service.ledger(user_id)}


def _require_admin(authorization: str | None) -> None:
    expected = settings.admin_config_token
    if not expected:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="管理写接口未启用；请在服务端设置 ADMIN_CONFIG_TOKEN",
        )
    if authorization != f"Bearer {expected}":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="管理员令牌无效")


@app.get("/v1/admin/rewards/config", response_model=RewardRules)
def get_reward_config(authorization: str | None = Header(default=None)) -> RewardRules:
    _require_admin(authorization)
    return rewards_service.current_rules()


@app.put("/v1/admin/rewards/config", response_model=RewardRules)
def replace_reward_config(payload: RewardRules, authorization: str | None = Header(default=None)) -> RewardRules:
    _require_admin(authorization)
    return rewards_service.replace_rules(payload)


@app.post("/v1/admin/rewards/config/reload", response_model=RewardRules)
def reload_reward_config(authorization: str | None = Header(default=None)) -> RewardRules:
    _require_admin(authorization)
    return rewards_service.reload_rules()
