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
    pangle_callback_secret: str | None
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
        pangle_callback_secret = os.getenv("PANGLE_CALLBACK_SECRET", "").strip() or None
        if environment == "production" and not pangle_callback_secret:
            raise RuntimeError("production 环境必须设置 PANGLE_CALLBACK_SECRET 用于广告服务端验证")
        origins = [item.strip() for item in os.getenv("CORS_ORIGINS", "").split(",") if item.strip()]
        return cls(
            database_url=database_url,
            environment=environment,
            host=os.getenv("HOST", "0.0.0.0").strip(),
            port=int(os.getenv("PORT", "8100")),
            cors_origins=origins or ["http://localhost"],
            allow_debug_ad_proof=allow_debug_ad_proof,
            pangle_callback_secret=pangle_callback_secret,
            admin_dashboard_token=os.getenv("ADMIN_DASHBOARD_TOKEN", "").strip() or None,
            timezone=os.getenv("TIMEZONE", "Asia/Shanghai").strip() or "Asia/Shanghai",
        )
