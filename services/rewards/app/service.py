from __future__ import annotations

import math
from contextlib import contextmanager
from datetime import UTC, date, datetime, timedelta
from typing import Any, Iterator
from zoneinfo import ZoneInfo

import psycopg
from psycopg.rows import dict_row

from app.config import Settings


class ServiceError(Exception):
    def __init__(self, code: int, message: str, status_code: int = 400) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


class RewardsService:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._business_timezone = ZoneInfo(settings.timezone)

    @contextmanager
    def _transaction(self) -> Iterator[psycopg.Connection[Any]]:
        with psycopg.connect(self.settings.database_url, row_factory=dict_row) as connection:
            with connection.transaction():
                yield connection

    def health(self) -> dict[str, Any]:
        with self._transaction() as connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT 1 AS ready")
                cursor.fetchone()
        return {"status": "ok", "database": "ok", "environment": self.settings.environment, "now": self._now()}

    def bootstrap(self, user_id: str) -> dict[str, Any]:
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            return self._snapshot(connection, user_id)

    def complete_rewarded_ad(
        self,
        *,
        user_id: str,
        ad_event_id: str,
        placement_id: str,
        target_id: str | None,
        episode_index: int | None,
        proof: str | None,
        timestamp_ms: int | None,
    ) -> dict[str, Any]:
        self._verify_ad_proof(proof)
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            business_date = self._business_date()
            existing = self._one(
                connection,
                "SELECT placement_id, result_payload FROM rewarded_ad_events WHERE ad_event_id = %s",
                (ad_event_id,),
            )
            if existing:
                return {"idempotent": True, "result": existing["result_payload"], "snapshot": self._snapshot(connection, user_id)}

            policy = self._policy(connection)
            counters = self._daily_counter(connection, user_id, business_date, lock=True)
            if counters["rv_count"] >= policy["rv_daily_limit"]:
                raise ServiceError(1001, "今日激励广告次数已达上限，请明天再来", 429)

            # 先执行业务前置检查，避免无效事件消耗用户的日频控。
            result = self._rewarded_action(
                connection=connection,
                user_id=user_id,
                business_date=business_date,
                placement_id=placement_id,
                target_id=target_id,
                episode_index=episode_index,
                policy=policy,
            )
            verification_method = "debug_test_proof" if self.settings.allow_debug_ad_proof else "server_callback"
            self._execute(
                connection,
                """
                INSERT INTO rewarded_ad_events
                (ad_event_id, user_id, business_date, placement_id, target_id, ad_timestamp, verification_method, result_payload)
                VALUES (%s, %s, %s, %s, %s, COALESCE(to_timestamp(%s / 1000.0), NOW()), %s, %s::jsonb)
                """,
                (
                    ad_event_id,
                    user_id,
                    business_date,
                    placement_id,
                    target_id,
                    timestamp_ms,
                    verification_method,
                    self._json(result),
                ),
            )
            self._execute(
                connection,
                "UPDATE daily_counters SET rv_count = rv_count + 1, last_rv_at = NOW() WHERE user_id = %s AND business_date = %s",
                (user_id, business_date),
            )
            # 关联该次广告与对应的权益动作。插屏冷却从该广告完成时重新开始。
            self._link_rewarded_event(connection, user_id, business_date, placement_id, target_id, ad_event_id)
            result["reset_int_timer"] = True
            return {"idempotent": False, "result": result, "snapshot": self._snapshot(connection, user_id)}

    def record_watch_event(
        self,
        *,
        user_id: str,
        event_id: str,
        drama_id: str,
        episode_id: str,
        watched_seconds: int,
    ) -> dict[str, Any]:
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            business_date = self._business_date()
            duplicate = self._one(connection, "SELECT event_id FROM watch_events WHERE event_id = %s", (event_id,))
            if duplicate:
                return {"idempotent": True, "result": {"gems_added": 0, "round_completed": False}, "snapshot": self._snapshot(connection, user_id)}

            policy = self._policy(connection)
            if watched_seconds < policy["valid_watch_seconds"]:
                raise ServiceError(1002, f"有效观看至少需要 {policy['valid_watch_seconds']} 秒", 422)

            round_row = self._one(
                connection,
                """
                SELECT id, round_number FROM episode_rounds
                WHERE user_id = %s AND business_date = %s AND episode_id = %s AND state = 'PENDING_WATCH'
                FOR UPDATE
                """,
                (user_id, business_date, episode_id),
            )
            if not round_row:
                raise ServiceError(1003, "该集尚未完成集间激励广告，无法计入奖励轮次", 409)

            reward_gems = self._round_reward(policy, round_row["round_number"])
            self._execute(
                connection,
                """
                INSERT INTO watch_events (event_id, user_id, business_date, drama_id, episode_id, watched_seconds)
                VALUES (%s, %s, %s, %s, %s, %s)
                """,
                (event_id, user_id, business_date, drama_id, episode_id, watched_seconds),
            )
            self._execute(
                connection,
                """
                UPDATE episode_rounds
                SET state = 'COMPLETED', watch_event_id = %s, base_reward_gems = %s, completed_at = NOW()
                WHERE id = %s
                """,
                (event_id, reward_gems, round_row["id"]),
            )
            self._execute(
                connection,
                """
                UPDATE daily_counters
                SET episode_count = episode_count + 1, completed_rounds = completed_rounds + 1
                WHERE user_id = %s AND business_date = %s
                """,
                (user_id, business_date),
            )
            self._credit_gems(
                connection,
                user_id=user_id,
                gems=reward_gems,
                policy=policy,
                entry_type="WATCH_EPISODE",
                reference_type="watch_event",
                reference_id=event_id,
            )
            pending_claim = self._create_pending_claim_if_due(connection, user_id, business_date, policy, round_row["round_number"])
            achievements = self._award_achievements_if_due(connection, user_id, policy)
            result = {
                "gems_added": reward_gems,
                "round_completed": True,
                "round_number": round_row["round_number"],
                "pending_claim": pending_claim,
                "achievements_awarded": achievements,
            }
            return {"idempotent": False, "result": result, "snapshot": self._snapshot(connection, user_id)}

    def checkin(self, user_id: str) -> dict[str, Any]:
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            business_date = self._business_date()
            existing = self._one(
                connection,
                "SELECT streak_day, base_gems, double_gems FROM checkins WHERE user_id = %s AND business_date = %s",
                (user_id, business_date),
            )
            if existing:
                return {"idempotent": True, "result": {"streak_day": existing["streak_day"], "gems_added": 0}, "snapshot": self._snapshot(connection, user_id)}

            previous = self._one(
                connection,
                """
                SELECT business_date, streak_day FROM checkins
                WHERE user_id = %s ORDER BY business_date DESC LIMIT 1 FOR UPDATE
                """,
                (user_id,),
            )
            if previous and previous["business_date"] == business_date - timedelta(days=1):
                streak_day = previous["streak_day"] % 30 + 1
            else:
                streak_day = 1
            reward = self._one(connection, "SELECT gems FROM signin_reward_tiers WHERE day_number = %s", (streak_day,))
            if not reward:
                raise ServiceError(1500, "数据库缺少签到奖励种子数据", 503)
            policy = self._policy(connection)
            self._execute(
                connection,
                "INSERT INTO checkins (user_id, business_date, streak_day, base_gems) VALUES (%s, %s, %s, %s)",
                (user_id, business_date, streak_day, reward["gems"]),
            )
            self._credit_gems(
                connection,
                user_id=user_id,
                gems=reward["gems"],
                policy=policy,
                entry_type="SIGN_IN",
                reference_type="checkin",
                reference_id=f"{user_id}:{business_date.isoformat()}",
            )
            achievements = self._award_achievements_if_due(connection, user_id, policy)
            return {
                "idempotent": False,
                "result": {"streak_day": streak_day, "gems_added": reward["gems"], "achievements_awarded": achievements},
                "snapshot": self._snapshot(connection, user_id),
            }

    def record_interstitial(self, user_id: str, event_id: str, placement_id: str) -> dict[str, Any]:
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            business_date = self._business_date()
            existing = self._one(connection, "SELECT event_id FROM interstitial_events WHERE event_id = %s", (event_id,))
            if existing:
                return {"idempotent": True, "snapshot": self._snapshot(connection, user_id)}
            policy = self._policy(connection)
            counters = self._daily_counter(connection, user_id, business_date, lock=True)
            now = self._now()
            if placement_id == "int_timer" and counters["last_rv_at"] and now - counters["last_rv_at"] < timedelta(seconds=policy["interstitial_cooldown_seconds"]):
                raise ServiceError(2001, "激励广告后冷却期内不展示插屏", 409)
            if counters["last_interstitial_at"] and now - counters["last_interstitial_at"] < timedelta(seconds=policy["interstitial_cooldown_seconds"]):
                raise ServiceError(2002, "插屏冷却期未结束", 409)
            hourly = self._one(
                connection,
                "SELECT COUNT(*) AS count FROM interstitial_events WHERE user_id = %s AND shown_at >= NOW() - INTERVAL '1 hour'",
                (user_id,),
            )["count"]
            if hourly >= policy["interstitial_hourly_limit"]:
                raise ServiceError(2003, "本小时插屏展示已达上限", 429)
            self._execute(
                connection,
                "INSERT INTO interstitial_events (event_id, user_id, business_date, placement_id) VALUES (%s, %s, %s, %s)",
                (event_id, user_id, business_date, placement_id),
            )
            self._execute(
                connection,
                "UPDATE daily_counters SET interstitial_count = interstitial_count + 1, last_interstitial_at = NOW() WHERE user_id = %s AND business_date = %s",
                (user_id, business_date),
            )
            return {"idempotent": False, "snapshot": self._snapshot(connection, user_id)}

    def withdrawal_preflight(self, user_id: str) -> dict[str, Any]:
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            policy = self._policy(connection)
            wallet = self._one(
                connection,
                "SELECT cash_wallet_micros, available_balance_micros FROM users WHERE user_id = %s",
                (user_id,),
            )
            enabled = bool(policy["withdrawal_enabled"])
            threshold = policy["withdrawal_threshold_micros"]
            available = wallet["available_balance_micros"]
            if not enabled:
                reason = "提现功能尚未启用；当前仅展示透明的积分与现金钱包预估。"
            elif available < threshold:
                reason = f"可提现余额未达到最低门槛 {self._money(threshold)}。"
            else:
                reason = "已满足余额条件；仍需通过正式 KYC 与支付服务校验。"
            return {
                "withdrawal_enabled": enabled,
                "eligible": enabled and available >= threshold,
                "reason": reason,
                "cash_wallet": self._money(wallet["cash_wallet_micros"]),
                "available_balance": self._money(available),
                "threshold": self._money(threshold),
            }

    def _rewarded_action(
        self,
        *,
        connection: psycopg.Connection[Any],
        user_id: str,
        business_date: date,
        placement_id: str,
        target_id: str | None,
        episode_index: int | None,
        policy: dict[str, Any],
    ) -> dict[str, Any]:
        if placement_id == "rv_episode_pass":
            if not target_id or episode_index is None:
                raise ServiceError(1101, "集间通行广告缺少 episode_id 或 episode_index", 422)
            if episode_index <= policy["free_episodes_count"]:
                raise ServiceError(1100, f"前 {policy['free_episodes_count']} 集为免费体验，无需激励广告", 409)
            duplicate_round = self._one(
                connection,
                "SELECT id, round_number FROM episode_rounds WHERE user_id = %s AND business_date = %s AND episode_id = %s",
                (user_id, business_date, target_id),
            )
            if duplicate_round:
                raise ServiceError(1102, "该集已经存在待完成或已完成的奖励轮次", 409)
            next_round = self._one(
                connection,
                "SELECT COALESCE(MAX(round_number), 0) + 1 AS next_round FROM episode_rounds WHERE user_id = %s AND business_date = %s",
                (user_id, business_date),
            )["next_round"]
            return {"action": "EPISODE_PASS", "episode_id": target_id, "round_number": next_round, "gems_added": 0, "round_incremented": False}

        if placement_id == "rv_claim_reward":
            claim = self._one(
                connection,
                """
                SELECT id, base_reward_gems, cycle_number FROM pending_claims
                WHERE user_id = %s AND business_date = %s AND state = 'PENDING'
                ORDER BY cycle_number ASC LIMIT 1 FOR UPDATE
                """,
                (user_id, business_date),
            )
            if not claim:
                raise ServiceError(1201, "暂无可领取的十轮奖励", 409)
            return {"action": "CLAIM_REWARD", "claim_id": claim["id"], "cycle_number": claim["cycle_number"], "gems_added": claim["base_reward_gems"], "can_double": True}

        if placement_id == "rv_double_reward":
            claim = self._one(
                connection,
                """
                SELECT id, base_reward_gems, cycle_number FROM pending_claims
                WHERE user_id = %s AND business_date = %s AND state = 'CLAIMED'
                ORDER BY cycle_number DESC LIMIT 1 FOR UPDATE
                """,
                (user_id, business_date),
            )
            if not claim:
                raise ServiceError(1202, "请先领取一笔十轮基础奖励后再翻倍", 409)
            return {"action": "DOUBLE_REWARD", "claim_id": claim["id"], "cycle_number": claim["cycle_number"], "gems_added": claim["base_reward_gems"], "can_double": False}

        if placement_id == "rv_chest_task":
            signed = self._one(connection, "SELECT 1 AS signed FROM checkins WHERE user_id = %s AND business_date = %s", (user_id, business_date))
            if not signed:
                raise ServiceError(1301, "签到后才可开启每日宝箱", 409)
            existing = self._one(connection, "SELECT 1 AS claimed FROM chest_claims WHERE user_id = %s AND business_date = %s", (user_id, business_date))
            if existing:
                raise ServiceError(1302, "今日宝箱已经领取", 409)
            return {"action": "CHEST_TASK", "gems_added": policy["chest_reward_gems"]}

        if placement_id == "rv_signin_double":
            checkin = self._one(
                connection,
                "SELECT base_gems, double_gems FROM checkins WHERE user_id = %s AND business_date = %s FOR UPDATE",
                (user_id, business_date),
            )
            if not checkin:
                raise ServiceError(1401, "请先完成今日签到", 409)
            if checkin["double_gems"] > 0:
                raise ServiceError(1402, "今日签到奖励已翻倍", 409)
            return {"action": "SIGNIN_DOUBLE", "gems_added": checkin["base_gems"]}

        raise ServiceError(1099, "不支持的激励广告位", 422)

    def _link_rewarded_event(
        self,
        connection: psycopg.Connection[Any],
        user_id: str,
        business_date: date,
        placement_id: str,
        target_id: str | None,
        ad_event_id: str,
    ) -> None:
        policy = self._policy(connection)
        event = self._one(connection, "SELECT result_payload FROM rewarded_ad_events WHERE ad_event_id = %s", (ad_event_id,))
        payload = event["result_payload"]
        action = payload["action"]
        if action == "EPISODE_PASS":
            self._execute(
                connection,
                """
                INSERT INTO episode_rounds (user_id, business_date, round_number, episode_id, rewarded_event_id, state)
                VALUES (%s, %s, %s, %s, %s, 'PENDING_WATCH')
                """,
                (user_id, business_date, payload["round_number"], target_id, ad_event_id),
            )
        elif action == "CLAIM_REWARD":
            self._execute(
                connection,
                "UPDATE pending_claims SET state = 'CLAIMED', claim_ad_event_id = %s, claimed_at = NOW() WHERE id = %s",
                (ad_event_id, payload["claim_id"]),
            )
            self._credit_gems(connection, user_id, payload["gems_added"], policy, "CLAIM_REWARD", "claim_ad", ad_event_id)
        elif action == "DOUBLE_REWARD":
            self._execute(
                connection,
                "UPDATE pending_claims SET state = 'DOUBLED', double_ad_event_id = %s, doubled_at = NOW() WHERE id = %s",
                (ad_event_id, payload["claim_id"]),
            )
            self._credit_gems(connection, user_id, payload["gems_added"], policy, "DOUBLE_REWARD", "double_ad", ad_event_id)
        elif action == "CHEST_TASK":
            self._execute(
                connection,
                "INSERT INTO chest_claims (user_id, business_date, ad_event_id, gems) VALUES (%s, %s, %s, %s)",
                (user_id, business_date, ad_event_id, payload["gems_added"]),
            )
            self._credit_gems(connection, user_id, payload["gems_added"], policy, "CHEST_TASK", "chest_ad", ad_event_id)
        elif action == "SIGNIN_DOUBLE":
            self._execute(
                connection,
                "UPDATE checkins SET double_gems = %s WHERE user_id = %s AND business_date = %s",
                (payload["gems_added"], user_id, business_date),
            )
            self._credit_gems(connection, user_id, payload["gems_added"], policy, "SIGNIN_DOUBLE", "signin_double_ad", ad_event_id)

    def _create_pending_claim_if_due(
        self,
        connection: psycopg.Connection[Any],
        user_id: str,
        business_date: date,
        policy: dict[str, Any],
        round_number: int,
    ) -> dict[str, Any] | None:
        size = policy["claim_round_size"]
        if round_number % size != 0:
            return None
        cycle_number = round_number // size
        reward = self._one(
            connection,
            """
            SELECT COALESCE(SUM(base_reward_gems), 0) AS gems
            FROM episode_rounds
            WHERE user_id = %s AND business_date = %s AND state = 'COMPLETED'
              AND round_number BETWEEN %s AND %s
            """,
            (user_id, business_date, round_number - size + 1, round_number),
        )["gems"]
        self._execute(
            connection,
            """
            INSERT INTO pending_claims (user_id, business_date, cycle_number, round_from, round_to, base_reward_gems)
            VALUES (%s, %s, %s, %s, %s, %s)
            ON CONFLICT (user_id, business_date, cycle_number) DO NOTHING
            """,
            (user_id, business_date, cycle_number, round_number - size + 1, round_number, reward),
        )
        return {"cycle_number": cycle_number, "gems": reward}

    def _award_achievements_if_due(self, connection: psycopg.Connection[Any], user_id: str, policy: dict[str, Any]) -> list[dict[str, Any]]:
        awarded: list[dict[str, Any]] = []
        policies = self._all(connection, "SELECT * FROM achievement_policy ORDER BY window_hours ASC")
        now = self._now()
        for item in policies:
            exists = self._one(connection, "SELECT 1 AS earned FROM user_achievements WHERE user_id = %s AND code = %s", (user_id, item["code"]))
            if exists:
                continue
            start = now - timedelta(hours=item["window_hours"])
            rounds = self._one(
                connection,
                "SELECT COUNT(*) AS count FROM episode_rounds WHERE user_id = %s AND state = 'COMPLETED' AND completed_at >= %s",
                (user_id, start),
            )["count"]
            if rounds < item["required_rounds"]:
                continue
            signins = self._one(connection, "SELECT COUNT(*) AS count FROM checkins WHERE user_id = %s AND created_at >= %s", (user_id, start))["count"]
            if signins < item["required_signin_days"]:
                continue
            rvs = self._one(connection, "SELECT COUNT(*) AS count FROM rewarded_ad_events WHERE user_id = %s AND processed_at >= %s", (user_id, start))["count"]
            if rvs < item["required_rv_count"]:
                continue
            self._execute(
                connection,
                "INSERT INTO user_achievements (user_id, code, gems, virtual_entitlement) VALUES (%s, %s, %s, %s)",
                (user_id, item["code"], item["reward_gems"], item["virtual_entitlement"]),
            )
            self._credit_gems(connection, user_id, item["reward_gems"], policy, "ACHIEVEMENT", "achievement", f"{user_id}:{item['code']}")
            awarded.append({"code": item["code"], "gems": item["reward_gems"], "entitlement": item["virtual_entitlement"]})
        return awarded

    def _snapshot(self, connection: psycopg.Connection[Any], user_id: str) -> dict[str, Any]:
        business_date = self._business_date()
        policy = self._policy(connection)
        user = self._one(connection, "SELECT * FROM users WHERE user_id = %s", (user_id,))
        counters = self._daily_counter(connection, user_id, business_date)
        checkin = self._one(connection, "SELECT streak_day, base_gems, double_gems FROM checkins WHERE user_id = %s AND business_date = %s", (user_id, business_date))
        previous = self._one(
            connection,
            "SELECT business_date, streak_day FROM checkins WHERE user_id = %s AND business_date < %s ORDER BY business_date DESC LIMIT 1",
            (user_id, business_date),
        )
        next_streak = checkin["streak_day"] if checkin else (previous["streak_day"] % 30 + 1 if previous and previous["business_date"] == business_date - timedelta(days=1) else 1)
        next_reward = self._one(connection, "SELECT gems FROM signin_reward_tiers WHERE day_number = %s", (next_streak,))["gems"]
        tiers = self._all(connection, "SELECT day_number, gems FROM signin_reward_tiers ORDER BY day_number")
        claims = self._all(
            connection,
            "SELECT cycle_number, round_from, round_to, base_reward_gems, state FROM pending_claims WHERE user_id = %s AND business_date = %s ORDER BY cycle_number",
            (user_id, business_date),
        )
        chest = self._one(connection, "SELECT 1 AS claimed FROM chest_claims WHERE user_id = %s AND business_date = %s", (user_id, business_date))
        achievements = self._all(connection, "SELECT code, gems, virtual_entitlement, earned_at FROM user_achievements WHERE user_id = %s ORDER BY earned_at", (user_id,))
        recent_ledger = self._all(
            connection,
            """
            SELECT entry_type, gems_delta, cash_wallet_delta_micros, available_balance_delta_micros, created_at
            FROM ledger_entries WHERE user_id = %s ORDER BY id DESC LIMIT 20
            """,
            (user_id,),
        )
        next_claim_round = (counters["completed_rounds"] // policy["claim_round_size"] + 1) * policy["claim_round_size"]
        return {
            "business_date": business_date.isoformat(),
            "wallet": {
                "gems_balance": user["gems_balance"],
                "cash_wallet": self._money(user["cash_wallet_micros"]),
                "available_balance": self._money(user["available_balance_micros"]),
                "total_gems_earned": user["total_gems_earned"],
                "exchange_rate_gems": policy["exchange_rate_gems"],
            },
            "today": {
                "episode_count": counters["episode_count"],
                "rv_count": counters["rv_count"],
                "rv_daily_limit": policy["rv_daily_limit"],
                "completed_rounds": counters["completed_rounds"],
                "claim_round_size": policy["claim_round_size"],
                "next_claim_round": next_claim_round,
            },
            "checkin": {
                "signed_today": checkin is not None,
                "streak_day": next_streak,
                "today_base_gems": checkin["base_gems"] if checkin else next_reward,
                "today_double_gems": checkin["double_gems"] if checkin else 0,
                "tiers": tiers,
            },
            "chest": {
                "requires_checkin": True,
                "claimable": checkin is not None and chest is None,
                "claimed": chest is not None,
                "reward_gems": policy["chest_reward_gems"],
            },
            "pending_claims": claims,
            "achievements": achievements,
            "episode_unlock": {
                "free_episodes_count": policy["free_episodes_count"],
                "placement_id": "rv_episode_pass",
                "valid_watch_seconds": policy["valid_watch_seconds"],
            },
            "interstitial": {
                "cooldown_seconds": policy["interstitial_cooldown_seconds"],
                "hourly_limit": policy["interstitial_hourly_limit"],
                "last_rv_at": counters["last_rv_at"].isoformat() if counters["last_rv_at"] else None,
                "last_interstitial_at": counters["last_interstitial_at"].isoformat() if counters["last_interstitial_at"] else None,
            },
            "withdrawal": self.withdrawal_preflight_inline(connection, user_id, policy, user),
            "ledger": [
                {
                    "type": item["entry_type"],
                    "gems_delta": item["gems_delta"],
                    "cash_wallet_delta": self._money(item["cash_wallet_delta_micros"]),
                    "available_balance_delta": self._money(item["available_balance_delta_micros"]),
                    "created_at": item["created_at"].isoformat(),
                }
                for item in recent_ledger
            ],
        }

    def withdrawal_preflight_inline(self, connection: psycopg.Connection[Any], user_id: str, policy: dict[str, Any], user: dict[str, Any]) -> dict[str, Any]:
        enabled = bool(policy["withdrawal_enabled"])
        threshold = policy["withdrawal_threshold_micros"]
        available = user["available_balance_micros"]
        if not enabled:
            reason = "提现功能尚未启用；当前仅展示透明的积分与现金钱包预估。"
        elif available < threshold:
            reason = f"可提现余额未达到最低门槛 {self._money(threshold)}。"
        else:
            reason = "已满足余额条件；仍需通过正式 KYC 与支付服务校验。"
        return {"enabled": enabled, "eligible": enabled and available >= threshold, "reason": reason, "threshold": self._money(threshold)}

    def _credit_gems(
        self,
        connection: psycopg.Connection[Any],
        user_id: str,
        gems: int,
        policy: dict[str, Any],
        entry_type: str,
        reference_type: str,
        reference_id: str,
    ) -> None:
        if gems <= 0:
            return
        existing = self._one(connection, "SELECT id FROM ledger_entries WHERE reference_type = %s AND reference_id = %s", (reference_type, reference_id))
        if existing:
            return
        user = self._one(connection, "SELECT total_gems_earned FROM users WHERE user_id = %s FOR UPDATE", (user_id,))
        before_wallet = self._one(connection, "SELECT cash_wallet_micros FROM users WHERE user_id = %s", (user_id,))["cash_wallet_micros"]
        total = user["total_gems_earned"] + gems
        after_wallet = (total * 1_000_000) // policy["exchange_rate_gems"]
        self._execute(
            connection,
            """
            UPDATE users
            SET gems_balance = gems_balance + %s,
                total_gems_earned = %s,
                cash_wallet_micros = %s,
                updated_at = NOW()
            WHERE user_id = %s
            """,
            (gems, total, after_wallet, user_id),
        )
        self._execute(
            connection,
            """
            INSERT INTO ledger_entries
            (user_id, entry_type, gems_delta, cash_wallet_delta_micros, reference_type, reference_id)
            VALUES (%s, %s, %s, %s, %s, %s)
            """,
            (user_id, entry_type, gems, after_wallet - before_wallet, reference_type, reference_id),
        )

    def _ensure_user(self, connection: psycopg.Connection[Any], user_id: str) -> None:
        self._execute(connection, "INSERT INTO users (user_id) VALUES (%s) ON CONFLICT (user_id) DO NOTHING", (user_id,))
        self._daily_counter(connection, user_id, self._business_date())

    def _daily_counter(self, connection: psycopg.Connection[Any], user_id: str, business_date: date, lock: bool = False) -> dict[str, Any]:
        self._execute(
            connection,
            "INSERT INTO daily_counters (user_id, business_date) VALUES (%s, %s) ON CONFLICT (user_id, business_date) DO NOTHING",
            (user_id, business_date),
        )
        sql = "SELECT * FROM daily_counters WHERE user_id = %s AND business_date = %s" + (" FOR UPDATE" if lock else "")
        row = self._one(connection, sql, (user_id, business_date))
        if row is None:
            raise ServiceError(1501, "无法初始化每日奖励计数", 503)
        return row

    def _policy(self, connection: psycopg.Connection[Any]) -> dict[str, Any]:
        policy = self._one(connection, "SELECT * FROM reward_policy WHERE id = 1")
        if not policy:
            raise ServiceError(1502, "数据库未初始化奖励规则，请执行 sql/001_init.sql", 503)
        return policy

    def _verify_ad_proof(self, proof: str | None) -> None:
        if self.settings.allow_debug_ad_proof and proof == "TEST_PANGLE_REWARD":
            return
        if self.settings.pangle_callback_secret and proof == self.settings.pangle_callback_secret:
            return
        raise ServiceError(1403, "广告奖励尚未由服务端验证", 403)

    def _round_reward(self, policy: dict[str, Any], round_number: int) -> int:
        raw = policy["r0_gems"] * math.pow(float(round_number), -float(policy["decay_alpha"]))
        return max(int(raw), int(policy["floor_gems"]))

    def _business_date(self) -> date:
        return self._now().date()

    def _now(self) -> datetime:
        return datetime.now(UTC).astimezone(self._business_timezone)

    @staticmethod
    def _money(micros: int) -> str:
        return f"{micros / 1_000_000:.2f}"

    @staticmethod
    def _json(value: dict[str, Any]) -> str:
        import json

        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))

    @staticmethod
    def _execute(connection: psycopg.Connection[Any], sql: str, params: tuple[Any, ...] = ()) -> None:
        with connection.cursor() as cursor:
            cursor.execute(sql, params)

    @staticmethod
    def _one(connection: psycopg.Connection[Any], sql: str, params: tuple[Any, ...] = ()) -> dict[str, Any] | None:
        with connection.cursor(row_factory=dict_row) as cursor:
            cursor.execute(sql, params)
            return cursor.fetchone()

    @staticmethod
    def _all(connection: psycopg.Connection[Any], sql: str, params: tuple[Any, ...] = ()) -> list[dict[str, Any]]:
        with connection.cursor(row_factory=dict_row) as cursor:
            cursor.execute(sql, params)
            return list(cursor.fetchall())
