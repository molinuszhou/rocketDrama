from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    environment: str
    cors_origins: list[str]
    pangle_content_enabled: bool
    owned_catalog_path: str | None
    local_media_root: str | None
    local_media_enabled: bool
    reward_rules_path: str
    reward_ledger_path: str
    admin_config_token: str | None

    @classmethod
    def from_environment(cls) -> "Settings":
        environment = os.getenv("ENVIRONMENT", "development")
        local_media_enabled = os.getenv("LOCAL_MEDIA_ENABLED", "false").lower() == "true"
        if local_media_enabled and environment != "development":
            raise RuntimeError("LOCAL_MEDIA_ENABLED 只能在 ENVIRONMENT=development 时启用")

        raw_origins = os.getenv("CORS_ORIGINS", "http://localhost:3000,http://10.0.2.2:8000")
        return cls(
            environment=environment,
            cors_origins=[origin.strip() for origin in raw_origins.split(",") if origin.strip()],
            pangle_content_enabled=os.getenv("PANGLE_CONTENT_ENABLED", "false").lower() == "true",
            owned_catalog_path=os.getenv("OWNED_CATALOG_PATH") or None,
            local_media_root=os.getenv("LOCAL_MEDIA_ROOT") or None,
            local_media_enabled=local_media_enabled,
            reward_rules_path=os.getenv("REWARD_RULES_PATH", "./runtime/reward_rules.json"),
            reward_ledger_path=os.getenv("REWARD_LEDGER_PATH", "./runtime/reward_ledger.sqlite3"),
            admin_config_token=os.getenv("ADMIN_CONFIG_TOKEN") or None,
        )
