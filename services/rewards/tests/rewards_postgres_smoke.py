from __future__ import annotations

import os
import sys
import uuid
from datetime import timedelta
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from app.config import Settings
from app.service import RewardsService


def event_id(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4()}"


def run() -> None:
    settings = Settings.from_environment()
    service = RewardsService(settings)
    user_id = f"v3-smoke-{uuid.uuid4()}"

    initial = service.bootstrap(user_id)
    assert initial["wallet"]["gems_balance"] == 0
    assert initial["checkin"]["signed_today"] is False
    assert initial["checkin"]["completed_streak"] == 0
    assert initial["checkin"]["today_streak"] == 1
    assert initial["welcome"]["available"] is True
    assert initial["ring"]["target"] == 1  # D0: 每完成 1 集即可形成一轮领取。

    welcome = service.claim_welcome_bonus(user_id)
    assert welcome["result"]["gems_added"] == 150000
    assert welcome["snapshot"]["welcome"]["claimed"] is True

    # 首日未签到时，第 1 天是可领取态而不是已完成态；签到入账只发生一次。
    checkin = service.checkin(user_id)
    assert checkin["result"]["gems_added"] == 500
    assert checkin["snapshot"]["checkin"]["signed_today"] is True
    assert checkin["snapshot"]["checkin"]["completed_streak"] == 1

    # 前 3 集免费也可以完成有效观看、推进轮次和皇冠统计；无需广告通行事件。
    for episode_index in range(1, 4):
        watch = service.record_watch_event(
            user_id=user_id,
            event_id=event_id("free-watch"),
            drama_id="owned:smoke",
            episode_id=f"owned:smoke:ep:{episode_index}",
            watched_seconds=60,
        )
        assert watch["result"]["gems_added"] == 0
        assert watch["result"]["claim_stride"] == 1

    after_free = service.bootstrap(user_id)
    assert after_free["today"]["episode_count"] == 3
    assert len(after_free["pending_claims"]) == 3
    assert after_free["crown_progress"]

    claimed = service.complete_rewarded_ad(
        user_id=user_id,
        ad_event_id=event_id("claim"),
        placement_id="rv_claim_reward",
        target_id=None,
        episode_index=None,
        proof="TEST_PANGLE_REWARD",
        timestamp_ms=None,
    )
    assert claimed["result"]["gems_added"] > 0

    # 主动兑换仅将 L1 宝石转入待释放余额；不会自动伪造为可提现余额。
    exchanged = service.exchange_gems(user_id, 10000, event_id("exchange"))
    assert exchanged["result"]["gems_spent"] == 10000
    assert exchanged["snapshot"]["wallet"]["available_balance"] == "0.00"
    assert float(exchanged["snapshot"]["wallet"]["wallet_locked"]) > 0

    # 模拟下一个业务日登录，验证服务端补算基础释放，而非依赖客户端定时器。
    with service._transaction() as connection:  # noqa: SLF001 - intentional integration test setup
        yesterday = service._business_date() - timedelta(days=1)  # noqa: SLF001
        service._execute(connection, "UPDATE users SET last_wallet_release_date = %s, last_active_date = %s WHERE user_id = %s", (yesterday, yesterday, user_id))  # noqa: SLF001
    released = service.bootstrap(user_id)
    assert released["wallet"]["release"]["released_today"] is True
    assert float(released["wallet"]["available_balance"]) > 0

    # 有待释放余额时可触发受日限控制的广告加速；具体金额由 geo_wallet_policy 决定。
    # 此处余额可能已在基础释放中全部释放，因此只验证接口和快照字段存在。
    assert released["wallet"]["release"]["boost_cap"] == 3
    assert released["withdrawal"]["enabled"] is False
    print("PostgreSQL v3 rewards smoke test passed; welcome, sign-in, free-watch, exchange and release verified")


if __name__ == "__main__":
    run()
