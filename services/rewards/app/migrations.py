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
        sql_directory / "003_rewards_v4.sql",
        sql_directory / "004_ad_flow_v5.sql",
    )
    with psycopg.connect(settings.database_url, autocommit=True) as connection:
        with connection.cursor() as cursor:
            for sql_path in migration_paths:
                cursor.execute(sql_path.read_text(encoding="utf-8"))
