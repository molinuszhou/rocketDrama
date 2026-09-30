from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    database_url: str
    environment: str
    host: str
    port: int
    cors_origins: list[str]
    allow_debug_ad_proof: bool
    topon_rewarded_s2s_secret: str | None
    topon_rewarded_placement_id: str | None
    admin_dashboard_token: str | None
    timezone: str

    @classmethod
    def from_environment(cls) -> "Settings":
        database_url = os.getenv("DATABASE_URL", "").strip()
        if not database_url.startswith("postgresql"):
            raise RuntimeError("DATABASE_URL 必须是 PostgreSQL 连接字符串")
        environment = os.getenv("ENVIRONMENT", "development").strip().lower()
        allow_debug_ad_proof = os.getenv("ALLOW_DEBUG_AD_PROOF", "false").strip().lower() == "true"
        if environment != "development" and allow_debug_ad_proof:
            raise RuntimeError("非 development 环境禁止 ALLOW_DEBUG_AD_PROOF")
        topon_rewarded_s2s_secret = os.getenv("TOPON_REWARDED_S2S_SECRET", "").strip() or None
        topon_rewarded_placement_id = os.getenv("TOPON_REWARDED_PLACEMENT_ID", "").strip() or None
        if environment == "production" and not topon_rewarded_s2s_secret:
            raise RuntimeError("production 环境必须设置 TOPON_REWARDED_S2S_SECRET 用于 TopOn 激励服务端验签")
        if environment == "production" and not topon_rewarded_placement_id:
            raise RuntimeError("production 环境必须设置 TOPON_REWARDED_PLACEMENT_ID 以校验 TopOn 激励广告位")
        origins = [item.strip() for item in os.getenv("CORS_ORIGINS", "").split(",") if item.strip()]
        return cls(
            database_url=database_url,
            environment=environment,
            host=os.getenv("HOST", "0.0.0.0").strip(),
            port=int(os.getenv("PORT", "8100")),
            cors_origins=origins or ["http://localhost"],
            allow_debug_ad_proof=allow_debug_ad_proof,
            topon_rewarded_s2s_secret=topon_rewarded_s2s_secret,
            topon_rewarded_placement_id=topon_rewarded_placement_id,
            admin_dashboard_token=os.getenv("ADMIN_DASHBOARD_TOKEN", "").strip() or None,
            timezone=os.getenv("TIMEZONE", "Asia/Shanghai").strip() or "Asia/Shanghai",
        )
