from __future__ import annotations

from pathlib import Path

import psycopg

from app.config import Settings


def apply_runtime_migrations(settings: Settings) -> None:
    """在 API 启动前补跑可重复执行的增量迁移。

    PostgreSQL 官方镜像仅会在数据卷首次创建时执行 initdb 目录中的 SQL；
    已存在的本地测试账本需由此函数非破坏性地应用后续 schema 版本。
    """
    sql_directory = Path(__file__).resolve().parent.parent / "sql"
    migration_paths = (
        ("003_rewards_v4", sql_directory / "003_rewards_v4.sql"),
        ("004_ad_flow_v5", sql_directory / "004_ad_flow_v5.sql"),
        ("005_random_chest_and_reward_v6", sql_directory / "005_random_chest_and_reward_v6.sql"),
        ("006_rewarded_actions_and_player_v7", sql_directory / "006_rewarded_actions_and_player_v7.sql"),
        ("007_v8_player_reward_polish", sql_directory / "007_v8_player_reward_polish.sql"),
        ("008_v81_ad_fatigue_and_crown_curve", sql_directory / "008_v81_ad_fatigue_and_crown_curve.sql"),
        ("009_v162_reward_control", sql_directory / "009_v162_reward_control.sql"),
    )
    with psycopg.connect(settings.database_url, autocommit=True) as connection:
        with connection.cursor() as cursor:
            # 每份增量 SQL 都包含早期兼容性 ALTER；已标记完成后不得重放，
            # 否则旧版本的广告位 CHECK 约束会短暂收窄并拒绝新版本事件数据。
            cursor.execute(
                """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version TEXT PRIMARY KEY,
                    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """
            )
            for version, sql_path in migration_paths:
                cursor.execute("SELECT 1 FROM schema_migrations WHERE version = %s", (version,))
                if cursor.fetchone() is not None:
                    continue
                cursor.execute(sql_path.read_text(encoding="utf-8"))
