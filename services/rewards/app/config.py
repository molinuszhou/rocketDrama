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
    telemetry_encryption_key: str | None
    user_session_ttl_days: int = 180

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
        user_session_ttl_days = int(os.getenv("USER_SESSION_TTL_DAYS", "180").strip() or "180")
        if not 1 <= user_session_ttl_days <= 730:
            raise RuntimeError("USER_SESSION_TTL_DAYS 必须在 1 至 730 天之间")
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
            # All product reporting and reward-day boundaries use UTC+0 unless deployment
            # explicitly supplies another (legacy) business timezone.
            timezone=os.getenv("TIMEZONE", "UTC").strip() or "UTC",
            geoip_db_path=os.getenv("GEOIP_DB_PATH", "/usr/share/GeoIP/GeoLite2-Country.mmdb").strip() or None,
            # A Fernet key used only for the restricted raw-IP / raw-AppsFlyer audit stream.
            # If omitted, ordinary Rewards behavior remains available but sensitive values are
            # intentionally not persisted in plaintext or via a fallback cipher.
            telemetry_encryption_key=os.getenv("TELEMETRY_ENCRYPTION_KEY", "").strip() or None,
            # User sessions renew on bootstrap and transparently after an authentication failure.
            # Keep this independent of the shorter, revocable administrator session lifetime.
            user_session_ttl_days=user_session_ttl_days,
        )
