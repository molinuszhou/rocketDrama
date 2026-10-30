from __future__ import annotations

import csv
import io
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from typing import Any

from fastapi import Header
from fastapi.responses import HTMLResponse, Response

from app.admin_auth import csv_safe_cell
from app.admin_dashboard import USER_TIMELINE_HTML
from app.main import app as _all_routes_app
from app.main import service
from app.service import ServiceError
from app.service_roles import configure_admin_routes

# The management process deliberately does not expose mobile reward, wallet, or
# advertising-write routes. Nginx sends only /admin and /v2/admin traffic here.
app = configure_admin_routes(_all_routes_app)


# This query is deliberately owned by rewards-admin.  The client delivery trail
# predates the app-version column, while user_behavior_events already retains a
# privacy-minimised versioned event stream keyed by the same anonymous user UID.
# A reporting delivery is associated with the latest observed client behavior at
# or shortly after its client timestamp; the short future window handles normal
# asynchronous client batching without treating a later app upgrade as a match.
_VERSION_LOOKUP_SQL = """
LEFT JOIN LATERAL (
    SELECT behavior.app_version
    FROM user_behavior_events AS behavior
    WHERE behavior.user_id = reporting.user_uid
      AND behavior.client_occurred_at <= reporting.client_occurred_at + INTERVAL '15 minutes'
    ORDER BY behavior.client_occurred_at DESC
    LIMIT 1
) AS behavior_version ON TRUE
"""


def _admin_csv_response(filename: str, header: list[str], rows: list[list[object]]) -> Response:
    """Return a spreadsheet-safe UTF-8 CSV without depending on the API entrypoint."""

    output = io.StringIO(newline="")
    writer = csv.writer(output)
    writer.writerow([csv_safe_cell(value) for value in header])
    writer.writerows([[csv_safe_cell(value) for value in row] for row in rows])
    return Response(
        content=("\ufeff" + output.getvalue()).encode("utf-8"),
        media_type="text/csv; charset=utf-8",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


def _versioned_reporting_delivery_metrics(
    authorization: str | None,
    *,
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
    app_version: str = "",
) -> dict[str, Any]:
    """Read delivery events with an Admin-only, read-only behavior-version association."""

    service._verify_admin_token(authorization)
    default_end = datetime.now(UTC).date()
    actual_end = end_date or default_end
    actual_start = start_date or actual_end - timedelta(days=6)
    if actual_start > actual_end:
        raise ServiceError(2313, "上报记录开始日期不能晚于结束日期", 422)
    if actual_end - actual_start > timedelta(days=90):
        raise ServiceError(2314, "上报记录最大查询范围为 90 天", 422)

    actual_page = max(int(page), 1)
    actual_limit = min(max(int(page_size), 1), 10_000)
    actual_offset = (actual_page - 1) * actual_limit
    base_filters = [
        "reporting.client_occurred_at >= %s",
        "reporting.client_occurred_at < %s",
    ]
    base_params: list[Any] = [
        service._utc_start_of_day(actual_start),
        service._utc_start_of_day(actual_end + timedelta(days=1)),
    ]
    for column, value in (
        ("provider", provider),
        ("event_name", event_name),
        ("status", status),
        ("logical_placement", logical_placement),
        ("ad_unit_id", ad_unit_id),
    ):
        normalized = value.strip()
        if normalized:
            base_filters.append(f"reporting.{column} = %s")
            base_params.append(normalized[:160])
    if user_uid.strip():
        base_filters.append("reporting.user_uid ILIKE %s")
        base_params.append(f"%{user_uid.strip()[:128]}%")

    base_where = " WHERE " + " AND ".join(base_filters)
    normalized_version = app_version.strip()[:64]
    version_where = ""
    version_params: list[Any] = []
    if normalized_version:
        version_where = " AND COALESCE(behavior_version.app_version, '') = %s"
        version_params.append(normalized_version)

    with service._transaction() as connection:
        rows = service._all(
            connection,
            f"""
            SELECT reporting.user_uid, reporting.provider, reporting.event_name, reporting.status,
                   reporting.logical_placement, reporting.ad_unit_id, reporting.error_code,
                   reporting.currency, reporting.ecpm, reporting.reported_value,
                   reporting.client_occurred_at, reporting.accepted_at,
                   COALESCE(behavior_version.app_version, '') AS app_version
            FROM reporting_delivery_events AS reporting
            {_VERSION_LOOKUP_SQL}
            {base_where}{version_where}
            ORDER BY reporting.client_occurred_at DESC, reporting.accepted_at DESC
            LIMIT %s OFFSET %s
            """,
            tuple(base_params + version_params + [actual_limit, actual_offset]),
        )
        totals = service._one(
            connection,
            f"""
            SELECT
                COUNT(*) AS event_count,
                COUNT(*) FILTER (WHERE reporting.status = 'SUCCESS') AS success_count,
                COUNT(*) FILTER (WHERE reporting.status IN ('FAILED', 'FINAL_FAILED')) AS failure_count,
                COUNT(*) FILTER (WHERE reporting.status = 'RETRYING') AS retrying_count
            FROM reporting_delivery_events AS reporting
            {_VERSION_LOOKUP_SQL}
            {base_where}{version_where}
            """,
            tuple(base_params + version_params),
        ) or {}
        version_rows = service._all(
            connection,
            f"""
            SELECT COALESCE(behavior_version.app_version, '') AS app_version,
                   COUNT(*) AS event_count
            FROM reporting_delivery_events AS reporting
            {_VERSION_LOOKUP_SQL}
            {base_where}
            GROUP BY COALESCE(behavior_version.app_version, '')
            ORDER BY event_count DESC, app_version DESC
            """,
            tuple(base_params),
        )

    def serialize(row: dict[str, Any]) -> dict[str, Any]:
        return {
            **row,
            "app_version": row.get("app_version") or "",
            "currency": str(row.get("currency") or "").strip(),
            "ecpm": format(Decimal(str(row["ecpm"])), "f") if row.get("ecpm") is not None else "",
            "value": format(Decimal(str(row.get("reported_value", 0))), "f"),
            "client_occurred_at": row["client_occurred_at"].isoformat(),
            "accepted_at": row["accepted_at"].isoformat(),
        }

    unknown_version_count = sum(int(item.get("event_count", 0)) for item in version_rows if not item.get("app_version"))
    version_options = [
        {"app_version": str(item["app_version"]), "event_count": int(item["event_count"])}
        for item in version_rows
        if item.get("app_version")
    ]
    return {
        "start_date": actual_start.isoformat(),
        "end_date": actual_end.isoformat(),
        "page": actual_page,
        "page_size": actual_limit,
        "offset": actual_offset,
        "total": int(totals.get("event_count", 0)),
        "filters": {
            "provider": provider,
            "event_name": event_name,
            "status": status,
            "user_uid": user_uid,
            "logical_placement": logical_placement,
            "ad_unit_id": ad_unit_id,
            "app_version": normalized_version,
        },
        "totals": {
            key: int(totals.get(key, 0))
            for key in ("event_count", "success_count", "failure_count", "retrying_count")
        },
        "version_options": version_options,
        "unknown_version_count": unknown_version_count,
        "rows": [serialize(row) for row in rows],
        "disclaimer": (
            "关联版本来自同一匿名 UID 在该投递发生时或其后 15 分钟内最近成功写入的客户端行为记录；"
            "它用于运营筛选与排障，不是广告 SDK 回调的原始版本字段。"
        ),
    }


@app.get("/v2/admin/operations/reporting-delivery-versioned")
def admin_reporting_delivery_versioned(
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
    app_version: str = "",
) -> dict[str, Any]:
    return {
        "code": 0,
        "data": _versioned_reporting_delivery_metrics(
            authorization,
            start_date=start_date,
            end_date=end_date,
            page=page,
            page_size=page_size,
            provider=provider,
            event_name=event_name,
            status=status,
            user_uid=user_uid,
            logical_placement=logical_placement,
            ad_unit_id=ad_unit_id,
            app_version=app_version,
        ),
    }


@app.get("/v2/admin/operations/reporting-delivery-versioned/export")
def admin_reporting_delivery_versioned_export(
    authorization: str | None = Header(default=None),
    start_date: date | None = None,
    end_date: date | None = None,
    provider: str = "",
    event_name: str = "",
    status: str = "",
    user_uid: str = "",
    logical_placement: str = "",
    ad_unit_id: str = "",
    app_version: str = "",
) -> Response:
    data = _versioned_reporting_delivery_metrics(
        authorization,
        start_date=start_date,
        end_date=end_date,
        page=1,
        page_size=10_000,
        provider=provider,
        event_name=event_name,
        status=status,
        user_uid=user_uid,
        logical_placement=logical_placement,
        ad_unit_id=ad_unit_id,
        app_version=app_version,
    )
    if data["total"] > len(data["rows"]):
        raise ServiceError(2319, "导出结果超过 10,000 条，请缩小筛选范围后重试", 422)
    csv_rows = [[
        item.get("client_occurred_at", ""),
        item.get("accepted_at", ""),
        item.get("user_uid", ""),
        item.get("app_version", ""),
        item.get("provider", ""),
        item.get("event_name", ""),
        item.get("status", ""),
        item.get("logical_placement", ""),
        item.get("ad_unit_id", ""),
        item.get("currency", ""),
        item.get("ecpm", ""),
        item.get("value", ""),
        item.get("error_code", ""),
    ] for item in data["rows"]]
    return _admin_csv_response(
        "joyedrama-reporting-delivery-with-version.csv",
        [
            "客户端发生时间", "服务端接收时间", "匿名 UID", "关联客户端版本", "渠道", "事件", "状态",
            "广告位", "广告单元", "币种", "eCPM", "Value", "错误码",
        ],
        csv_rows,
    )


_SCENE_SURFACE_SQL = """
CASE
    WHEN COALESCE(BTRIM(u.attribution_status), '') = '' THEN 'PENDING'
    WHEN u.attribution_surface IN ('A', 'B') THEN u.attribution_surface
    ELSE 'UNKNOWN'
END
"""


def _admin_first_day_ad_scene_ipu_metrics(
    authorization: str | None,
    *,
    start_date: date | None = None,
    end_date: date | None = None,
    logical_placement: str = "",
    ad_format: str = "",
    app_version: str = "",
    attribution_surface: str = "",
) -> dict[str, Any]:
    """Read first-registration-day AD_SHOWN telemetry by product entry point.

    ``ad_mediation_events`` deliberately does not retain a user identifier, so it
    remains the source for network/revenue reconciliation only. This Admin-only
    analysis instead uses the privacy-minimised ``AD_SHOWN`` behavior trail, which
    is keyed by the anonymous Rewards user and can safely support cohort IPU.
    """

    service._verify_admin_token(authorization)
    actual_end = end_date or datetime.now(UTC).date()
    actual_start = start_date or actual_end - timedelta(days=6)
    if actual_start > actual_end:
        raise ServiceError(2330, "首日场景 IPU 开始日期不能晚于结束日期", 422)
    if actual_end - actual_start > timedelta(days=90):
        raise ServiceError(2331, "首日场景 IPU 最大查询范围为 90 天", 422)

    normalized_placement = logical_placement.strip()[:80]
    normalized_format = ad_format.strip().lower()[:32]
    normalized_version = app_version.strip()[:64]
    normalized_surface = attribution_surface.strip().upper()
    if normalized_surface and normalized_surface not in {"A", "B", "PENDING", "UNKNOWN"}:
        raise ServiceError(2332, "广告场景归因面仅支持 A、B、PENDING 或 UNKNOWN", 422)

    registration_start = service._utc_start_of_day(actual_start)
    registration_end = service._utc_start_of_day(actual_end + timedelta(days=1))
    cohort_filters = ["u.created_at >= %s", "u.created_at < %s"]
    cohort_params: list[Any] = [registration_start, registration_end]
    if normalized_surface:
        cohort_filters.append(f"({_SCENE_SURFACE_SQL}) = %s")
        cohort_params.append(normalized_surface)
    cohort_where = " WHERE " + " AND ".join(cohort_filters)
    cohort_cte = f"""
        WITH cohort AS (
            SELECT
                u.user_id,
                u.created_at,
                {_SCENE_SURFACE_SQL} AS attribution_segment
            FROM users AS u
            {cohort_where}
        )
    """

    shown_filters = [
        "b.event_category = 'AD'",
        "b.event_name = 'AD_SHOWN'",
        "b.client_occurred_at >= %s",
        "b.client_occurred_at < %s",
        "b.client_occurred_at >= c.created_at",
        "b.client_occurred_at < (((c.created_at AT TIME ZONE 'UTC')::date + 1)::timestamp AT TIME ZONE 'UTC')",
    ]
    shown_params: list[Any] = [registration_start, registration_end]
    if normalized_placement:
        shown_filters.append("COALESCE(b.properties ->> 'placement_id', '') = %s")
        shown_params.append(normalized_placement)
    if normalized_format:
        shown_filters.append("COALESCE(b.properties ->> 'ad_format', '') = %s")
        shown_params.append(normalized_format)
    if normalized_version:
        shown_filters.append("b.app_version = %s")
        shown_params.append(normalized_version)
    shown_where = " WHERE " + " AND ".join(shown_filters)
    shown_cte = f"""
        {cohort_cte}, shown AS (
            SELECT
                b.user_id,
                COALESCE(NULLIF(BTRIM(b.properties ->> 'placement_id'), ''), 'unknown') AS logical_placement,
                COALESCE(NULLIF(BTRIM(b.properties ->> 'ad_format'), ''), 'unknown') AS ad_format,
                c.attribution_segment,
                COALESCE(NULLIF(BTRIM(b.properties ->> 'ad_interaction_id'), ''), b.event_id) AS impression_key
            FROM user_behavior_events AS b
            JOIN cohort AS c ON c.user_id = b.user_id
            {shown_where}
        )
    """

    with service._transaction() as connection:
        cohort_total = service._one(
            connection,
            f"{cohort_cte} SELECT COUNT(*) AS cohort_users FROM cohort",
            tuple(cohort_params),
        ) or {}
        totals = service._one(
            connection,
            f"""
            {shown_cte}
            SELECT
                COUNT(DISTINCT impression_key) AS shown_count,
                COUNT(DISTINCT user_id) AS reached_users
            FROM shown
            """,
            tuple(cohort_params + shown_params),
        ) or {}
        rows = service._all(
            connection,
            f"""
            {shown_cte}
            SELECT
                logical_placement,
                ad_format,
                attribution_segment,
                COUNT(DISTINCT impression_key) AS shown_count,
                COUNT(DISTINCT user_id) AS reached_users
            FROM shown
            GROUP BY logical_placement, ad_format, attribution_segment
            ORDER BY shown_count DESC, logical_placement ASC, ad_format ASC, attribution_segment ASC
            """,
            tuple(cohort_params + shown_params),
        )

    cohort_users = int(cohort_total.get("cohort_users", 0))
    shown_count = int(totals.get("shown_count", 0))
    reached_users = int(totals.get("reached_users", 0))
    surface_labels = {
        "A": "A 面（已确认）",
        "B": "B 面（已确认）",
        "PENDING": "待归因（客户端默认 A）",
        "UNKNOWN": "归因面未标记",
    }

    def ratio(numerator: int, denominator: int) -> float:
        return round(numerator / denominator, 6) if denominator else 0.0

    scene_rows = []
    for row in rows:
        scene_shown = int(row.get("shown_count", 0))
        scene_reached = int(row.get("reached_users", 0))
        segment = str(row.get("attribution_segment") or "UNKNOWN")
        scene_rows.append({
            "logical_placement": str(row.get("logical_placement") or "unknown"),
            "ad_format": str(row.get("ad_format") or "unknown"),
            "attribution_segment": segment,
            "attribution_segment_label": surface_labels.get(segment, surface_labels["UNKNOWN"]),
            "shown_count": scene_shown,
            "reached_users": scene_reached,
            "cohort_ipu_contribution": ratio(scene_shown, cohort_users),
            "reach_rate": ratio(scene_reached, cohort_users),
            "reached_user_ipu": ratio(scene_shown, scene_reached),
        })
    return {
        "start_date": actual_start.isoformat(),
        "end_date": actual_end.isoformat(),
        "cohort_users": cohort_users,
        "shown_count": shown_count,
        "reached_users": reached_users,
        "first_day_ipu": ratio(shown_count, cohort_users),
        "reach_rate": ratio(reached_users, cohort_users),
        "rows": scene_rows,
        "filters": {
            "logical_placement": normalized_placement,
            "ad_format": normalized_format,
            "app_version": normalized_version,
            "attribution_surface": normalized_surface,
        },
        "definition": (
            "首日按用户注册所在 UTC+0 自然日计算：仅计入注册后发生的 AD_SHOWN，"
            "以 ad_interaction_id 去重；场景 IPU 贡献 = 场景展示 ÷ 注册 cohort，"
            "各行可相加得到本 cohort 的首日总 IPU。"
        ),
        "coverage": (
            "数据来自用户行为埋点，不含仅存在于 TopOn 原始收入回调中的无用户标识记录；"
            "适用于已上报 AD_SHOWN 的客户端版本，原始广告源与收入请继续在广告源日统计中核对。"
        ),
    }


@app.get("/v2/admin/operations/first-day-ad-scene-ipu")
def admin_first_day_ad_scene_ipu(
    authorization: str | None = Header(default=None),
    start_date: date | None = None,
    end_date: date | None = None,
    logical_placement: str = "",
    ad_format: str = "",
    app_version: str = "",
    attribution_surface: str = "",
) -> dict[str, Any]:
    return {
        "code": 0,
        "data": _admin_first_day_ad_scene_ipu_metrics(
            authorization,
            start_date=start_date,
            end_date=end_date,
            logical_placement=logical_placement,
            ad_format=ad_format,
            app_version=app_version,
            attribution_surface=attribution_surface,
        ),
    }


@app.get("/admin/timeline", response_class=HTMLResponse, include_in_schema=False)
def admin_user_behavior_timeline_page() -> HTMLResponse:
    """Admin-only focused page; data is fetched through the existing admin analytics API."""

    return HTMLResponse(
        USER_TIMELINE_HTML,
        headers={
            "Content-Security-Policy": "default-src 'self'; base-uri 'none'; frame-ancestors 'none'; object-src 'none'; form-action 'self'; connect-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'",
            "Referrer-Policy": "no-referrer",
            "X-Content-Type-Options": "nosniff",
        },
    )
