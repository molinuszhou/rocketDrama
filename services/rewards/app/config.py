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
    admin_dashboard_token: str | None
    session_signing_secret: str
    require_user_session: bool
    admin_initial_username: str | None
    admin_initial_password: str | None
    timezone: str
    geoip_db_path: str | None

    @classmethod
    def from_environment(cls) -> "Settings":
        database_url = os.getenv("DATABASE_URL", "").strip()
        if not database_url.startswith("postgresql"):
            raise RuntimeError("DATABASE_URL 必须是 PostgreSQL 连接字符串")
        environment = os.getenv("ENVIRONMENT", "development").strip().lower()
        allow_debug_ad_proof = os.getenv("ALLOW_DEBUG_AD_PROOF", "false").strip().lower() == "true"
        if environment != "development" and allow_debug_ad_proof:
            raise RuntimeError("非 development 环境禁止 ALLOW_DEBUG_AD_PROOF")
        origins = [item.strip() for item in os.getenv("CORS_ORIGINS", "").split(",") if item.strip()]
        session_secret = os.getenv("SESSION_SIGNING_SECRET", "").strip()
        if environment != "development" and len(session_secret) < 32:
            raise RuntimeError("生产环境必须设置至少 32 位 SESSION_SIGNING_SECRET")
        return cls(
            database_url=database_url,
            environment=environment,
            host=os.getenv("HOST", "0.0.0.0").strip(),
            port=int(os.getenv("PORT", "8100")),
            cors_origins=origins or ["http://localhost"],
            allow_debug_ad_proof=allow_debug_ad_proof,
            admin_dashboard_token=os.getenv("ADMIN_DASHBOARD_TOKEN", "").strip() or None,
            session_signing_secret=session_secret or os.getenv("ADMIN_DASHBOARD_TOKEN", "").strip(),
            require_user_session=os.getenv("REWARDS_REQUIRE_USER_SESSION", "false").strip().lower() == "true",
            admin_initial_username=os.getenv("ADMIN_INITIAL_USERNAME", "").strip() or None,
            admin_initial_password=os.getenv("ADMIN_INITIAL_PASSWORD", "").strip() or None,
            timezone=os.getenv("TIMEZONE", "Asia/Shanghai").strip() or "Asia/Shanghai",
            geoip_db_path=os.getenv("GEOIP_DB_PATH", "/usr/share/GeoIP/GeoLite2-Country.mmdb").strip() or None,
        )
