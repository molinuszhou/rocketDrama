from __future__ import annotations

import sys
import uuid
from pathlib import Path

import psycopg

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from app.config import Settings
from app.migrations import apply_runtime_migrations
from app.service import RewardsService


def run() -> None:
    settings = Settings.from_environment()
    user_id = f"v164-upgrade-{uuid.uuid4()}"

    # The harness has loaded 001–018 exactly as a deployed v1.6.3 database would.
    # Seed the three legacy asset layers before v1.6.4 migrates them into one wallet.
    with psycopg.connect(settings.database_url, autocommit=True) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                INSERT INTO users (
                    user_id, market_code, gems_balance, cash_wallet_micros,
                    available_balance_micros, total_gems_earned
                ) VALUES (%s, 'BR', 50000, 2000000, 3000000, 150000)
                """,
                (user_id,),
            )

    apply_runtime_migrations(settings)
    service = RewardsService(settings)
    snapshot = service.bootstrap(user_id, "BR")

    # 50,000 legacy units at R$3 per 10,000 = R$15, plus R$2/R$3 old wallet layers.
    assert snapshot["wallet"]["cash_mode"] is True
    assert snapshot["wallet"]["gems_balance"] == 0
    assert snapshot["wallet"]["cash_wallet"] == "20.00"
    assert snapshot["wallet"]["available_balance"] == "20.00"
    assert snapshot["wallet"]["total_gems_earned"] == 150000
    assert snapshot["welcome"]["available"] is True
    assert snapshot["welcome"]["cash"] == "30.00"
    assert snapshot["wallet"]["currency"]["code"] == "BRL"
    assert snapshot["interstitial"]["cooldown_seconds"] == 60
    assert snapshot["wallet"]["release"]["boost_cap"] == 10
    assert snapshot["ring"]["claim_prompt_threshold_gems"] == 50_000

    # A second migration pass is deliberately harmless; it must never replay v3's
    # older rewarded-placement CHECK constraint against current ledger data.
    apply_runtime_migrations(settings)
    after_repeat = service.bootstrap(user_id, "BR")
    assert after_repeat["wallet"]["cash_wallet"] == "20.00"

    print("PostgreSQL v1.6.5.0 direct-cash runtime upgrade smoke test passed; legacy Gem and split-wallet assets migrated exactly once")


if __name__ == "__main__":
    run()
