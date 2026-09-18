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
    assert initial["episode_unlock"]["episodes_per_rewarded_unlock"] == 3
    assert initial["episode_unlock"]["episode_reward_boost_enabled"] is False
    assert initial["interstitial"]["mid_episode_enabled"] is True
    assert initial["interstitial"]["seek_enabled"] is True
    assert initial["interstitial"]["speed_enabled"] is True
    assert initial["chest"]["available_count"] == 1
    assert initial["chest"]["min_gems"] <= initial["chest"]["max_gems"]
    assert [item["code"] for item in initial["crown_progress"]] == ["BRONZE", "SILVER", "GOLD", "DIAMOND", "CROWN"]
    assert initial["crown_progress"][0]["rounds"]["target"] == 10
    assert initial["crown_progress"][0]["ads_per_episode"] == 2
    assert initial["interstitial"]["mid_episode_trigger_seconds"] == 720
    assert initial["interstitial"]["global_hourly_limit"] == 8
    assert initial["wallet"]["exchange_rate_gems"] == 10_000
    assert initial["wallet"]["currency"]["code"] == "USD"
    assert initial["gem_damping"]["cap"] == 2_000_000
    assert initial["gem_damping"]["tier1_threshold"] == 1_900_000
    assert initial["gem_damping"]["tier2_threshold"] == 1_950_000
    assert initial["gem_damping"]["tier3_threshold"] == 1_980_000
    assert initial["gem_damping"]["tier4_multiplier"] == 0.20
    assert initial["gem_damping"]["scope"] == "ALL_GEM_SOURCES"
    assert initial["wallet"]["release"]["boost_cap"] == 3
    assert [item["gems"] for item in initial["checkin"]["tiers"][:7]] == [3000, 6000, 8000, 12000, 15000, 25000, 50000]
    assert [item["gems"] for item in initial["crown_progress"]] == [12000, 26000, 66000, 160000, 400000]

    # 欢迎奖励和签到均必须由完整激励广告结算，旧直接接口不再能绕过广告。
    welcome, _ = rewarded(service, user_id, "rv_welcome_bonus")
    assert welcome["result"]["action"] == "WELCOME_BONUS"
    assert welcome["result"]["gems_added"] == initial["welcome"]["gems"]
    signed, _ = rewarded(service, user_id, "rv_checkin_complete")
    assert signed["result"]["action"] == "SIGN_IN"
    assert signed["snapshot"]["checkin"]["signed_today"] is True

    # 每种被动播放动作独立记账；拖拽、剧中与倍速均不共享旧冷却。
    for placement in ("int_episode_midroll", "int_seek", "int_speed", "int_pause_native"):
        result = service.record_interstitial(user_id, event_id(placement), placement)
        assert result["idempotent"] is False
    for placement in ("int_seek", "int_speed", "int_drama_switch", "int_rewarded_fallback"):
        result = service.record_interstitial(user_id, event_id(f"{placement}-cap"), placement)
        assert result["idempotent"] is False
    global_cap_rejected = False
    try:
        service.record_interstitial(user_id, event_id("int-global-cap"), "int_seek")
    except Exception as error:  # noqa: BLE001 - 验证所有插屏位共用小时总上限。
        global_cap_rejected = "总上限" in str(error)
    assert global_cap_rejected

    # 单条激励广告连续解锁三集；低频模式关闭单集广告翻倍。
    episode_pass, _ = rewarded(service, user_id, "rv_episode_pass", "owned:smoke:ep:4", 4)
    assert episode_pass["result"]["unlock_episode_count"] == 3
    boost_rejected = False
    try:
        rewarded(service, user_id, "rv_episode_reward_boost", "owned:smoke:ep:1")
    except Exception as error:  # noqa: BLE001 - 验证低频模式拒绝额外单集翻倍广告。
        boost_rejected = "未启用单集广告翻倍" in str(error)
    assert boost_rejected

    # 每集形成待领取宝石；每十集产生追剧随机宝箱，宝石不会自动入账。
    watched_rewards: list[int] = []
    for episode_index in range(1, 11):
        watch = service.record_watch_event(
            user_id=user_id,
            event_id=event_id("watch"),
            drama_id="owned:smoke",
            episode_id=f"owned:smoke:ep:{episode_index}",
            watched_seconds=60,
        )
        assert watch["result"]["gems_added"] == 0
        if episode_index < 10:
            assert watch["result"]["random_chests_created"] == []
        else:
            assert watch["result"]["random_chests_created"][0]["created_count"] == 1
        watched_rewards.append(watch["result"]["episode_reward"]["gems"])

    after_watches = service.bootstrap(user_id)
    assert len(after_watches["episode_reward_claims"]) == 10
    assert after_watches["ring"]["claimable"] is True
    assert after_watches["ring"]["reward_gems"] == sum(watched_rewards)
    assert after_watches["chest"]["daily_available_count"] == 1
    assert after_watches["chest"]["watch_available_count"] == 1

    # 领取全部待领取分集奖励仍须先获得一次激励广告授权。
    authorization, authorization_event_id = rewarded(service, user_id, "rv_episode_rewards_claim")
    assert authorization["result"]["action"] == "EPISODE_REWARDS_CLAIM_AUTH"
    claim_all = service.claim_all_episode_rewards(user_id, event_id("claim-all"), authorization_event_id)
    assert claim_all["result"]["action"] == "EPISODE_REWARDS_CLAIM_ALL"
    assert claim_all["result"]["claimed_count"] == 10
    assert claim_all["result"]["boosted_count"] == 0
    assert claim_all["result"]["gems_added"] == sum(watched_rewards)
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
    for _ in range(3):
        rewarded(service, user_id, "rv_crown_upgrade")
    crown_daily_cap_rejected = False
    try:
        rewarded(service, user_id, "rv_crown_upgrade")
    except Exception as error:  # noqa: BLE001 - 验证皇冠主动激励广告独立日上限。
        crown_daily_cap_rejected = "皇冠成长广告次数已达上限" in str(error)
    assert crown_daily_cap_rejected

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
        assert boost["result"]["released_micros"] >= 0
        assert boost["snapshot"]["wallet"]["release"]["boost_available"] is True

    # 市场本币：同样的 10,000 Gems 在巴西默认为 R$3.00、在印尼默认为 Rp10,000，并均提供 US$1.00 参考值。
    br_user = f"v163-br-{uuid.uuid4()}"
    br_initial = service.bootstrap(br_user, "BR")
    assert br_initial["wallet"]["currency"]["code"] == "BRL"
    assert br_initial["wallet"]["currency"]["symbol"] == "R$"
    rewarded(service, br_user, "rv_welcome_bonus")
    br_exchange = service.exchange_gems(br_user, 10_000, event_id("br-exchange"))
    assert br_exchange["snapshot"]["wallet"]["cash_wallet"] == "3.00"
    assert br_exchange["snapshot"]["wallet"]["cash_wallet_usd_reference"] == "1.00"

    id_user = f"v163-id-{uuid.uuid4()}"
    id_initial = service.bootstrap(id_user, "ID")
    assert id_initial["wallet"]["currency"]["code"] == "IDR"
    assert id_initial["wallet"]["currency"]["fraction_digits"] == 0
    rewarded(service, id_user, "rv_welcome_bonus")
    id_exchange = service.exchange_gems(id_user, 10_000, event_id("id-exchange"))
    assert id_exchange["snapshot"]["wallet"]["cash_wallet"] == "10000"
    assert id_exchange["snapshot"]["wallet"]["cash_wallet_usd_reference"] == "1.00"

    # 通用区域钱包管理：新增 MX，停用后新用户回退 DEFAULT；历史用户仍可读取原策略。
    admin_auth = "Bearer smoke-admin-token"
    service.update_market_wallet_policy(admin_auth, {
        "market_code": "MX", "currency_code": "MXN", "currency_symbol": "$",
        "currency_fraction_digits": 2, "currency_micros_per_10000_gems": 20_000_000,
        "usd_micros_per_10000_gems": 1_000_000, "withdrawal_threshold_micros": 1_000_000,
        "welcome_bonus_gems": 200_000, "daily_release_micros": 1, "enabled": True,
    })
    mx_user = f"v163-mx-{uuid.uuid4()}"
    mx_initial = service.bootstrap(mx_user, "MX")
    assert mx_initial["wallet"]["currency"]["code"] == "MXN"
    service.update_market_wallet_policy(admin_auth, {
        "market_code": "MX", "currency_code": "MXN", "currency_symbol": "$",
        "currency_fraction_digits": 2, "currency_micros_per_10000_gems": 20_000_000,
        "usd_micros_per_10000_gems": 1_000_000, "withdrawal_threshold_micros": 1_000_000,
        "welcome_bonus_gems": 200_000, "daily_release_micros": 1, "enabled": False,
    })
    fallback_user = f"v163-mx-fallback-{uuid.uuid4()}"
    assert service.bootstrap(fallback_user, "MX")["wallet"]["currency"]["code"] == "USD"
    assert service.bootstrap(mx_user)["wallet"]["currency"]["code"] == "MXN"

    # 全渠道五档阻尼：在每个边界附近依次以欢迎、签到、分集、宝箱和皇冠来源结算。
    damping_user = f"v163-damping-{uuid.uuid4()}"
    service.bootstrap(damping_user, "US")
    with service._transaction() as connection:  # noqa: SLF001 - boundary integration test setup
        policy = service._policy(connection)  # noqa: SLF001
        def total_at(value: int) -> None:
            service._execute(connection, "UPDATE users SET total_gems_earned = %s, gems_balance = %s WHERE user_id = %s", (value, value, damping_user))  # noqa: SLF001

        total_at(1_899_999)
        assert service._credit_gems(connection, damping_user, 10, policy, "WELCOME_BONUS", "damping", event_id("welcome")) == 10  # noqa: SLF001
        total_at(1_929_999)
        assert service._credit_gems(connection, damping_user, 10, policy, "SIGN_IN", "damping", event_id("signin")) == 5  # noqa: SLF001
        total_at(1_959_999)
        assert service._credit_gems(connection, damping_user, 10, policy, "EPISODE_REWARD_CLAIM", "damping", event_id("episode")) == 4  # noqa: SLF001
        total_at(1_979_999)
        assert service._credit_gems(connection, damping_user, 10, policy, "RANDOM_CHEST_OPEN", "damping", event_id("chest")) == 2  # noqa: SLF001
        total_at(1_989_999)
        assert service._credit_gems(connection, damping_user, 10, policy, "ACHIEVEMENT", "damping", event_id("crown")) == 0  # noqa: SLF001
        total_at(1_999_999)
        assert service._credit_gems(connection, damping_user, 100, policy, "RANDOM_CHEST_OPEN", "damping", event_id("cap")) == 1  # noqa: SLF001

    print("PostgreSQL v1.6.3.6 rewards smoke test passed; low-frequency ads, three-episode rewarded unlocks, five-tier damping, localized wallet exchange and crown growth verified")


if __name__ == "__main__":
    run()
