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
    assert initial["wallet"]["cash_mode"] is True
    assert initial["wallet"]["gems_balance"] == 0
    assert initial["wallet"]["cash_wallet"] == "0.00"
    assert initial["welcome"]["cash"] == "20.00"
    assert initial["review"]["available"] is False
    assert initial["checkin"]["signed_today"] is False
    assert initial["episode_unlock"]["free_episodes_count"] == 3
    assert initial["episode_unlock"]["next_episode_rewarded_enabled"] is True
    assert initial["episode_unlock"]["episodes_per_rewarded_unlock"] == 2
    assert initial["episode_unlock"]["episode_reward_boost_enabled"] is False
    assert initial["interstitial"]["mid_episode_enabled"] is True
    assert initial["interstitial"]["seek_enabled"] is False
    assert initial["interstitial"]["speed_enabled"] is False
    assert initial["chest"]["available_count"] == 0
    assert initial["ring"]["claim_prompt_threshold_gems"] == 50_000
    assert initial["chest"]["daily_available_count"] == 0
    assert [item["code"] for item in initial["crown_progress"]] == ["BRONZE", "SILVER", "GOLD", "DIAMOND", "CROWN"]
    assert initial["crown_progress"][0]["rounds"]["target"] == 30
    assert initial["crown_progress"][0]["episode_progress_points"] == 2
    assert initial["crown_progress"][0]["rewarded_ad_progress_points"] == 1
    assert initial["crown_progress"][0]["charge_cooldown_seconds"] == 60
    assert initial["wallet"]["release"]["charge_cooldown_seconds"] == 60
    assert initial["interstitial"]["mid_episode_trigger_seconds"] == 180
    assert initial["interstitial"]["mid_episode_repeat_seconds"] == 360
    assert initial["interstitial"]["cooldown_seconds"] == 60
    assert initial["interstitial"]["global_hourly_limit"] == 0
    assert initial["interstitial"]["rewarded_skip_threshold"] == 3
    assert initial["wallet"]["exchange_rate_gems"] == 10_000
    assert initial["wallet"]["currency"]["code"] == "USD"
    assert initial["withdrawal"]["threshold"] == "200.00"
    assert initial["gem_damping"]["cap"] == 2_000_000
    assert initial["gem_damping"]["tier1_threshold"] == 1_900_000
    assert initial["gem_damping"]["tier2_threshold"] == 1_950_000
    assert initial["gem_damping"]["tier3_threshold"] == 1_980_000
    assert initial["gem_damping"]["tier4_threshold"] == 1_990_000
    assert initial["gem_damping"]["tier5_threshold"] == 1_995_000
    assert initial["gem_damping"]["tier4_multiplier"] == 0.20
    assert initial["gem_damping"]["tier5_multiplier"] == 0.10
    assert initial["gem_damping"]["tier6_multiplier"] == 0.01
    assert initial["gem_damping"]["scope"] == "ALL_GEM_SOURCES"
    assert initial["wallet"]["release"]["boost_cap"] == 10
    assert [item["gems"] for item in initial["checkin"]["tiers"][:7]] == [3000, 6000, 8000, 12000, 15000, 25000, 50000]
    assert [item["gems"] for item in initial["crown_progress"]] == [12000, 26000, 66000, 80000, 100000]

    # 欢迎奖励和签到均必须由完整激励广告结算，旧直接接口不再能绕过广告。
    welcome, _ = rewarded(service, user_id, "rv_welcome_bonus")
    assert welcome["result"]["action"] == "WELCOME_BONUS"
    assert welcome["result"]["gems_added"] == initial["welcome"]["gems"]
    assert welcome["result"]["cash_added"] == "20.00"
    signed, _ = rewarded(service, user_id, "rv_checkin_complete")
    assert signed["result"]["action"] == "SIGN_IN"
    assert signed["snapshot"]["checkin"]["signed_today"] is True

    # 插屏由 CD 控制；完整激励广告会重新开始 CD，且跳过三次激励广告后才允许兜底插屏。
    with service._transaction() as connection:  # noqa: SLF001 - integration test setup
        service._execute(connection, "UPDATE daily_counters SET last_interstitial_at = NOW() - INTERVAL '61 seconds' WHERE user_id = %s AND business_date = %s", (user_id, service._business_date()))  # noqa: SLF001
    result = service.record_interstitial(user_id, event_id("int_episode_midroll"), "int_episode_midroll")
    assert result["idempotent"] is False
    cooldown_rejected = False
    try:
        service.record_interstitial(user_id, event_id("int-seek-cooldown"), "int_seek")
    except Exception as error:  # noqa: BLE001 - 验证所有插屏位共用 CD。
        cooldown_rejected = "冷却期" in str(error)
    assert cooldown_rejected
    for _ in range(3):
        service.record_rewarded_skipped(user_id, "unlock_declined")
    with service._transaction() as connection:  # noqa: SLF001 - integration test setup
        service._execute(connection, "UPDATE daily_counters SET last_interstitial_at = NOW() - INTERVAL '61 seconds' WHERE user_id = %s AND business_date = %s", (user_id, service._business_date()))  # noqa: SLF001
    fallback = service.record_interstitial(user_id, event_id("int_rewarded_fallback"), "int_rewarded_fallback")
    assert fallback["snapshot"]["interstitial"]["rewarded_skip_count"] == 3

    # A real fallback interstitial may settle the original rewarded action exactly once.
    fallback_user = f"v1656-fallback-{uuid.uuid4()}"
    fallback_initial = service.bootstrap(fallback_user)
    fallback_interstitial_id = event_id("int-rewarded-fallback-grant")
    service.record_interstitial(fallback_user, fallback_interstitial_id, "int_rewarded_fallback")
    fallback_grant = service.settle_rewarded_fallback(
        user_id=fallback_user,
        interstitial_event_id=fallback_interstitial_id,
        original_placement_id="rv_welcome_bonus",
        target_id=None,
        episode_index=None,
    )
    assert fallback_grant["result"]["action"] == "WELCOME_BONUS"
    assert fallback_grant["result"]["cash_added"] == fallback_initial["welcome"]["cash"]
    assert fallback_grant["result"]["ad_event_id"] == f"fallback-{fallback_interstitial_id}"
    assert fallback_grant["snapshot"]["welcome"]["claimed"] is True
    assert service.settle_rewarded_fallback(
        user_id=fallback_user,
        interstitial_event_id=fallback_interstitial_id,
        original_placement_id="rv_welcome_bonus",
        target_id=None,
        episode_index=None,
    )["idempotent"] is True

    # A completed fallback creative must still grant its original benefit when
    # the best-effort preceding display telemetry request was lost in transit.
    missing_record_user = f"v1662-fallback-missing-record-{uuid.uuid4()}"
    missing_record_initial = service.bootstrap(missing_record_user)
    missing_record_grant = service.settle_rewarded_fallback(
        user_id=missing_record_user,
        interstitial_event_id=event_id("int-fallback-telemetry-lost"),
        original_placement_id="rv_welcome_bonus",
        target_id=None,
        episode_index=None,
    )
    assert missing_record_grant["result"]["action"] == "WELCOME_BONUS"
    assert missing_record_grant["result"]["cash_added"] == missing_record_initial["welcome"]["cash"]
    assert missing_record_grant["snapshot"]["welcome"]["claimed"] is True

    # A fallback for the aggregate episode-reward action must expose a rewarded
    # authorization ID, not the interstitial telemetry ID. The client uses this
    # ID for /claim-all after the single shown fallback creative.
    fallback_claim_user = f"v1666-fallback-claim-all-{uuid.uuid4()}"
    service.bootstrap(fallback_claim_user)
    service.record_watch_event(
        user_id=fallback_claim_user,
        event_id=event_id("fallback-claim-watch"),
        drama_id="owned:fallback-claim",
        episode_id="owned:fallback-claim:ep:1",
        watched_seconds=60,
    )
    fallback_claim_interstitial_id = event_id("int-fallback-claim-all")
    service.record_interstitial(fallback_claim_user, fallback_claim_interstitial_id, "int_rewarded_fallback")
    fallback_claim_authorization = service.settle_rewarded_fallback(
        user_id=fallback_claim_user,
        interstitial_event_id=fallback_claim_interstitial_id,
        original_placement_id="rv_episode_rewards_claim",
        target_id=None,
        episode_index=None,
    )
    fallback_authorization_id = fallback_claim_authorization["result"]["ad_event_id"]
    assert fallback_authorization_id == f"fallback-{fallback_claim_interstitial_id}"
    fallback_claim_all = service.claim_all_episode_rewards(
        fallback_claim_user, event_id("fallback-claim-all"), fallback_authorization_id,
    )
    assert fallback_claim_all["result"]["action"] == "EPISODE_REWARDS_CLAIM_ALL"
    assert fallback_claim_all["result"]["claimed_count"] == 1
    assert float(fallback_claim_all["result"]["cash_added"]) > 0

    # The legacy per-episode action-ad cap must never block the 3-minute then
    # 6-minute schedule from recording repeated real-playback mid-rolls.
    service.update_operating_policy("Bearer smoke-admin-token", {
        "interstitial_cooldown_seconds": 0,
        "interstitial_global_hourly_limit": 0,
        "episode_interstitial_limit": 1,
    })
    midroll_user = f"v1666-repeat-midroll-{uuid.uuid4()}"
    service.bootstrap(midroll_user)
    service.record_interstitial(midroll_user, event_id("midroll-first"), "int_episode_midroll", "owned:midroll:ep:1")
    repeat_midroll = service.record_interstitial(midroll_user, event_id("midroll-repeat"), "int_episode_midroll", "owned:midroll:ep:1")
    assert repeat_midroll["idempotent"] is False
    service.update_operating_policy("Bearer smoke-admin-token", {"interstitial_cooldown_seconds": 60})

    # A Crown level reached by either a normal rewarded completion or the
    # verified fallback settlement is part of the same cash result. This keeps
    # the mobile receipt and coin-to-wallet animation universal.
    with service._transaction() as connection:  # noqa: SLF001 - isolated smoke policy setup
        service._execute(connection, "UPDATE achievement_policy SET required_rounds = 1 WHERE code = 'BRONZE'")  # noqa: SLF001
    crown_cash_user = f"v1666-crown-direct-cash-{uuid.uuid4()}"
    service.bootstrap(crown_cash_user)
    crown_direct, _ = rewarded(service, crown_cash_user, "rv_crown_upgrade")
    assert crown_direct["result"]["action"] == "CROWN_UPGRADE"
    assert crown_direct["result"]["achievements_awarded"][0]["code"] == "BRONZE"
    assert float(crown_direct["result"]["cash_added"]) > 0

    crown_fallback_user = f"v1666-crown-fallback-cash-{uuid.uuid4()}"
    service.bootstrap(crown_fallback_user)
    crown_fallback_interstitial_id = event_id("int-crown-fallback")
    service.record_interstitial(crown_fallback_user, crown_fallback_interstitial_id, "int_rewarded_fallback")
    crown_fallback = service.settle_rewarded_fallback(
        user_id=crown_fallback_user,
        interstitial_event_id=crown_fallback_interstitial_id,
        original_placement_id="rv_crown_upgrade",
        target_id=None,
        episode_index=None,
    )
    assert crown_fallback["result"]["achievements_awarded"][0]["code"] == "BRONZE"
    assert float(crown_fallback["result"]["cash_added"]) > 0

    # 单条激励广告连续解锁一个两集集包；低频模式关闭单集广告翻倍。
    episode_pass, _ = rewarded(service, user_id, "rv_episode_pass", "owned:smoke:ep:4", 4)
    assert episode_pass["result"]["unlock_episode_count"] == 2
    boost_rejected = False
    try:
        rewarded(service, user_id, "rv_episode_reward_boost", "owned:smoke:ep:1")
    except Exception as error:  # noqa: BLE001 - 验证低频模式拒绝额外单集翻倍广告。
        boost_rejected = "未启用单集广告翻倍" in str(error)
    assert boost_rejected

    # 每集形成待领取奖励；随机宝箱已取消，并由广告领取的追剧任务红包替代。
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
        assert watch["result"]["random_chests_created"] == []
        watched_rewards.append(watch["result"]["episode_reward"]["gems"])

    after_watches = service.bootstrap(user_id)
    assert len(after_watches["episode_reward_claims"]) == 10
    assert after_watches["ring"]["claimable"] is True
    assert after_watches["ring"]["reward_gems"] == sum(watched_rewards)
    assert after_watches["chest"]["available_count"] == 0
    task_targets = [item["target"] for item in after_watches["pending_claims"]]
    assert task_targets[:4] == [1, 3, 6, 9]
    assert task_targets[-1] == 90
    assert after_watches["pending_claims"][0]["random_redpacket"] is True
    assert after_watches["pending_claims"][0]["reward_min_gems"] == 800
    assert after_watches["pending_claims"][0]["reward_max_gems"] == 1_200
    assert after_watches["pending_claims"][1]["reward_min_gems"] == 1_200
    assert after_watches["pending_claims"][1]["reward_max_gems"] == 1_800

    # 领取全部待领取分集奖励仍须先获得一次激励广告授权。
    authorization, authorization_event_id = rewarded(service, user_id, "rv_episode_rewards_claim")
    assert authorization["result"]["action"] == "EPISODE_REWARDS_CLAIM_AUTH"
    claim_all = service.claim_all_episode_rewards(user_id, event_id("claim-all"), authorization_event_id)
    assert claim_all["result"]["action"] == "EPISODE_REWARDS_CLAIM_ALL"
    assert claim_all["result"]["claimed_count"] == 10
    assert claim_all["result"]["boosted_count"] == 0
    assert claim_all["result"]["gems_added"] == sum(watched_rewards)
    assert float(claim_all["result"]["cash_added"]) > 0
    replay_claim = service.claim_all_episode_rewards(user_id, event_id("claim-replay"), authorization_event_id)
    assert replay_claim["idempotent"] is True

    # 追剧任务在完整观看任务领取广告后发放随机红包，并受同一阻尼逻辑影响。
    task_redpacket, _ = rewarded(service, user_id, "rv_episode_task_claim", "1")
    assert task_redpacket["result"]["action"] == "EPISODE_TASK_REDPACKET"
    assert 800 <= task_redpacket["result"]["gems_added"] <= 1_200
    duplicate_task_error = False
    try:
        rewarded(service, user_id, "rv_episode_task_claim", "1")
    except Exception as error:  # noqa: BLE001 - 验证任务红包只能领取一次。
        duplicate_task_error = "已领取" in str(error)
    assert duplicate_task_error

    # 每日任务按业务日独立重置：次日重新完成第 1 集后，可再次领取同一节点。
    original_business_date = service._business_date  # noqa: SLF001 - deterministic business-date boundary verification
    next_business_date = original_business_date() + timedelta(days=1)
    service._business_date = lambda: next_business_date  # type: ignore[method-assign] # noqa: SLF001
    try:
        day_two_watch = service.record_watch_event(
            user_id=user_id,
            event_id=event_id("watch-day-two"),
            drama_id="owned:smoke",
            episode_id="owned:smoke:ep:day-two",
            watched_seconds=60,
        )
        assert day_two_watch["snapshot"]["today"]["episode_count"] == 1
        assert day_two_watch["snapshot"]["pending_claims"][0]["state"] == "PENDING"
        day_two_task, _ = rewarded(service, user_id, "rv_episode_task_claim", "1")
        assert 800 <= day_two_task["result"]["gems_added"] <= 1_200
    finally:
        service._business_date = original_business_date  # type: ignore[method-assign] # noqa: SLF001

    # 皇冠成长积分：完整看一集 +2，皇冠页面完整看一条广告 +1；两次广告之间有充能 CD。
    crown_first, _ = rewarded(service, user_id, "rv_crown_upgrade")
    assert crown_first["snapshot"]["crown_progress"][0]["charge_cooldown_remaining_seconds"] > 0
    crown_cooldown_rejected = False
    try:
        rewarded(service, user_id, "rv_crown_upgrade")
    except Exception as error:  # noqa: BLE001 - 验证皇冠主动广告的独立充能冷却。
        crown_cooldown_rejected = "正在充能" in str(error)
    assert crown_cooldown_rejected
    service.update_operating_policy("Bearer smoke-admin-token", {"crown_rewarded_cooldown_seconds": 0})
    crown_second, _ = rewarded(service, user_id, "rv_crown_upgrade")
    bronze = crown_second["snapshot"]["crown_progress"][0]
    assert bronze["ad_equivalent_rounds"] >= crown_first["snapshot"]["crown_progress"][0]["ad_equivalent_rounds"] + 1
    for _ in range(8):
        rewarded(service, user_id, "rv_crown_upgrade")
    crown_daily_cap_rejected = False
    try:
        rewarded(service, user_id, "rv_crown_upgrade")
    except Exception as error:  # noqa: BLE001 - 验证皇冠主动激励广告独立日上限。
        crown_daily_cap_rejected = "皇冠成长广告次数已达上限" in str(error)
    assert crown_daily_cap_rejected

    # 横滑切剧与激励加载失败回退都有独立频控：不能被内容流插屏的共享 CD 静默吞掉。
    service.update_operating_policy("Bearer smoke-admin-token", {
        "interstitial_cooldown_seconds": 0,
        "interstitial_global_hourly_limit": 0,
        "interstitial_rewarded_skip_threshold": 0,
    })
    service.record_interstitial(user_id, event_id("midroll-before-exception"), "int_episode_midroll", "owned:smoke:ep:cooldown")
    service.update_operating_policy("Bearer smoke-admin-token", {
        "interstitial_cooldown_seconds": 600,
        "interstitial_global_hourly_limit": 1,
    })
    shared_cooldown_rejected = False
    try:
        service.record_interstitial(user_id, event_id("midroll-shared-cooldown"), "int_episode_midroll", "owned:smoke:ep:shared")
    except Exception as error:  # noqa: BLE001 - shared content placements retain the common CD.
        shared_cooldown_rejected = "冷却期" in str(error)
    assert shared_cooldown_rejected
    switch_exception = service.record_interstitial(user_id, event_id("switch-exception"), "int_drama_switch", "owned:smoke:ep:switch")
    fallback_exception = service.record_interstitial(user_id, event_id("fallback-exception"), "int_rewarded_fallback", "owned:smoke:ep:fallback")
    assert switch_exception["snapshot"]["interstitial"]["drama_switch_enabled"] is True
    assert fallback_exception["snapshot"]["interstitial"]["rewarded_fallback_enabled"] is True
    service.update_operating_policy("Bearer smoke-admin-token", {
        "interstitial_cooldown_seconds": 60,
        "interstitial_global_hourly_limit": 0,
    })

    # 直接现金钱包不再支持 Gems 兑换；钱包广告加速直接向同一个钱包发放现金。
    exchange_rejected = False
    try:
        service.exchange_gems(user_id, 10_000, event_id("exchange"))
    except Exception as error:  # noqa: BLE001 - compatibility endpoint must not reintroduce a conversion layer.
        exchange_rejected = "无需兑换" in str(error)
    assert exchange_rejected
    boost, _ = rewarded(service, user_id, "rv_release_boost")
    assert boost["result"]["action"] == "WALLET_CASH_BOOST"
    assert float(boost["result"]["cash_added"]) > 0
    assert float(boost["snapshot"]["wallet"]["cash_wallet"]) > float(claim_all["snapshot"]["wallet"]["cash_wallet"])
    assert boost["snapshot"]["wallet"]["release"]["charge_cooldown_remaining_seconds"] > 0
    assert boost["snapshot"]["wallet"]["release"]["boost_available"] is False

    # 市场本币：欢迎奖励与每集奖励直接展示本币现金，且仍提供美元参考值。
    br_user = f"v163-br-{uuid.uuid4()}"
    br_initial = service.bootstrap(br_user, "BR")
    assert br_initial["wallet"]["currency"]["code"] == "BRL"
    assert br_initial["wallet"]["currency"]["symbol"] == "R$"
    br_welcome, _ = rewarded(service, br_user, "rv_welcome_bonus")
    assert br_welcome["result"]["cash_added"] == "60.00"
    assert br_welcome["result"]["cash_added_usd_reference"] == "20.00"

    id_user = f"v163-id-{uuid.uuid4()}"
    id_initial = service.bootstrap(id_user, "ID")
    assert id_initial["wallet"]["currency"]["code"] == "IDR"
    assert id_initial["wallet"]["currency"]["fraction_digits"] == 0
    id_welcome, _ = rewarded(service, id_user, "rv_welcome_bonus")
    assert id_welcome["result"]["cash_added"] == "200000"
    assert id_welcome["result"]["cash_added_usd_reference"] == "20.00"

    hk_user = f"v164-hk-{uuid.uuid4()}"
    hk_initial = service.bootstrap(hk_user, "HK")
    assert hk_initial["wallet"]["currency"]["code"] == "USD"
    hk_welcome, _ = rewarded(service, hk_user, "rv_welcome_bonus")
    assert hk_welcome["result"]["cash_added"] == "20.00"
    assert hk_welcome["result"]["cash_added_usd_reference"] == "20.00"

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

    # 全渠道六档阻尼：内置奖励进度只用于后续现金奖励阻尼，任何渠道均使用同一套边界。
    damping_user = f"v163-damping-{uuid.uuid4()}"
    service.bootstrap(damping_user, "US")
    with service._transaction() as connection:  # noqa: SLF001 - boundary integration test setup
        policy = service._policy(connection)  # noqa: SLF001
        def total_at(value: int) -> None:
            service._execute(connection, "UPDATE users SET total_gems_earned = %s, gems_balance = 0, cash_wallet_micros = 0 WHERE user_id = %s", (value, damping_user))  # noqa: SLF001

        total_at(1_899_999)
        assert service._credit_gems(connection, damping_user, 10, policy, "WELCOME_BONUS", "damping", event_id("welcome")) == 10  # noqa: SLF001
        total_at(1_929_999)
        assert service._credit_gems(connection, damping_user, 10, policy, "SIGN_IN", "damping", event_id("signin")) == 5  # noqa: SLF001
        total_at(1_959_999)
        assert service._credit_gems(connection, damping_user, 10, policy, "EPISODE_REWARD_CLAIM", "damping", event_id("episode")) == 4  # noqa: SLF001
        total_at(1_979_999)
        assert service._credit_gems(connection, damping_user, 10, policy, "EPISODE_TASK_REDPACKET", "damping", event_id("task")) == 4  # noqa: SLF001
        total_at(1_989_999)
        assert service._credit_gems(connection, damping_user, 10, policy, "ACHIEVEMENT", "damping", event_id("crown")) == 2  # noqa: SLF001
        total_at(1_994_999)
        assert service._credit_gems(connection, damping_user, 10, policy, "EPISODE_TASK_REDPACKET", "damping", event_id("tier5")) == 1  # noqa: SLF001
        total_at(1_999_999)
        assert service._credit_gems(connection, damping_user, 100, policy, "WALLET_CASH_BOOST", "damping", event_id("tier6")) == 1  # noqa: SLF001

    print("PostgreSQL v1.6.6.2 rewards smoke test passed; welcome consolidation, verified fallback settlement, direct cash, cooldowns and Crown points verified")


if __name__ == "__main__":
    run()
