from __future__ import annotations

import csv
import io
import json
from datetime import date

from fastapi import FastAPI, Header, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse, Response

from app.admin_dashboard import DASHBOARD_HTML
from app.config import Settings
from app.migrations import apply_runtime_migrations
from app.schemas import (
    ApiEnvelope,
    AdMediationEventRequest,
    AdUnavailableEpisodeUnlockRequest,
    AttributionUpdateRequest,
    AdminLoginRequest,
    BootstrapRequest,
    CheckinRequest,
    ClaimAllEpisodeRewardsRequest,
    CrownMultiplierUpdateRequest,
    DampingUpdateRequest,
    EpisodeTaskClaimRequest,
    EpisodeRewardClaimRequest,
    GemsExchangeRequest,
    HealthResponse,
    InterstitialIncentiveSettlementRequest,
    MarketWalletPolicyUpdateRequest,
    InterstitialShownRequest,
    RewardedCompleteRequest,
    RewardedFallbackSettlementRequest,
    RewardPolicyUpdateRequest,
    RewardedSkippedRequest,
    ReportingDeliveryEventRequest,
    TenRoundClaimRequest,
    WelcomeBonusRequest,
    WatchEventRequest,
    WithdrawalAccountRequest,
    WithdrawalRequest,
    WithdrawalPreflightRequest,
)
from app.geo import GeoCountryResolver
from app.service import RewardsService, ServiceError

settings = Settings.from_environment()
apply_runtime_migrations(settings)
service = RewardsService(settings)
geo_country_resolver = GeoCountryResolver(settings.geoip_db_path)


def _csv_response(filename: str, header: list[str], rows: list[list[object]]) -> Response:
    """Return a UTF-8-with-BOM CSV that opens correctly in spreadsheet clients."""

    output = io.StringIO(newline="")
    writer = csv.writer(output)
    writer.writerow(header)
    writer.writerows(rows)
    content = ("\ufeff" + output.getvalue()).encode("utf-8")
    return Response(
        content=content,
        media_type="text/csv; charset=utf-8",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


def client_ip(request: Request) -> str:
    """Read the first client IP from an Nginx-sanitised forwarding chain."""
    forwarded = request.headers.get("x-forwarded-for", "")
    if forwarded:
        return forwarded.split(",", 1)[0].strip()
    return request.headers.get("x-real-ip", "").strip()

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
    allow_headers=["Content-Type", "Authorization", "X-Device-Id", "X-Rewards-Session"],
)


@app.middleware("http")
async def require_user_session(request: Request, call_next):
    """Bind anonymous user APIs to a server-signed token instead of trusting a UID alone."""
    path = request.url.path
    session_protocol_requested = request.headers.get("x-rewards-session") == "v1"
    needs_session = settings.require_user_session or session_protocol_requested
    if request.method in {"POST", "PUT", "PATCH", "DELETE"} and path.startswith("/v2/") and needs_session:
        exempt = {"/v2/bootstrap"}
        if not path.startswith("/v2/admin/") and path not in exempt:
            body = await request.body()
            try:
                payload = json.loads(body.decode("utf-8") or "{}")
            except (UnicodeDecodeError, json.JSONDecodeError):
                return JSONResponse(status_code=400, content={"code": 1000, "msg": "请求体格式无效"})
            identity = payload.get("user_uid") if path == "/v2/operations/reporting-delivery-events" else payload.get("user_id")
            if not isinstance(identity, str) or not identity.strip():
                return JSONResponse(status_code=401, content={"code": 1904, "msg": "缺少用户会话身份"})
            try:
                service.verify_user_session(identity, request.headers.get("authorization"))
            except ServiceError as error:
                return JSONResponse(status_code=error.status_code, content={"code": error.code, "msg": error.message})
            request._body = body
    return await call_next(request)


@app.exception_handler(ServiceError)
async def service_error_handler(_: Request, error: ServiceError) -> JSONResponse:
    return JSONResponse(status_code=error.status_code, content={"code": error.code, "msg": error.message})


@app.get("/health", response_model=HealthResponse)
def health() -> dict:
    payload = service.health()
    payload["geoip"] = geo_country_resolver.diagnostics()
    return payload


@app.post("/v2/bootstrap", response_model=ApiEnvelope)
def bootstrap(payload: BootstrapRequest, request: Request) -> dict:
    return {
        "code": 0,
        "data": service.bootstrap(
            payload.user_id,
            payload.region,
            geo_country=geo_country_resolver.country_for_ip(client_ip(request)),
            attribution=payload.attribution.model_dump() if payload.attribution else None,
            session_token=request.headers.get("authorization"),
            session_required=settings.require_user_session or request.headers.get("x-rewards-session") == "v1",
        ),
    }


@app.post("/v2/admin/login", response_model=ApiEnvelope)
def admin_login(payload: AdminLoginRequest) -> dict:
    return {"code": 0, "data": service.admin_login(payload.username, payload.password)}


@app.post("/v2/attribution", response_model=ApiEnvelope)
def update_attribution(payload: AttributionUpdateRequest) -> dict:
    return {
        "code": 0,
        "data": service.update_attribution(
            user_id=payload.user_id,
            appsflyer_uid=payload.appsflyer_uid,
            af_status=payload.af_status,
            media_source=payload.media_source,
            campaign=payload.campaign,
            channel=payload.channel,
        ),
    }


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


@app.post("/v2/ads/rewarded/fallback-settlement", response_model=ApiEnvelope)
def settle_rewarded_fallback(payload: RewardedFallbackSettlementRequest) -> dict:
    return {
        "code": 0,
        "data": service.settle_rewarded_fallback(
            user_id=payload.user_id,
            interstitial_event_id=payload.interstitial_event_id,
            original_placement_id=payload.original_placement_id,
            target_id=payload.target_id,
            episode_index=payload.episode_index,
        ),
    }


@app.post("/v2/ads/interstitial/incentive-settlement", response_model=ApiEnvelope)
def settle_interstitial_incentive(payload: InterstitialIncentiveSettlementRequest) -> dict:
    return {
        "code": 0,
        "data": service.settle_interstitial_incentive(
            user_id=payload.user_id,
            interstitial_event_id=payload.interstitial_event_id,
            original_placement_id=payload.original_placement_id,
            target_id=payload.target_id,
            episode_index=payload.episode_index,
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


@app.post("/v2/episodes/ad-unavailable-unlock", response_model=ApiEnvelope)
def unlock_episode_when_ad_unavailable(payload: AdUnavailableEpisodeUnlockRequest) -> dict:
    return {
        "code": 0,
        "data": service.allow_episode_unlock_when_ad_unavailable(
            user_id=payload.user_id,
            episode_id=payload.episode_id,
            episode_index=payload.episode_index,
        ),
    }


@app.post("/v2/checkins", response_model=ApiEnvelope)
def checkin(payload: CheckinRequest) -> dict:
    return {"code": 0, "data": service.checkin(payload.user_id)}


@app.post("/v2/welcome/claim", response_model=ApiEnvelope)
def claim_welcome_bonus(payload: WelcomeBonusRequest) -> dict:
    return {"code": 0, "data": service.claim_welcome_bonus(payload.user_id)}


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
def admin_dashboard() -> HTMLResponse:
    return HTMLResponse(
        DASHBOARD_HTML,
        headers={
            "Content-Security-Policy": "default-src 'self'; base-uri 'none'; frame-ancestors 'none'; object-src 'none'; form-action 'self'; connect-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'",
            "Referrer-Policy": "no-referrer",
            "X-Content-Type-Options": "nosniff",
        },
    )


@app.post("/v2/wallet/exchange", response_model=ApiEnvelope)
def exchange_gems(payload: GemsExchangeRequest) -> dict:
    return {"code": 0, "data": service.exchange_gems(payload.user_id, payload.gems, payload.reference_id)}


@app.post("/v2/interstitials/shown", response_model=ApiEnvelope)
def interstitial_shown(payload: InterstitialShownRequest) -> dict:
    return {"code": 0, "data": service.record_interstitial(payload.user_id, payload.event_id, payload.placement_id, payload.episode_id)}


@app.post("/v2/operations/ad-mediation-events", response_model=ApiEnvelope)
def record_ad_mediation_event(payload: AdMediationEventRequest) -> dict:
    return {
        "code": 0,
        "data": service.record_ad_mediation_event(
            client_event_id=payload.client_event_id,
            install_id_hash=payload.install_id_hash,
            event_type=payload.event_type,
            logical_placement=payload.logical_placement,
            topon_placement_id=payload.topon_placement_id,
            ad_format=payload.ad_format,
            ad_network=payload.ad_network,
            ad_source_id=payload.ad_source_id,
            app_version=payload.app_version,
            currency=payload.currency,
            ecpm=payload.ecpm,
            reporting_targets=payload.reporting_targets,
            occurred_at_ms=payload.occurred_at_ms,
        ),
    }


@app.post("/v2/operations/reporting-delivery-events", response_model=ApiEnvelope)
def record_reporting_delivery_event(payload: ReportingDeliveryEventRequest) -> dict:
    return {
        "code": 0,
        "data": service.record_reporting_delivery_event(**payload.model_dump()),
    }


@app.get("/v2/admin/operations/ad-mediation-daily", response_model=ApiEnvelope)
def admin_ad_mediation_daily(
    authorization: str | None = Header(default=None),
    start_date: date | None = None,
    end_date: date | None = None,
    summary_page: int = 1,
    summary_page_size: int = 50,
    raw_page: int = 1,
    raw_page_size: int = 100,
    logical_placement: str = "",
    topon_placement_id: str = "",
    ad_format: str = "",
    ad_network: str = "",
    event_type: str = "",
    reporting_target: str = "",
    app_version: str = "",
    currency: str = "",
) -> dict:
    return {
        "code": 0,
        "data": service.admin_ad_mediation_daily_metrics(
            authorization=authorization,
            start_date=start_date,
            end_date=end_date,
            summary_page=summary_page,
            summary_page_size=summary_page_size,
            raw_page=raw_page,
            raw_page_size=raw_page_size,
            logical_placement=logical_placement,
            topon_placement_id=topon_placement_id,
            ad_format=ad_format,
            ad_network=ad_network,
            event_type=event_type,
            reporting_target=reporting_target,
            app_version=app_version,
            currency=currency,
        ),
    }


@app.get("/v2/admin/users", response_model=ApiEnvelope)
def admin_users(
    authorization: str | None = Header(default=None),
    query: str = "",
    market_code: str = "",
    attribution_surface: str = "",
    geo_country: str = "",
    created_start: date | None = None,
    created_end: date | None = None,
    limit: int = 50,
    offset: int = 0,
) -> dict:
    return {
        "code": 0,
        "data": service.admin_users(
            authorization,
            query=query,
            market_code=market_code,
            attribution_surface=attribution_surface,
            geo_country=geo_country,
            created_start=created_start,
            created_end=created_end,
            limit=limit,
            offset=offset,
        ),
    }


@app.get("/v2/admin/users/metrics", response_model=ApiEnvelope)
def admin_user_metrics(
    authorization: str | None = Header(default=None),
    query: str = "",
    market_code: str = "",
    attribution_surface: str = "",
    geo_country: str = "",
    start_date: date | None = None,
    end_date: date | None = None,
) -> dict:
    return {
        "code": 0,
        "data": service.admin_user_metrics(
            authorization,
            query=query,
            market_code=market_code,
            attribution_surface=attribution_surface,
            geo_country=geo_country,
            start_date=start_date,
            end_date=end_date,
        ),
    }


@app.get("/v2/admin/users/export")
def admin_users_export(
    authorization: str | None = Header(default=None),
    query: str = "",
    market_code: str = "",
    attribution_surface: str = "",
    geo_country: str = "",
    created_start: date | None = None,
    created_end: date | None = None,
) -> Response:
    data = service.admin_users(
        authorization,
        query=query,
        market_code=market_code,
        attribution_surface=attribution_surface,
        geo_country=geo_country,
        created_start=created_start,
        created_end=created_end,
        limit=10_000,
        offset=0,
    )
    if data["total"] > len(data["rows"]):
        raise ServiceError(2318, "导出结果超过 10,000 条，请缩小筛选范围后重试", 422)
    rows: list[list[object]] = []
    for item in data["rows"]:
        wallet = item["wallet"]
        activity = item["activity"]
        attribution = item.get("attribution") or {}
        currency = item.get("currency") or {}
        rows.append([
            item["user_id"], item.get("created_at", ""), item.get("updated_at", ""), item.get("market_code", ""), item.get("geo_country", ""), currency.get("code", ""),
            wallet.get("cash_wallet", ""), wallet.get("available_balance", ""), wallet.get("reward_progress_units", 0),
            activity.get("watch_count", 0), activity.get("rewarded_count", 0), activity.get("interstitial_count", 0), activity.get("ledger_count", 0),
            attribution.get("af_status", ""), attribution.get("appsflyer_uid", ""), attribution.get("media_source", ""), attribution.get("campaign", ""), attribution.get("channel", ""), attribution.get("surface", ""),
        ])
    return _csv_response(
        "joyedrama-users.csv",
        ["匿名用户 ID", "注册时间", "最近活动", "市场", "GeoIP 国家", "币种", "钱包余额", "可用余额", "奖励进度", "观看次数", "激励完成", "插屏展示", "账本条数", "归因状态", "AppsFlyer UID", "媒体来源", "Campaign", "Channel", "A/B 面"],
        rows,
    )


@app.get("/v2/admin/operations/reporting-delivery", response_model=ApiEnvelope)
def admin_reporting_delivery(
    authorization: str | None = Header(default=None),
    start_date: date | None = None,
    end_date: date | None = None,
    page: int = 1,
    page_size: int = 100,
    provider: str = "",
    event_name: str = "",
    status: str = "",
    user_uid: str = "",
    logical_placement: str = "",
    ad_unit_id: str = "",
) -> dict:
    return {
        "code": 0,
        "data": service.admin_reporting_delivery_metrics(
            authorization, start_date, end_date, page, page_size,
            provider, event_name, status, user_uid, logical_placement, ad_unit_id,
        ),
    }


@app.get("/v2/admin/operations/reporting-delivery/export")
def admin_reporting_delivery_export(
    authorization: str | None = Header(default=None),
    start_date: date | None = None,
    end_date: date | None = None,
    provider: str = "",
    event_name: str = "",
    status: str = "",
    user_uid: str = "",
    logical_placement: str = "",
    ad_unit_id: str = "",
) -> Response:
    data = service.admin_reporting_delivery_metrics(
        authorization, start_date, end_date, 1, 10_000,
        provider, event_name, status, user_uid, logical_placement, ad_unit_id,
    )
    if data["total"] > len(data["rows"]):
        raise ServiceError(2319, "导出结果超过 10,000 条，请缩小筛选范围后重试", 422)
    rows = [[
        item.get("client_occurred_at", ""), item.get("accepted_at", ""), item.get("user_uid", ""), item.get("provider", ""), item.get("event_name", ""), item.get("status", ""),
        item.get("logical_placement", ""), item.get("ad_unit_id", ""), item.get("currency", ""), item.get("ecpm", ""), item.get("value", ""), item.get("error_code", ""),
    ] for item in data["rows"]]
    return _csv_response(
        "joyedrama-reporting-delivery.csv",
        ["客户端发生时间", "服务端接收时间", "匿名 UID", "渠道", "事件", "状态", "广告位", "广告单元", "币种", "eCPM", "Value", "错误码"],
        rows,
    )

@app.post("/v2/withdrawals/preflight", response_model=ApiEnvelope)
def withdrawal_preflight(payload: WithdrawalPreflightRequest) -> dict:
    return {"code": 0, "data": service.withdrawal_preflight(payload.user_id)}


@app.post("/v2/withdrawals/account", response_model=ApiEnvelope)
def save_withdrawal_account(payload: WithdrawalAccountRequest) -> dict:
    return {"code": 0, "data": service.save_withdrawal_account(**payload.model_dump())}


@app.post("/v2/withdrawals/request", response_model=ApiEnvelope)
def create_withdrawal_request(payload: WithdrawalRequest) -> dict:
    return {"code": 0, "data": service.create_withdrawal_request(**payload.model_dump())}
