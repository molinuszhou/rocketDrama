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


def rewarded(
    service: RewardsService,
    user_id: str,
    placement: str,
    target_id: str | None = None,
    episode_index: int | None = None,
) -> tuple[dict, str]:
    ad_event_id = event_id(placement)
    return (
        service.complete_rewarded_ad(
            user_id=user_id,
            ad_event_id=ad_event_id,
            placement_id=placement,
            target_id=target_id,
            episode_index=episode_index,
            proof="TEST_PANGLE_REWARD",
            timestamp_ms=None,
        ),
        ad_event_id,
    )


def run() -> None:
    settings = Settings.from_environment()
    apply_runtime_migrations(settings)
    service = RewardsService(settings)
    user_id = f"v7-smoke-{uuid.uuid4()}"

    initial = service.bootstrap(user_id)
    assert initial["wallet"]["gems_balance"] == 0
    assert initial["checkin"]["signed_today"] is False
    assert initial["episode_unlock"]["free_episodes_count"] == 3
    assert initial["episode_unlock"]["next_episode_rewarded_enabled"] is True
    assert initial["interstitial"]["mid_episode_enabled"] is True
    assert initial["interstitial"]["seek_enabled"] is True
    assert initial["interstitial"]["speed_enabled"] is True
    assert initial["chest"]["available_count"] == 1
    assert initial["chest"]["min_gems"] <= initial["chest"]["max_gems"]
    assert [item["code"] for item in initial["crown_progress"]] == ["BRONZE", "SILVER", "GOLD", "DIAMOND", "CROWN"]
    assert initial["crown_progress"][0]["rounds"]["target"] == 10
    assert initial["crown_progress"][0]["ads_per_episode"] == 2

    # 欢迎奖励和签到均必须由完整激励广告结算，旧直接接口不再能绕过广告。
    welcome, _ = rewarded(service, user_id, "rv_welcome_bonus")
    assert welcome["result"]["action"] == "WELCOME_BONUS"
    assert welcome["result"]["gems_added"] == initial["welcome"]["gems"]
    signed, _ = rewarded(service, user_id, "rv_checkin_complete")
    assert signed["result"]["action"] == "SIGN_IN"
    assert signed["snapshot"]["checkin"]["signed_today"] is True

    # 每种被动播放动作独立记账；拖拽、剧中与倍速均不共享旧冷却。
    for placement in ("int_next_episode_unlock", "int_episode_midroll", "int_seek", "int_seek", "int_speed", "int_pause_native"):
        result = service.record_interstitial(user_id, event_id(placement), placement)
        assert result["idempotent"] is False

    # 每集形成待领取宝石；第三集产生追剧随机宝箱，宝石不会自动入账。
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

    # 当前集仅能预先激励翻倍一次；随后领取全部必须先获得一次激励广告授权。
    boosted, _ = rewarded(service, user_id, "rv_episode_reward_boost", "owned:smoke:ep:1")
    assert boosted["result"]["action"] == "EPISODE_REWARD_BOOST"
    authorization, authorization_event_id = rewarded(service, user_id, "rv_episode_rewards_claim")
    assert authorization["result"]["action"] == "EPISODE_REWARDS_CLAIM_AUTH"
    claim_all = service.claim_all_episode_rewards(user_id, event_id("claim-all"), authorization_event_id)
    assert claim_all["result"]["action"] == "EPISODE_REWARDS_CLAIM_ALL"
    assert claim_all["result"]["claimed_count"] == 3
    assert claim_all["result"]["boosted_count"] == 1
    assert claim_all["result"]["gems_added"] == sum(watched_rewards) + watched_rewards[0]
    replay_claim = service.claim_all_episode_rewards(user_id, event_id("claim-replay"), authorization_event_id)
    assert replay_claim["idempotent"] is True

    # 宝箱可连续开启，金额始终位于后台随机区间内；无库存时完整广告结算会被服务端拒绝。
    before_chests = claim_all["snapshot"]["chest"]["available_count"]
    for _ in range(before_chests):
        opened, _ = rewarded(service, user_id, "rv_random_chest_open")
        gems = opened["result"]["gems_added"]
        assert initial["chest"]["min_gems"] <= gems <= initial["chest"]["max_gems"]
    no_chest_error = False
    try:
        rewarded(service, user_id, "rv_random_chest_open")
    except Exception as error:  # noqa: BLE001 - 验证服务端明确拒绝空库存。
        no_chest_error = "没有可开启" in str(error)
    assert no_chest_error

    # 皇冠广告成长：每 2 条完整激励广告抵 1 集追剧，服务端快照公开等效进度。
    crown_first, _ = rewarded(service, user_id, "rv_crown_upgrade")
    crown_second, _ = rewarded(service, user_id, "rv_crown_upgrade")
    bronze = crown_second["snapshot"]["crown_progress"][0]
    assert bronze["ad_equivalent_rounds"] >= crown_first["snapshot"]["crown_progress"][0]["ad_equivalent_rounds"] + 1

    # 钱包余额可兑换；下一活动日基础转入后，广告加速按后台百分比额外转入可提现余额。
    gems_to_exchange = min(int(claim_all["snapshot"]["wallet"]["gems_balance"]), 10_000)
    assert gems_to_exchange > 0
    exchanged = service.exchange_gems(user_id, gems_to_exchange, event_id("exchange"))
    assert exchanged["result"]["gems_spent"] == gems_to_exchange
    assert float(exchanged["snapshot"]["wallet"]["wallet_locked"]) > 0
    with service._transaction() as connection:  # noqa: SLF001 - integration test setup
        yesterday = service._business_date() - timedelta(days=1)  # noqa: SLF001
        service._execute(connection, "UPDATE users SET last_wallet_release_date = %s, last_active_date = %s WHERE user_id = %s", (yesterday, yesterday, user_id))  # noqa: SLF001
    released = service.bootstrap(user_id)
    assert released["wallet"]["release"]["released_today"] is True
    if released["wallet"]["release"]["boost_available"]:
        boost, _ = rewarded(service, user_id, "rv_release_boost")
        assert boost["result"]["action"] == "RELEASE_BOOST"
        assert boost["result"]["released_micros"] > 0

    print("PostgreSQL v7 rewards smoke test passed; rewarded gates, chests, crown ads, playback ads and wallet boost verified")


if __name__ == "__main__":
    run()
