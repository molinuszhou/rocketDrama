from __future__ import annotations

import sys
import uuid
from datetime import timedelta
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from app.config import Settings
from app.migrations import apply_runtime_migrations
from app.service import RewardsService


def event_id(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4()}"


def run() -> None:
    settings = Settings.from_environment()
    apply_runtime_migrations(settings)
    service = RewardsService(settings)
    user_id = f"v4-smoke-{uuid.uuid4()}"

    initial = service.bootstrap(user_id)
    assert initial["wallet"]["gems_balance"] == 0
    assert initial["checkin"]["signed_today"] is False
    assert initial["episode_unlock"]["free_episodes_count"] == 3
    assert [item["code"] for item in initial["crown_progress"]] == ["BRONZE", "SILVER", "GOLD", "DIAMOND", "CROWN"]
    assert initial["crown_progress"][0]["rounds"]["target"] == 150
    assert initial["episode_unlock"]["placement_id"] == "int_next_episode_unlock"
    assert initial["episode_unlock"]["next_episode_interstitial_enabled"] is True
    assert initial["episode_unlock"]["extra_episode_unlock_enabled"] is True
    assert initial["interstitial"]["pause_ad_enabled"] is True
    assert initial["ring"]["claimable"] is False

    next_interstitial = service.record_interstitial(user_id, event_id("next-interstitial"), "int_next_episode_unlock")
    assert next_interstitial["idempotent"] is False
    pause_native = service.record_interstitial(user_id, event_id("pause-native"), "int_pause_native")
    assert pause_native["idempotent"] is False

    welcome = service.claim_welcome_bonus(user_id)
    assert welcome["result"]["gems_added"] == 150000

    # 有效观看按集形成待领取宝石，不因广告或播放解锁自动进入用户宝石余额。
    for episode_index in range(1, 11):
        watch = service.record_watch_event(
            user_id=user_id,
            event_id=event_id("watch"),
            drama_id="owned:smoke",
            episode_id=f"owned:smoke:ep:{episode_index}",
            watched_seconds=60,
        )
        assert watch["result"]["gems_added"] == 0
        assert watch["result"]["episode_reward"]["gems"] > 0

    after_watches = service.bootstrap(user_id)
    assert len(after_watches["episode_reward_claims"]) == 10
    assert after_watches["ring"]["claimable"] is True
    assert after_watches["ring"]["reward_gems"] > 0
    assert len(after_watches["pending_claims"]) == 1

    episode_claim = service.claim_episode_reward(user_id, "owned:smoke:ep:1", event_id("episode-claim"))
    assert episode_claim["result"]["action"] == "EPISODE_REWARD_CLAIM"
    assert episode_claim["result"]["gems_added"] > 0

    episode_double = service.complete_rewarded_ad(
        user_id=user_id,
        ad_event_id=event_id("episode-double"),
        placement_id="rv_episode_reward_double",
        target_id="owned:smoke:ep:1",
        episode_index=None,
        proof="TEST_PANGLE_REWARD",
        timestamp_ms=None,
    )
    assert episode_double["result"]["gems_added"] == episode_claim["result"]["gems_added"]

    ten_round_claim = service.claim_ten_round_bonus(user_id, 1, event_id("ten-round-claim"))
    assert ten_round_claim["result"]["action"] == "TEN_ROUND_CLAIM"
    assert ten_round_claim["result"]["gems_added"] > 0

    # 主动兑换仍只从宝石余额进入现金余额，之后按钱包日规则转入可提现余额。
    exchanged = service.exchange_gems(user_id, 10000, event_id("exchange"))
    assert exchanged["result"]["gems_spent"] == 10000
    assert exchanged["snapshot"]["wallet"]["available_balance"] == "0.00"
    assert float(exchanged["snapshot"]["wallet"]["wallet_locked"]) > 0

    with service._transaction() as connection:  # noqa: SLF001 - integration test setup
        yesterday = service._business_date() - timedelta(days=1)  # noqa: SLF001
        service._execute(connection, "UPDATE users SET last_wallet_release_date = %s, last_active_date = %s WHERE user_id = %s", (yesterday, yesterday, user_id))  # noqa: SLF001
    released = service.bootstrap(user_id)
    assert released["wallet"]["release"]["released_today"] is True
    print("PostgreSQL v4 rewards smoke test passed; per-episode claims, voluntary ad doubling and cumulative ten-round bonus verified")


if __name__ == "__main__":
    run()
