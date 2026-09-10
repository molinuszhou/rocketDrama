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


def rewarded(service: RewardsService, user_id: str, placement: str, target_id: str | None = None) -> dict:
    return service.complete_rewarded_ad(
        user_id=user_id,
        ad_event_id=event_id(placement),
        placement_id=placement,
        target_id=target_id,
        episode_index=None,
        proof="TEST_PANGLE_REWARD",
        timestamp_ms=None,
    )


def run() -> None:
    settings = Settings.from_environment()
    apply_runtime_migrations(settings)
    service = RewardsService(settings)
    user_id = f"v6-smoke-{uuid.uuid4()}"

    initial = service.bootstrap(user_id)
    assert initial["wallet"]["gems_balance"] == 0
    assert initial["checkin"]["signed_today"] is False
    assert initial["episode_unlock"]["free_episodes_count"] == 3
    assert initial["episode_unlock"]["next_episode_interstitial_enabled"] is True
    assert initial["episode_unlock"]["extra_episode_unlock_enabled"] is False
    assert initial["interstitial"]["mid_episode_enabled"] is True
    assert initial["interstitial"]["seek_enabled"] is True
    assert initial["chest"]["available_count"] == 1
    assert initial["chest"]["min_gems"] <= initial["chest"]["max_gems"]
    assert [item["code"] for item in initial["crown_progress"]] == ["BRONZE", "SILVER", "GOLD", "DIAMOND", "CROWN"]
    assert initial["crown_progress"][0]["rounds"]["target"] == 30
    assert initial["crown_progress"][0]["episode_reward_multiplier"] == 1.1

    for placement in ("int_next_episode_unlock", "int_episode_midroll", "int_seek", "int_reward_claim", "int_pause_native"):
        result = service.record_interstitial(user_id, event_id(placement), placement)
        assert result["idempotent"] is False

    # 每集先形成待领取宝石；第 3 集产生一个追剧随机宝箱，宝石不自动入账。
    watched_rewards: list[int] = []
    for episode_index in range(1, 4):
        watch = service.record_watch_event(
            user_id=user_id,
            event_id=event_id("watch"),
            drama_id="owned:smoke",
            episode_id=f"owned:smoke:ep:{episode_index}",
            watched_seconds=60,
        )
        assert watch["result"]["gems_added"] == 0
        if episode_index < 3:
            assert watch["result"]["random_chests_created"] == []
        else:
            assert watch["result"]["random_chests_created"][0]["created_count"] == 1
        watched_rewards.append(watch["result"]["episode_reward"]["gems"])
    after_watches = service.bootstrap(user_id)
    assert len(after_watches["episode_reward_claims"]) == 3
    assert after_watches["ring"]["claimable"] is True
    assert after_watches["ring"]["reward_gems"] == sum(watched_rewards)
    assert after_watches["chest"]["daily_available_count"] == 1
    assert after_watches["chest"]["watch_available_count"] == 1

    # 用户主动激励广告只为本集标记一次翻倍；之后悬浮球一次性领取所有积累宝石。
    boosted = rewarded(service, user_id, "rv_episode_reward_boost", "owned:smoke:ep:1")
    assert boosted["result"]["action"] == "EPISODE_REWARD_BOOST"
    boosted_snapshot = boosted["snapshot"]
    assert boosted_snapshot["ring"]["reward_gems"] == sum(watched_rewards) + watched_rewards[0]
    claim_all = service.claim_all_episode_rewards(user_id, event_id("claim-all"))
    assert claim_all["result"]["action"] == "EPISODE_REWARDS_CLAIM_ALL"
    assert claim_all["result"]["claimed_count"] == 3
    assert claim_all["result"]["boosted_count"] == 1
    assert claim_all["result"]["gems_added"] == sum(watched_rewards) + watched_rewards[0]

    # 宝箱可连续开启，每次通过独立激励广告随机结算，金额始终落在后台配置区间。
    before_chests = claim_all["snapshot"]["chest"]["available_count"]
    for _ in range(before_chests):
        opened = rewarded(service, user_id, "rv_random_chest_open")
        gems = opened["result"]["gems_added"]
        assert initial["chest"]["min_gems"] <= gems <= initial["chest"]["max_gems"]
    no_chest_error = False
    try:
        rewarded(service, user_id, "rv_random_chest_open")
    except Exception as error:  # noqa: BLE001 - 验证服务端明确拒绝空库存。
        no_chest_error = "没有可开启" in str(error)
    assert no_chest_error

    # 钱包余额仍可兑换，下一次活动日会自动转入可提现余额，并返回明日/广告加速预估字段。
    service.claim_welcome_bonus(user_id)
    exchanged = service.exchange_gems(user_id, 10_000, event_id("exchange"))
    assert exchanged["result"]["gems_spent"] == 10_000
    assert float(exchanged["snapshot"]["wallet"]["wallet_locked"]) > 0
    assert "expected_tomorrow_transfer" in exchanged["snapshot"]["wallet"]["release"]
    with service._transaction() as connection:  # noqa: SLF001 - integration test setup
        yesterday = service._business_date() - timedelta(days=1)  # noqa: SLF001
        service._execute(connection, "UPDATE users SET last_wallet_release_date = %s, last_active_date = %s WHERE user_id = %s", (yesterday, yesterday, user_id))  # noqa: SLF001
    released = service.bootstrap(user_id)
    assert released["wallet"]["release"]["released_today"] is True
    print("PostgreSQL v6 rewards smoke test passed; random chests, boosted episode claims, all-claim, ads and wallet release verified")


if __name__ == "__main__":
    run()
