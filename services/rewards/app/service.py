from __future__ import annotations

import hmac
import math
import secrets
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

    def bootstrap(self, user_id: str, region: str | None = None) -> dict[str, Any]:
        with self._transaction() as connection:
            self._ensure_user(connection, user_id, region)
            self._ensure_random_chests(connection, user_id, self._business_date(), self._policy(connection))
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
            rv_daily_limit = int(policy["rv_daily_limit"])
            if rv_daily_limit > 0 and int(counters["rv_count"]) >= rv_daily_limit:
                raise ServiceError(1001, "今日激励广告次数已达上限，请明天再来", 429)
            if placement_id == "rv_crown_upgrade":
                crown_daily_limit = int(policy.get("crown_rewarded_daily_limit", 0))
                crown_used_today = int(self._one(
                    connection,
                    "SELECT COUNT(*) AS count FROM rewarded_ad_events WHERE user_id = %s AND business_date = %s AND placement_id = 'rv_crown_upgrade'",
                    (user_id, business_date),
                )["count"])
                if crown_daily_limit > 0 and crown_used_today >= crown_daily_limit:
                    raise ServiceError(1003, "今日皇冠成长广告次数已达上限，请明天继续", 429)

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
                """
                UPDATE daily_counters
                SET rv_count = rv_count + 1,
                    last_rv_at = NOW(),
                    last_interstitial_at = NOW(),
                    rewarded_decline_count = 0,
                    last_rewarded_declined_at = NULL
                WHERE user_id = %s AND business_date = %s
                """,
                (user_id, business_date),
            )
            # Associate the ad with the entitlement and settle cash directly into the single wallet.
            # The historical Gems field remains an internal damping/progress unit only.
            credited_units = self._link_rewarded_event(connection, user_id, business_date, placement_id, target_id, ad_event_id)
            if credited_units is not None:
                wallet_policy = self._wallet_policy(connection, user_id)
                credited_micros = self._reward_units_to_micros(credited_units, wallet_policy)
                result["gems_added"] = credited_units  # legacy API compatibility; never presented as Gems by v1.6.4.
                result["cash_added"] = self._money(credited_micros, wallet_policy)
                result["cash_added_usd_reference"] = self._usd_reference_money(credited_micros, wallet_policy)
                self._execute(
                    connection,
                    "UPDATE rewarded_ad_events SET result_payload = %s::jsonb WHERE ad_event_id = %s",
                    (self._json(result), ad_event_id),
                )
            achievements = self._award_achievements_if_due(connection, user_id, policy)
            result["achievements_awarded"] = achievements
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
            already_rewarded = self._one(
                connection,
                "SELECT id FROM episode_reward_claims WHERE user_id = %s AND episode_id = %s",
                (user_id, episode_id),
            )
            if duplicate or already_rewarded:
                return {"idempotent": True, "result": {"gems_added": 0, "episode_reward": None, "ten_round_claim": None}, "snapshot": self._snapshot(connection, user_id)}

            policy = self._policy(connection)
            if watched_seconds < policy["valid_watch_seconds"]:
                raise ServiceError(1002, f"有效观看至少需要 {policy['valid_watch_seconds']} 秒", 422)

            # 免费集与广告解锁集均可产生有效观看。广告仅换取内容通行，不可作为奖励记账的前置条件。
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
                UPDATE daily_counters
                SET episode_count = episode_count + 1, completed_rounds = completed_rounds + 1,
                    content_seconds = content_seconds + %s
                WHERE user_id = %s AND business_date = %s
                """,
                (watched_seconds, user_id, business_date),
            )
            counters = self._daily_counter(connection, user_id, business_date, lock=True)
            lifetime_episode_count = int(self._one(
                connection, "SELECT COUNT(*) AS count FROM watch_events WHERE user_id = %s", (user_id,)
            )["count"])
            episode_reward = self._create_episode_reward_claim(connection, user_id, business_date, episode_id, policy)
            chests_created = self._ensure_random_chests(connection, user_id, business_date, policy, lifetime_episode_count)
            self._ensure_episode_task_claims(connection, user_id, lifetime_episode_count)
            achievements = self._award_achievements_if_due(connection, user_id, policy)
            result = {
                "gems_added": 0,
                "episode_count": counters["episode_count"],
                "lifetime_episode_count": lifetime_episode_count,
                "episode_reward": episode_reward,
                "random_chests_created": chests_created,
                "achievements_awarded": achievements,
            }
            return {"idempotent": False, "result": result, "snapshot": self._snapshot(connection, user_id)}

    def claim_episode_task(self, user_id: str, milestone_episode_count: int, reference_id: str) -> dict[str, Any]:
        """Claim one completed watch-episode milestone directly into the user's cash wallet."""
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            policy = self._policy(connection)
            task = self._one(
                connection,
                "SELECT reward_gems FROM episode_task_policy WHERE milestone_episode_count = %s",
                (milestone_episode_count,),
            )
            if not task:
                raise ServiceError(1221, "该追剧任务不存在", 404)
            lifetime_episode_count = int(self._one(
                connection, "SELECT COUNT(*) AS count FROM watch_events WHERE user_id = %s", (user_id,)
            )["count"])
            if lifetime_episode_count < milestone_episode_count:
                raise ServiceError(1222, "追剧任务尚未完成", 409)
            self._ensure_episode_task_claims(connection, user_id, lifetime_episode_count)
            claim = self._one(
                connection,
                """
                SELECT state FROM episode_task_claims
                WHERE user_id = %s AND milestone_episode_count = %s FOR UPDATE
                """,
                (user_id, milestone_episode_count),
            )
            if not claim or claim["state"] != "PENDING":
                return {"idempotent": True, "result": {"action": "EPISODE_TASK_CLAIM", "cash_added": "0.00"}, "snapshot": self._snapshot(connection, user_id)}
            credited_units = self._credit_gems(
                connection, user_id, int(task["reward_gems"]), policy,
                "EPISODE_TASK_CLAIM", "episode_task", reference_id,
            )
            self._execute(
                connection,
                "UPDATE episode_task_claims SET state = 'CLAIMED', claimed_at = NOW() WHERE user_id = %s AND milestone_episode_count = %s",
                (user_id, milestone_episode_count),
            )
            wallet_policy = self._wallet_policy(connection, user_id)
            credited_micros = self._reward_units_to_micros(credited_units, wallet_policy)
            return {
                "idempotent": False,
                "result": {
                    "action": "EPISODE_TASK_CLAIM",
                    "cash_added": self._money(credited_micros, wallet_policy),
                    "cash_added_usd_reference": self._usd_reference_money(credited_micros, wallet_policy),
                },
                "snapshot": self._snapshot(connection, user_id),
            }

    def record_rewarded_skipped(self, user_id: str, context: str) -> dict[str, Any]:
        """Record a deliberately skipped/cancelled rewarded placement for cooldown-based interstitial rules."""
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            business_date = self._business_date()
            self._daily_counter(connection, user_id, business_date, lock=True)
            self._execute(
                connection,
                """
                UPDATE daily_counters
                SET rewarded_decline_count = rewarded_decline_count + 1,
                    last_rewarded_declined_at = NOW()
                WHERE user_id = %s AND business_date = %s
                """,
                (user_id, business_date),
            )
            return {"context": context, "snapshot": self._snapshot(connection, user_id)}

    def checkin(self, user_id: str) -> dict[str, Any]:
        # v7 起签到必须由 rv_checkin_complete 广告事件结算；保留方法仅防止旧客户端绕过广告。
        raise ServiceError(1404, "签到需要完整观看激励广告，请升级客户端后重试", 409)

    def _legacy_checkin(self, user_id: str) -> dict[str, Any]:
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
            self._ensure_random_chests(connection, user_id, business_date, policy)
            achievements = self._award_achievements_if_due(connection, user_id, policy)
            return {
                "idempotent": False,
                "result": {"streak_day": streak_day, "gems_added": reward["gems"], "achievements_awarded": achievements},
                "snapshot": self._snapshot(connection, user_id),
            }

    def claim_welcome_bonus(self, user_id: str) -> dict[str, Any]:
        # v7 起欢迎奖励必须由 rv_welcome_bonus 广告事件结算；保留方法仅防止旧客户端绕过广告。
        raise ServiceError(1601, "欢迎奖励需要完整观看激励广告，请升级客户端后重试", 409)

    def claim_review_reward(self, user_id: str, reference_id: str) -> dict[str, Any]:
        """Grant the server-configured, once-only review reward after an explicit client confirmation.

        Android store surfaces do not provide a verifiable star-rating callback. The UI therefore opens the
        store first and requires a second explicit confirmation; the ledger enforces one grant per user.
        """
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            existing = self._one(connection, "SELECT gems FROM app_review_rewards WHERE user_id = %s FOR UPDATE", (user_id,))
            if existing:
                return {"idempotent": True, "result": {"action": "APP_REVIEW", "gems_added": 0}, "snapshot": self._snapshot(connection, user_id)}
            policy = self._policy(connection)
            configured_gems = int(policy["review_reward_gems"])
            self._execute(
                connection,
                "INSERT INTO app_review_rewards (user_id, gems, reference_id) VALUES (%s, %s, %s)",
                (user_id, configured_gems, reference_id),
            )
            credited = self._credit_gems(connection, user_id, configured_gems, policy, "APP_REVIEW", "app_review", reference_id)
            wallet_policy = self._wallet_policy(connection, user_id)
            credited_micros = self._reward_units_to_micros(credited, wallet_policy)
            return {"idempotent": False, "result": {"action": "APP_REVIEW", "gems_added": credited, "cash_added": self._money(credited_micros, wallet_policy), "cash_added_usd_reference": self._usd_reference_money(credited_micros, wallet_policy)}, "snapshot": self._snapshot(connection, user_id)}

    def _legacy_claim_welcome_bonus(self, user_id: str) -> dict[str, Any]:
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            self._apply_wallet_release_on_active_day(connection, user_id)
            welcome = self._one(
                connection,
                "SELECT gems, state FROM welcome_rewards WHERE user_id = %s FOR UPDATE",
                (user_id,),
            )
            if not welcome:
                raise ServiceError(1600, "欢迎奖励尚未初始化", 503)
            if welcome["state"] == "CLAIMED":
                return {"idempotent": True, "result": {"gems_added": 0}, "snapshot": self._snapshot(connection, user_id)}
            policy = self._policy(connection)
            self._credit_gems(
                connection, user_id, int(welcome["gems"]), policy,
                "WELCOME_BONUS", "welcome_bonus", user_id,
            )
            self._execute(
                connection,
                "UPDATE welcome_rewards SET state = 'CLAIMED', claimed_at = NOW() WHERE user_id = %s",
                (user_id,),
            )
            self._execute(connection, "UPDATE users SET welcome_claimed_at = NOW() WHERE user_id = %s", (user_id,))
            return {
                "idempotent": False,
                "result": {"action": "WELCOME_BONUS", "gems_added": int(welcome["gems"])},
                "snapshot": self._snapshot(connection, user_id),
            }

    def claim_episode_reward(self, user_id: str, episode_id: str, reference_id: str) -> dict[str, Any]:
        # v7 仅允许使用一次激励广告授权后的聚合领取，避免旧端点绕过广告。
        raise ServiceError(1218, "请先完整观看领取宝石的激励广告，再使用全部领取", 409)

    def claim_all_episode_rewards(self, user_id: str, reference_id: str, ad_event_id: str) -> dict[str, Any]:
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            authorization = self._one(
                connection,
                """
                SELECT ad_event_id, used_at FROM episode_reward_claim_authorizations
                WHERE ad_event_id = %s AND user_id = %s FOR UPDATE
                """,
                (ad_event_id, user_id),
            )
            if not authorization:
                raise ServiceError(1216, "请先完整观看领取宝石的激励广告", 409)
            if authorization["used_at"] is not None:
                return {
                    "idempotent": True,
                    "result": {"action": "EPISODE_REWARDS_CLAIM_ALL", "gems_added": 0, "claimed_count": 0},
                    "snapshot": self._snapshot(connection, user_id),
                }
            claims = self._all(
                connection,
                """
                SELECT id, episode_id, base_reward_gems FROM episode_reward_claims
                WHERE user_id = %s AND state = 'PENDING'
                ORDER BY id FOR UPDATE
                """,
                (user_id,),
            )
            if not claims:
                self._execute(
                    connection,
                    "UPDATE episode_reward_claim_authorizations SET used_at = NOW() WHERE ad_event_id = %s",
                    (ad_event_id,),
                )
                return {
                    "idempotent": True,
                    "result": {"action": "EPISODE_REWARDS_CLAIM_ALL", "gems_added": 0, "claimed_count": 0},
                    "snapshot": self._snapshot(connection, user_id),
                }
            policy = self._policy(connection)
            total = 0
            boosted_count = 0
            for claim in claims:
                boosted = self._one(
                    connection,
                    "SELECT 1 AS enabled FROM episode_reward_boosts WHERE user_id = %s AND episode_id = %s",
                    (user_id, claim["episode_id"]),
                ) is not None
                gems = int(claim["base_reward_gems"]) * (2 if boosted else 1)
                claim_reference = f"{reference_id}:{claim['id']}"
                credited = self._credit_gems(
                    connection, user_id, gems, policy,
                    "EPISODE_REWARD_CLAIM", "episode_reward_claim_all", claim_reference,
                )
                self._execute(
                    connection,
                    """
                    UPDATE episode_reward_claims
                    SET state = %s, claim_reference_id = %s, claimed_at = NOW()
                    WHERE id = %s
                    """,
                    ("DOUBLED" if boosted else "CLAIMED", claim_reference, claim["id"]),
                )
                total += credited
                boosted_count += 1 if boosted else 0
            self._execute(
                connection,
                "UPDATE episode_reward_claim_authorizations SET used_at = NOW() WHERE ad_event_id = %s",
                (ad_event_id,),
            )
            return {
                "idempotent": False,
                "result": {"action": "EPISODE_REWARDS_CLAIM_ALL", "gems_added": total, "cash_added": self._money(self._reward_units_to_micros(total, self._wallet_policy(connection, user_id)), self._wallet_policy(connection, user_id)), "cash_added_usd_reference": self._usd_reference_money(self._reward_units_to_micros(total, self._wallet_policy(connection, user_id)), self._wallet_policy(connection, user_id)), "claimed_count": len(claims), "boosted_count": boosted_count},
                "snapshot": self._snapshot(connection, user_id),
            }

    def claim_ten_round_bonus(self, user_id: str, cycle_number: int, reference_id: str) -> dict[str, Any]:
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            claim = self._one(
                connection,
                """
                SELECT id, base_reward_gems, state FROM pending_claims
                WHERE user_id = %s AND cycle_number = %s AND claim_kind = 'TEN_ROUND' FOR UPDATE
                """,
                (user_id, cycle_number),
            )
            if not claim:
                raise ServiceError(1220, "该十轮追剧奖励尚未达成", 409)
            if claim["state"] != "PENDING":
                return {"idempotent": True, "result": {"action": "TEN_ROUND_CLAIM", "gems_added": 0}, "snapshot": self._snapshot(connection, user_id)}
            policy = self._policy(connection)
            self._credit_gems(
                connection, user_id, int(claim["base_reward_gems"]), policy,
                "TEN_ROUND_CLAIM", "ten_round_claim", reference_id,
            )
            self._execute(
                connection,
                "UPDATE pending_claims SET state = 'CLAIMED', claim_ad_event_id = NULL, claimed_at = NOW() WHERE id = %s",
                (claim["id"],),
            )
            return {
                "idempotent": False,
                "result": {"action": "TEN_ROUND_CLAIM", "gems_added": int(claim["base_reward_gems"]), "can_double": True},
                "snapshot": self._snapshot(connection, user_id),
            }

    def admin_reward_policy(self, authorization: str | None) -> dict[str, Any]:
        self._verify_admin_token(authorization)
        with self._transaction() as connection:
            policy = self._policy(connection)
            return self._admin_policy_with_crowns(connection, policy)

    def update_damping(self, authorization: str | None, decay_alpha: float) -> dict[str, Any]:
        self._verify_admin_token(authorization)
        with self._transaction() as connection:
            policy = self._policy(connection)
            old_value = str(policy["decay_alpha"])
            new_value = f"{decay_alpha:.4f}"
            self._execute(
                connection,
                "UPDATE reward_policy SET decay_alpha = %s, updated_at = NOW() WHERE id = 1",
                (new_value,),
            )
            self._execute(
                connection,
                "INSERT INTO reward_policy_admin_audit (actor, field_name, old_value, new_value) VALUES (%s, 'decay_alpha', %s, %s)",
                ("dashboard", old_value, new_value),
            )
            updated = self._policy(connection)
            return self._admin_policy_with_crowns(connection, updated)

    def update_operating_policy(self, authorization: str | None, updates: dict[str, Any]) -> dict[str, Any]:
        self._verify_admin_token(authorization)
        allowed = {
            "episode_reward_gems", "episodes_per_rewarded_unlock", "episode_reward_boost_enabled",
            "random_chest_min_gems", "random_chest_max_gems", "daily_random_chest_count", "episodes_per_random_chest",
            "interstitial_cooldown_seconds", "interstitial_rewarded_skip_threshold",
            "reward_claim_interstitial_enabled", "mid_episode_interstitial_enabled", "mid_episode_interstitial_trigger_seconds",
            "mid_episode_interstitial_hourly_limit", "seek_interstitial_enabled", "seek_interstitial_hourly_limit",
            "next_episode_interstitial_enabled", "speed_interstitial_enabled", "speed_interstitial_hourly_limit",
            "action_interstitial_reward_threshold_gems", "review_reward_gems", "rv_daily_limit", "episode_interstitial_limit",
            "drama_switch_interstitial_enabled", "drama_switch_interstitial_every_count", "drama_switch_interstitial_hourly_limit",
            "rewarded_fallback_interstitial_enabled", "gems_earned_cap", "gem_damping_tier1_threshold",
            "gem_damping_tier2_threshold", "gem_damping_tier3_threshold", "gem_damping_tier4_threshold", "gem_damping_tier1_multiplier",
            "gem_damping_tier2_multiplier", "gem_damping_tier3_multiplier", "gem_damping_tier4_multiplier", "gem_damping_tier5_multiplier",
            "crown_rewarded_ads_per_episode", "crown_rewarded_daily_limit", "release_boost_percent", "release_boost_daily_limit",
            "interstitial_global_hourly_limit",
        }
        changed = {key: value for key, value in updates.items() if key in allowed and value is not None}
        if not changed:
            raise ServiceError(1903, "未提供可更新的运营规则", 422)
        with self._transaction() as connection:
            policy = self._policy(connection)
            chest_min = int(changed.get("random_chest_min_gems", policy["random_chest_min_gems"]))
            chest_max = int(changed.get("random_chest_max_gems", policy["random_chest_max_gems"]))
            if chest_min > chest_max:
                raise ServiceError(1904, "随机宝箱最小宝石不能大于最大宝石", 422)
            damping_tier1 = int(changed.get("gem_damping_tier1_threshold", policy.get("gem_damping_tier1_threshold", 0)))
            damping_tier2 = int(changed.get("gem_damping_tier2_threshold", policy.get("gem_damping_tier2_threshold", 0)))
            damping_tier3 = int(changed.get("gem_damping_tier3_threshold", policy.get("gem_damping_tier3_threshold", 0)))
            damping_tier4 = int(changed.get("gem_damping_tier4_threshold", policy.get("gem_damping_tier4_threshold", 0)))
            gems_cap = int(changed.get("gems_earned_cap", policy.get("gems_earned_cap", 0)))
            if any(next_tier > 0 and current > next_tier for current, next_tier in ((damping_tier1, damping_tier2), (damping_tier2, damping_tier3), (damping_tier3, damping_tier4))):
                raise ServiceError(1906, "五档宝石阻尼阈值必须按从小到大排序", 422)
            if gems_cap > 0 and any(tier > gems_cap for tier in (damping_tier1, damping_tier2, damping_tier3, damping_tier4)):
                raise ServiceError(1907, "宝石阻尼阈值不能大于累计宝石上限", 422)
            assignments = ", ".join(f"{key} = %s" for key in changed)
            values = tuple(changed.values()) + (1,)
            self._execute(connection, f"UPDATE reward_policy SET {assignments}, updated_at = NOW() WHERE id = %s", values)
            for key, new_value in changed.items():
                self._execute(
                    connection,
                    "INSERT INTO reward_policy_admin_audit (actor, field_name, old_value, new_value) VALUES (%s, %s, %s, %s)",
                    ("dashboard", key, str(policy[key]), str(new_value)),
                )
            return self._admin_policy_with_crowns(connection, self._policy(connection))

    def update_market_wallet_policy(self, authorization: str | None, payload: dict[str, Any]) -> dict[str, Any]:
        self._verify_admin_token(authorization)
        market_code = payload["market_code"].strip().upper()
        currency_code = payload["currency_code"].strip().upper()
        if market_code == "DEFAULT" and not payload.get("enabled", True):
            raise ServiceError(1910, "DEFAULT 区域不能停用", 422)
        with self._transaction() as connection:
            previous = self._one(connection, "SELECT * FROM market_wallet_policy WHERE market_code = %s", (market_code,))
            old_value = str({key: str(value) for key, value in (previous or {}).items()})
            enabled = bool(payload.get("enabled", True))
            self._execute(
                connection,
                """
                INSERT INTO market_wallet_policy
                (market_code, currency_code, currency_symbol, currency_fraction_digits,
                 currency_micros_per_10000_gems, usd_micros_per_10000_gems,
                 withdrawal_threshold_micros, welcome_bonus_gems, daily_release_micros,
                 enabled, disabled_at)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
                        CASE WHEN %s THEN NULL ELSE NOW() END)
                ON CONFLICT (market_code) DO UPDATE SET
                    currency_code = EXCLUDED.currency_code,
                    currency_symbol = EXCLUDED.currency_symbol,
                    currency_fraction_digits = EXCLUDED.currency_fraction_digits,
                    currency_micros_per_10000_gems = EXCLUDED.currency_micros_per_10000_gems,
                    usd_micros_per_10000_gems = EXCLUDED.usd_micros_per_10000_gems,
                    withdrawal_threshold_micros = EXCLUDED.withdrawal_threshold_micros,
                    welcome_bonus_gems = EXCLUDED.welcome_bonus_gems,
                    daily_release_micros = EXCLUDED.daily_release_micros,
                    enabled = EXCLUDED.enabled,
                    disabled_at = EXCLUDED.disabled_at,
                    updated_at = NOW()
                """,
                (
                    market_code, currency_code, payload["currency_symbol"], payload["currency_fraction_digits"],
                    payload["currency_micros_per_10000_gems"], payload["usd_micros_per_10000_gems"],
                    payload["withdrawal_threshold_micros"], payload["welcome_bonus_gems"],
                    payload["daily_release_micros"], enabled, enabled,
                ),
            )
            self._execute(
                connection,
                "INSERT INTO reward_policy_admin_audit (actor, field_name, old_value, new_value) VALUES (%s, %s, %s, %s)",
                ("dashboard", f"market_wallet.{market_code}", old_value, self._json(payload)),
            )
            return {"market_wallet_policies": self._market_wallet_policies(connection)}

    def update_crown_multiplier(self, authorization: str | None, code: str, multiplier: float) -> dict[str, Any]:
        self._verify_admin_token(authorization)
        with self._transaction() as connection:
            row = self._one(connection, "SELECT episode_reward_multiplier FROM achievement_policy WHERE code = %s FOR UPDATE", (code,))
            if not row:
                raise ServiceError(1905, "皇冠等级不存在", 404)
            old_value = str(row["episode_reward_multiplier"])
            self._execute(
                connection,
                "UPDATE achievement_policy SET episode_reward_multiplier = %s WHERE code = %s",
                (f"{multiplier:.2f}", code),
            )
            self._execute(
                connection,
                "INSERT INTO reward_policy_admin_audit (actor, field_name, old_value, new_value) VALUES (%s, %s, %s, %s)",
                ("dashboard", f"crown.{code}.episode_reward_multiplier", old_value, f"{multiplier:.2f}"),
            )
            return self._admin_policy_with_crowns(connection, self._policy(connection))

    def exchange_gems(self, user_id: str, gems: int, reference_id: str) -> dict[str, Any]:
        raise ServiceError(1460, "当前版本采用直接现金钱包，奖励无需兑换", 409)

    def _exchange_gems_legacy(self, user_id: str, gems: int, reference_id: str) -> dict[str, Any]:
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            self._apply_wallet_release_on_active_day(connection, user_id)
            policy = self._policy(connection)
            if gems < int(policy["minimum_exchange_gems"]):
                raise ServiceError(1700, f"单次兑换至少需要 {policy['minimum_exchange_gems']} 宝石", 422)
            user = self._one(
                connection,
                "SELECT gems_balance, cash_wallet_micros FROM users WHERE user_id = %s FOR UPDATE",
                (user_id,),
            )
            if int(user["gems_balance"]) < gems:
                raise ServiceError(1701, "宝石余额不足，无法兑换", 409)
            wallet_policy = self._wallet_policy(connection, user_id)
            micros = (gems * int(wallet_policy["currency_micros_per_10000_gems"])) // 10_000
            if micros <= 0:
                raise ServiceError(1702, "当前兑换金额过小，请增加兑换宝石数量", 422)
            existing = self._one(
                connection,
                "SELECT id FROM ledger_entries WHERE reference_type = 'gems_exchange' AND reference_id = %s",
                (reference_id,),
            )
            if existing:
                return {"idempotent": True, "result": {"gems_spent": 0, "cash_added": "0.00"}, "snapshot": self._snapshot(connection, user_id)}
            self._execute(
                connection,
                """
                UPDATE users SET gems_balance = gems_balance - %s, cash_wallet_micros = cash_wallet_micros + %s,
                    updated_at = NOW() WHERE user_id = %s
                """,
                (gems, micros, user_id),
            )
            self._execute(
                connection,
                """
                INSERT INTO ledger_entries
                (user_id, entry_type, gems_delta, cash_wallet_delta_micros, reference_type, reference_id)
                VALUES (%s, 'GEMS_EXCHANGE', %s, %s, 'gems_exchange', %s)
                """,
                (user_id, -gems, micros, reference_id),
            )
            return {
                "idempotent": False,
                "result": {
                    "action": "GEMS_EXCHANGE",
                    "gems_spent": gems,
                    "cash_added": self._money(micros, wallet_policy),
                    "cash_added_usd_reference": self._usd_reference_money(micros, wallet_policy),
                    "currency": self._currency_payload(wallet_policy),
                },
                "snapshot": self._snapshot(connection, user_id),
            }

    def record_interstitial(self, user_id: str, event_id: str, placement_id: str, episode_id: str | None = None) -> dict[str, Any]:
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            business_date = self._business_date()
            existing = self._one(connection, "SELECT event_id FROM interstitial_events WHERE event_id = %s", (event_id,))
            if existing:
                return {"idempotent": True, "snapshot": self._snapshot(connection, user_id)}
            policy = self._policy(connection)
            counters = self._daily_counter(connection, user_id, business_date, lock=True)
            now = self._now()

            cooldown_seconds = int(policy.get("interstitial_cooldown_seconds", 0))
            if counters["last_interstitial_at"] and now - counters["last_interstitial_at"] < timedelta(seconds=cooldown_seconds):
                raise ServiceError(2002, "插屏冷却期未结束", 409)

            global_hourly_limit = int(policy.get("interstitial_global_hourly_limit", 0))
            if global_hourly_limit > 0:
                global_count = int(self._one(
                    connection,
                    "SELECT COUNT(*) AS count FROM interstitial_events WHERE user_id = %s AND shown_at >= NOW() - INTERVAL '1 hour'",
                    (user_id,),
                )["count"])
                if global_count >= global_hourly_limit:
                    raise ServiceError(2021, "本小时插屏展示已达总上限", 429)

            def placement_count(placement: str) -> int:
                return int(self._one(
                    connection,
                    """SELECT COUNT(*) AS count FROM interstitial_events
                    WHERE user_id = %s AND placement_id = %s AND shown_at >= NOW() - INTERVAL '1 hour'""",
                    (user_id, placement),
                )["count"])

            if episode_id and placement_id in {"int_episode_midroll", "int_seek", "int_speed", "int_rewarded_fallback"}:
                episode_limit = int(policy["episode_interstitial_limit"])
                shown_in_episode = int(self._one(
                    connection,
                    """SELECT COUNT(*) AS count FROM interstitial_events
                    WHERE user_id = %s AND episode_id = %s AND shown_at >= NOW() - INTERVAL '24 hours'""",
                    (user_id, episode_id),
                )["count"])
                if episode_limit > 0 and shown_in_episode >= episode_limit:
                    raise ServiceError(2017, "本集插屏展示次数已达上限", 429)

            if placement_id == "int_next_episode_unlock":
                if not bool(policy["next_episode_interstitial_enabled"]):
                    raise ServiceError(2004, "下一集广告解锁当前未启用", 409)
                if placement_count(placement_id) >= int(policy["next_episode_interstitial_hourly_limit"]):
                    raise ServiceError(2005, "本小时下一集广告解锁已达上限", 429)
            elif placement_id == "int_pause_native":
                if not bool(policy["pause_ad_enabled"]):
                    raise ServiceError(2007, "暂停广告当前未启用", 409)
                if placement_count(placement_id) >= int(policy["pause_interstitial_hourly_limit"]):
                    raise ServiceError(2008, "本小时暂停广告已达上限", 429)
            elif placement_id == "int_reward_claim":
                if not bool(policy["reward_claim_interstitial_enabled"]):
                    raise ServiceError(2010, "领取宝石插屏当前未启用", 409)
            elif placement_id == "int_episode_midroll":
                if not bool(policy["mid_episode_interstitial_enabled"]):
                    raise ServiceError(2011, "剧中插屏当前未启用", 409)
                mid_limit = int(policy["mid_episode_interstitial_hourly_limit"])
                if mid_limit > 0 and placement_count(placement_id) >= mid_limit:
                    raise ServiceError(2012, "本小时剧中插屏已达上限", 429)
            elif placement_id == "int_seek":
                if not bool(policy["seek_interstitial_enabled"]):
                    raise ServiceError(2013, "拖拽插屏当前未启用", 409)
                seek_limit = int(policy["seek_interstitial_hourly_limit"])
                if seek_limit > 0 and placement_count(placement_id) >= seek_limit:
                    raise ServiceError(2014, "本小时拖拽插屏已达上限", 429)
            elif placement_id == "int_speed":
                if not bool(policy["speed_interstitial_enabled"]):
                    raise ServiceError(2015, "倍速插屏当前未启用", 409)
                speed_limit = int(policy["speed_interstitial_hourly_limit"])
                if speed_limit > 0 and placement_count(placement_id) >= speed_limit:
                    raise ServiceError(2016, "本小时倍速插屏已达上限", 429)
            elif placement_id == "int_drama_switch":
                if not bool(policy["drama_switch_interstitial_enabled"]):
                    raise ServiceError(2018, "切换剧集插屏当前未启用", 409)
                switch_limit = int(policy["drama_switch_interstitial_hourly_limit"])
                if switch_limit > 0 and placement_count(placement_id) >= switch_limit:
                    raise ServiceError(2019, "本小时切换剧集插屏已达上限", 429)
            elif placement_id == "int_rewarded_fallback":
                if not bool(policy["rewarded_fallback_interstitial_enabled"]):
                    raise ServiceError(2020, "激励回退插屏当前未启用", 409)
                threshold = int(policy.get("interstitial_rewarded_skip_threshold", 0))
                if threshold > 0 and int(counters.get("rewarded_decline_count", 0)) < threshold:
                    raise ServiceError(2022, "尚未达到激励广告跳过触发阈值", 409)
            else:
                # 兼容旧版 int_timer / int_pause 事件。
                if placement_count(placement_id) >= int(policy["interstitial_hourly_limit"]):
                    raise ServiceError(2003, "本小时插屏展示已达上限", 429)

            self._execute(
                connection,
                "INSERT INTO interstitial_events (event_id, user_id, business_date, placement_id, episode_id) VALUES (%s, %s, %s, %s, %s)",
                (event_id, user_id, business_date, placement_id, episode_id),
            )
            if placement_id == "int_pause_native":
                self._execute(
                    connection,
                    "UPDATE daily_counters SET pause_interstitial_count = pause_interstitial_count + 1 WHERE user_id = %s AND business_date = %s",
                    (user_id, business_date),
                )
            else:
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
            wallet_policy = self._wallet_policy(connection, user_id)
            enabled = bool(policy["withdrawal_enabled"])
            threshold = int(wallet_policy["withdrawal_threshold_micros"])
            available = int(wallet["cash_wallet_micros"])
            if not enabled:
                reason = "提现功能尚未启用；当前仅展示已累计的现金钱包奖励。"
            elif available < threshold:
                reason = f"可提现余额未达到最低门槛 {self._money_label(threshold, wallet_policy)}。"
            else:
                reason = "已满足余额条件；仍需通过正式 KYC 与支付服务校验。"
            return {
                "withdrawal_enabled": enabled,
                "eligible": enabled and available >= threshold,
                "reason": reason,
                "cash_wallet": self._money(int(wallet["cash_wallet_micros"]), wallet_policy),
                "available_balance": self._money(available, wallet_policy),
                "threshold": self._money(threshold, wallet_policy),
                "currency": self._currency_payload(wallet_policy),
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
        if placement_id == "rv_welcome_bonus":
            welcome = self._one(
                connection,
                "SELECT gems, state FROM welcome_rewards WHERE user_id = %s FOR UPDATE",
                (user_id,),
            )
            if not welcome or welcome["state"] != "PENDING":
                raise ServiceError(1602, "欢迎奖励已领取或暂不可用", 409)
            return {"action": "WELCOME_BONUS", "gems_added": int(welcome["gems"])}

        if placement_id == "rv_checkin_complete":
            # 锁定用户行，避免并发广告回调重复计算同一业务日的连续签到天数。
            self._one(connection, "SELECT user_id FROM users WHERE user_id = %s FOR UPDATE", (user_id,))
            existing = self._one(
                connection,
                "SELECT streak_day FROM checkins WHERE user_id = %s AND business_date = %s",
                (user_id, business_date),
            )
            if existing:
                raise ServiceError(1405, "今天已经签到", 409)
            previous = self._one(
                connection,
                """
                SELECT business_date, streak_day FROM checkins
                WHERE user_id = %s ORDER BY business_date DESC LIMIT 1 FOR UPDATE
                """,
                (user_id,),
            )
            streak_day = previous["streak_day"] % 30 + 1 if previous and previous["business_date"] == business_date - timedelta(days=1) else 1
            reward = self._one(connection, "SELECT gems FROM signin_reward_tiers WHERE day_number = %s", (streak_day,))
            if not reward:
                raise ServiceError(1500, "数据库缺少签到奖励种子数据", 503)
            return {"action": "SIGN_IN", "streak_day": int(streak_day), "gems_added": int(reward["gems"])}

        if placement_id == "rv_episode_rewards_claim":
            pending = self._one(
                connection,
                "SELECT 1 AS available FROM episode_reward_claims WHERE user_id = %s AND state = 'PENDING' LIMIT 1",
                (user_id,),
            )
            if not pending:
                raise ServiceError(1217, "当前没有待领取的追剧宝石", 409)
            return {"action": "EPISODE_REWARDS_CLAIM_AUTH", "gems_added": 0}

        if placement_id == "rv_crown_upgrade":
            return {"action": "CROWN_UPGRADE", "gems_added": 0}

        if placement_id == "rv_episode_pass":
            if not target_id or episode_index is None:
                raise ServiceError(1101, "集间通行广告缺少 episode_id 或 episode_index", 422)
            if episode_index <= policy["free_episodes_count"]:
                raise ServiceError(1100, f"前 {policy['free_episodes_count']} 集为免费体验，无需激励广告", 409)
            return {
                "action": "EPISODE_PASS",
                "episode_id": target_id,
                "gems_added": 0,
                "unlocked": True,
                "unlock_episode_count": int(policy.get("episodes_per_rewarded_unlock", 1)),
            }

        if placement_id == "rv_claim_reward":
            claim = self._one(
                connection,
                """
                SELECT id, base_reward_gems, cycle_number FROM pending_claims
                WHERE user_id = %s AND claim_kind = 'TEN_ROUND' AND state = 'PENDING'
                ORDER BY cycle_number ASC LIMIT 1 FOR UPDATE
                """,
                (user_id,),
            )
            if not claim:
                raise ServiceError(1201, "暂无可领取的十轮奖励", 409)
            return {"action": "CLAIM_REWARD", "claim_id": claim["id"], "cycle_number": claim["cycle_number"], "gems_added": claim["base_reward_gems"], "can_double": True}

        if placement_id == "rv_double_reward":
            if target_id:
                try:
                    cycle_number = int(target_id)
                except ValueError as error:
                    raise ServiceError(1203, "十集奖励翻倍缺少有效轮次", 422) from error
                claim = self._one(
                    connection,
                    """
                    SELECT id, base_reward_gems, cycle_number FROM pending_claims
                    WHERE user_id = %s AND cycle_number = %s AND claim_kind = 'TEN_ROUND' AND state = 'CLAIMED'
                    FOR UPDATE
                    """,
                    (user_id, cycle_number),
                )
            else:
                # 兼容旧 Android 客户端：未传轮次时仅翻倍最新一笔已领取的十集加奖。
                claim = self._one(
                    connection,
                    """
                    SELECT id, base_reward_gems, cycle_number FROM pending_claims
                    WHERE user_id = %s AND claim_kind = 'TEN_ROUND' AND state = 'CLAIMED'
                    ORDER BY cycle_number DESC LIMIT 1 FOR UPDATE
                    """,
                    (user_id,),
                )
            if not claim:
                raise ServiceError(1202, "请先领取该笔十集基础奖励后再翻倍", 409)
            return {"action": "DOUBLE_REWARD", "claim_id": claim["id"], "cycle_number": claim["cycle_number"], "gems_added": claim["base_reward_gems"], "can_double": False}

        if placement_id == "rv_episode_reward_boost":
            if not target_id:
                raise ServiceError(1213, "本集翻倍缺少 episode_id", 422)
            if not bool(policy.get("episode_reward_boost_enabled", True)):
                raise ServiceError(1214, "当前奖励规则未启用单集广告翻倍", 409)
            # 翻倍是分集级预授权：可在播放中、完播奖励尚未生成前观看广告。
            # 完播后创建待领取奖励时读取此记录，单集最多仅按 2 倍结算一次。
            boosted = self._one(
                connection,
                "SELECT 1 AS exists FROM episode_reward_boosts WHERE user_id = %s AND episode_id = %s",
                (user_id, target_id),
            )
            if boosted:
                raise ServiceError(1215, "本集奖励已开启翻倍，完成观看后将自动按双倍结算", 409)
            return {"action": "EPISODE_REWARD_BOOST", "episode_id": target_id, "gems_added": 0}

        if placement_id == "rv_random_chest_open":
            chest = self._one(
                connection,
                """
                SELECT id FROM random_chest_inventory
                WHERE user_id = %s AND state = 'AVAILABLE'
                ORDER BY created_at, id LIMIT 1 FOR UPDATE
                """,
                (user_id,),
            )
            if not chest:
                raise ServiceError(1303, "当前没有可开启的随机宝箱", 409)
            minimum = int(policy["random_chest_min_gems"])
            maximum = int(policy["random_chest_max_gems"])
            gems = minimum + secrets.randbelow(maximum - minimum + 1)
            return {"action": "RANDOM_CHEST_OPEN", "chest_id": int(chest["id"]), "gems_added": gems}

        if placement_id == "rv_episode_reward_double":
            if not target_id:
                raise ServiceError(1211, "单集奖励翻倍缺少 episode_id", 422)
            claim = self._one(
                connection,
                """
                SELECT id, base_reward_gems FROM episode_reward_claims
                WHERE user_id = %s AND episode_id = %s AND state = 'CLAIMED' FOR UPDATE
                """,
                (user_id, target_id),
            )
            if not claim:
                raise ServiceError(1212, "请先领取该集基础宝石后再观看广告翻倍", 409)
            return {"action": "EPISODE_REWARD_DOUBLE", "claim_id": claim["id"], "episode_id": target_id, "gems_added": int(claim["base_reward_gems"])}

        if placement_id == "rv_chest_task":
            # 兼容旧客户端：旧每日宝箱入口与新随机宝箱采用相同库存和结算方式。
            chest = self._one(
                connection,
                """
                SELECT id FROM random_chest_inventory
                WHERE user_id = %s AND state = 'AVAILABLE'
                ORDER BY created_at, id LIMIT 1 FOR UPDATE
                """,
                (user_id,),
            )
            if not chest:
                raise ServiceError(1303, "当前没有可开启的随机宝箱", 409)
            minimum = int(policy["random_chest_min_gems"])
            maximum = int(policy["random_chest_max_gems"])
            gems = minimum + secrets.randbelow(maximum - minimum + 1)
            return {"action": "RANDOM_CHEST_OPEN", "chest_id": int(chest["id"]), "gems_added": gems}

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

        if placement_id == "rv_release_boost":
            counters = self._daily_counter(connection, user_id, business_date, lock=True)
            release_boost_limit = int(policy["release_boost_daily_limit"])
            if release_boost_limit > 0 and int(counters["release_boost_count"]) >= release_boost_limit:
                raise ServiceError(1451, "今日广告加速次数已达上限", 429)
            # Single-wallet mode: an ad boost is transparent extra cash, not a transfer
            # between hidden balance layers. Its amount remains policy-configurable.
            reward_units = max(0, math.floor(int(policy["episode_reward_gems"]) * float(policy["release_boost_percent"])))
            return {
                "action": "WALLET_CASH_BOOST",
                "gems_added": reward_units,
                "released_micros": 0,
                "release_boost_percent": float(policy["release_boost_percent"]),
            }

        raise ServiceError(1099, "不支持的激励广告位", 422)

    def _link_rewarded_event(
        self,
        connection: psycopg.Connection[Any],
        user_id: str,
        business_date: date,
        placement_id: str,
        target_id: str | None,
        ad_event_id: str,
    ) -> int | None:
        policy = self._policy(connection)
        event = self._one(connection, "SELECT result_payload FROM rewarded_ad_events WHERE ad_event_id = %s", (ad_event_id,))
        payload = event["result_payload"]
        action = payload["action"]
        if action == "EPISODE_PASS":
            # 广告只授予本集的内容通行权益；观看完成后才会形成独立的待领取宝石。
            return
        elif action == "WELCOME_BONUS":
            updated = self._execute(
                connection,
                "UPDATE welcome_rewards SET state = 'CLAIMED', claimed_at = NOW() WHERE user_id = %s AND state = 'PENDING'",
                (user_id,),
            )
            if updated != 1:
                raise ServiceError(1603, "欢迎奖励状态已变化，请刷新后重试", 409)
            self._execute(connection, "UPDATE users SET welcome_claimed_at = NOW() WHERE user_id = %s", (user_id,))
            return self._credit_gems(connection, user_id, int(payload["gems_added"]), policy, "WELCOME_BONUS", "welcome_bonus_ad", ad_event_id)
        elif action == "SIGN_IN":
            inserted = self._execute(
                connection,
                """
                INSERT INTO checkins (user_id, business_date, streak_day, base_gems)
                VALUES (%s, %s, %s, %s)
                ON CONFLICT (user_id, business_date) DO NOTHING
                """,
                (user_id, business_date, int(payload["streak_day"]), int(payload["gems_added"])),
            )
            if inserted != 1:
                raise ServiceError(1406, "今天已经签到", 409)
            credited = self._credit_gems(connection, user_id, int(payload["gems_added"]), policy, "SIGN_IN", "checkin_ad", ad_event_id)
            self._ensure_random_chests(connection, user_id, business_date, policy)
            return credited
        elif action == "EPISODE_REWARDS_CLAIM_AUTH":
            self._execute(
                connection,
                "INSERT INTO episode_reward_claim_authorizations (ad_event_id, user_id) VALUES (%s, %s)",
                (ad_event_id, user_id),
            )
        elif action == "CROWN_UPGRADE":
            # 奖励事件本身已写入表；_award_achievements_if_due 会将每 2 条广告按 1 集等效追剧计算。
            return
        elif action == "CLAIM_REWARD":
            self._execute(
                connection,
                "UPDATE pending_claims SET state = 'CLAIMED', claim_ad_event_id = %s, claimed_at = NOW() WHERE id = %s",
                (ad_event_id, payload["claim_id"]),
            )
            return self._credit_gems(connection, user_id, payload["gems_added"], policy, "CLAIM_REWARD", "claim_ad", ad_event_id)
        elif action == "DOUBLE_REWARD":
            self._execute(
                connection,
                "UPDATE pending_claims SET state = 'DOUBLED', double_ad_event_id = %s, doubled_at = NOW() WHERE id = %s",
                (ad_event_id, payload["claim_id"]),
            )
            return self._credit_gems(connection, user_id, payload["gems_added"], policy, "DOUBLE_REWARD", "double_ad", ad_event_id)
        elif action == "EPISODE_REWARD_BOOST":
            self._execute(
                connection,
                """
                INSERT INTO episode_reward_boosts (user_id, episode_id, ad_event_id)
                VALUES (%s, %s, %s)
                ON CONFLICT (user_id, episode_id) DO NOTHING
                """,
                (user_id, payload["episode_id"], ad_event_id),
            )
        elif action == "EPISODE_REWARD_DOUBLE":
            self._execute(
                connection,
                "UPDATE episode_reward_claims SET state = 'DOUBLED', double_ad_event_id = %s, doubled_at = NOW() WHERE id = %s",
                (ad_event_id, payload["claim_id"]),
            )
            return self._credit_gems(connection, user_id, payload["gems_added"], policy, "EPISODE_REWARD_DOUBLE", "episode_reward_double", ad_event_id)
        elif action == "RANDOM_CHEST_OPEN":
            self._execute(
                connection,
                """
                UPDATE random_chest_inventory
                SET state = 'OPENED', gems = %s, opened_ad_event_id = %s, opened_at = NOW()
                WHERE id = %s AND state = 'AVAILABLE'
                """,
                (payload["gems_added"], ad_event_id, payload["chest_id"]),
            )
            return self._credit_gems(connection, user_id, payload["gems_added"], policy, "RANDOM_CHEST_OPEN", "random_chest", str(payload["chest_id"]))
        elif action == "SIGNIN_DOUBLE":
            self._execute(
                connection,
                "UPDATE checkins SET double_gems = %s WHERE user_id = %s AND business_date = %s",
                (payload["gems_added"], user_id, business_date),
            )
            return self._credit_gems(connection, user_id, payload["gems_added"], policy, "SIGNIN_DOUBLE", "signin_double_ad", ad_event_id)
        elif action == "WALLET_CASH_BOOST":
            credited = self._credit_gems(
                connection, user_id, int(payload["gems_added"]), policy,
                "WALLET_CASH_BOOST", "wallet_cash_boost", ad_event_id,
            )
            self._execute(
                connection,
                """
                UPDATE daily_counters SET release_boost_count = release_boost_count + 1
                WHERE user_id = %s AND business_date = %s
                """,
                (user_id, business_date),
            )
            return credited
        return None

    def _create_episode_reward_claim(
        self,
        connection: psycopg.Connection[Any],
        user_id: str,
        business_date: date,
        episode_id: str,
        policy: dict[str, Any],
    ) -> dict[str, Any] | None:
        multiplier = self._current_episode_multiplier(connection, user_id)
        reward = max(0, math.floor(int(policy["episode_reward_gems"]) * multiplier))
        self._execute(
            connection,
            """
            INSERT INTO episode_reward_claims (user_id, business_date, episode_id, base_reward_gems)
            VALUES (%s, %s, %s, %s)
            ON CONFLICT (user_id, episode_id) DO NOTHING
            """,
            (user_id, business_date, episode_id, reward),
        )
        claim = self._one(
            connection,
            "SELECT state, base_reward_gems FROM episode_reward_claims WHERE user_id = %s AND episode_id = %s",
            (user_id, episode_id),
        )
        boosted = self._one(
            connection,
            "SELECT 1 AS enabled FROM episode_reward_boosts WHERE user_id = %s AND episode_id = %s",
            (user_id, episode_id),
        ) is not None
        return (
            {
                "episode_id": episode_id,
                "gems": int(claim["base_reward_gems"]) * (2 if boosted else 1),
                "base_gems": int(claim["base_reward_gems"]),
                "multiplier": multiplier,
                "boosted": boosted,
                "state": claim["state"],
            }
            if claim and claim["state"] == "PENDING" else None
        )

    def _current_episode_multiplier(self, connection: psycopg.Connection[Any], user_id: str) -> float:
        row = self._one(
            connection,
            """
            SELECT COALESCE(MAX(p.episode_reward_multiplier), 1.00) AS multiplier
            FROM user_achievements a
            JOIN achievement_policy p ON p.code = a.code
            WHERE a.user_id = %s
            """,
            (user_id,),
        )
        return float(row["multiplier"] if row else 1.0)

    def _ensure_random_chests(
        self,
        connection: psycopg.Connection[Any],
        user_id: str,
        business_date: date,
        policy: dict[str, Any],
        lifetime_episode_count: int | None = None,
    ) -> list[dict[str, Any]]:
        created: list[dict[str, Any]] = []
        created_count = 0
        for index in range(int(policy["daily_random_chest_count"])):
            source_key = f"{business_date.isoformat()}:{index + 1}"
            created_count += self._execute(
                connection,
                """
                INSERT INTO random_chest_inventory (user_id, source_kind, source_key, business_date)
                VALUES (%s, 'DAILY', %s, %s)
                ON CONFLICT (user_id, source_kind, source_key) DO NOTHING
                """,
                (user_id, source_key, business_date),
            )
        if lifetime_episode_count is None:
            lifetime_episode_count = int(self._one(
                connection, "SELECT COUNT(*) AS count FROM watch_events WHERE user_id = %s", (user_id,)
            )["count"])
        stride = int(policy["episodes_per_random_chest"])
        chest_count = lifetime_episode_count // stride
        for cycle in range(1, chest_count + 1):
            source_key = str(cycle)
            created_count += self._execute(
                connection,
                """
                INSERT INTO random_chest_inventory (user_id, source_kind, source_key, business_date)
                VALUES (%s, 'WATCH', %s, %s)
                ON CONFLICT (user_id, source_kind, source_key) DO NOTHING
                """,
                (user_id, source_key, business_date),
            )
        available = int(self._one(
            connection,
            "SELECT COUNT(*) AS count FROM random_chest_inventory WHERE user_id = %s AND state = 'AVAILABLE'",
            (user_id,),
        )["count"])
        if created_count:
            created.append({"created_count": created_count, "available_count": available})
        return created

    def _ensure_episode_task_claims(
        self,
        connection: psycopg.Connection[Any],
        user_id: str,
        lifetime_episode_count: int,
    ) -> None:
        """Materialise milestones that have become claimable; repeat calls are safe."""
        self._execute(
            connection,
            """
            INSERT INTO episode_task_claims (user_id, milestone_episode_count)
            SELECT %s, milestone_episode_count
            FROM episode_task_policy
            WHERE milestone_episode_count <= %s
            ON CONFLICT (user_id, milestone_episode_count) DO NOTHING
            """,
            (user_id, lifetime_episode_count),
        )

    def _episode_task_progress(
        self,
        connection: psycopg.Connection[Any],
        user_id: str,
        lifetime_episode_count: int,
        wallet_policy: dict[str, Any],
    ) -> list[dict[str, Any]]:
        self._ensure_episode_task_claims(connection, user_id, lifetime_episode_count)
        rows = self._all(
            connection,
            """
            SELECT p.milestone_episode_count, p.reward_gems, p.sort_order,
                   COALESCE(c.state, CASE WHEN p.milestone_episode_count <= %s THEN 'PENDING' ELSE 'LOCKED' END) AS state
            FROM episode_task_policy p
            LEFT JOIN episode_task_claims c
                   ON c.user_id = %s AND c.milestone_episode_count = p.milestone_episode_count
            ORDER BY p.sort_order
            """,
            (lifetime_episode_count, user_id),
        )
        return [
            {
                "milestone_episode_count": int(row["milestone_episode_count"]),
                "reward_gems": int(row["reward_gems"]),
                "reward_cash": self._money(self._reward_units_to_micros(int(row["reward_gems"]), wallet_policy), wallet_policy),
                "state": row["state"],
                "progress": min(lifetime_episode_count, int(row["milestone_episode_count"])),
                "target": int(row["milestone_episode_count"]),
            }
            for row in rows
        ]

    def _create_ten_round_claim_if_due(
        self,
        connection: psycopg.Connection[Any],
        user_id: str,
        business_date: date,
        policy: dict[str, Any],
        lifetime_episode_count: int,
    ) -> dict[str, Any] | None:
        round_size = int(policy["ten_round_size"])
        if lifetime_episode_count <= 0 or lifetime_episode_count % round_size != 0:
            return None
        cycle_number = lifetime_episode_count // round_size
        reward = self._round_reward(policy, cycle_number)
        self._execute(
            connection,
            """
            INSERT INTO pending_claims
            (user_id, business_date, cycle_number, round_from, round_to, base_reward_gems, claim_kind)
            VALUES (%s, %s, %s, %s, %s, %s, 'TEN_ROUND')
            ON CONFLICT (user_id, cycle_number) WHERE claim_kind = 'TEN_ROUND' DO NOTHING
            """,
            (user_id, business_date, cycle_number, lifetime_episode_count - round_size + 1, lifetime_episode_count, reward),
        )
        return {"cycle_number": cycle_number, "gems": reward, "round_size": round_size}

    def _award_achievements_if_due(self, connection: psycopg.Connection[Any], user_id: str, policy: dict[str, Any]) -> list[dict[str, Any]]:
        awarded: list[dict[str, Any]] = []
        policies = self._all(connection, "SELECT * FROM achievement_policy ORDER BY level_rank ASC, code ASC")
        now = self._now()
        for item in policies:
            exists = self._one(connection, "SELECT 1 AS earned FROM user_achievements WHERE user_id = %s AND code = %s", (user_id, item["code"]))
            if exists:
                continue
            start = now - timedelta(hours=item["window_hours"])
            watched_rounds = int(self._one(
                connection,
                "SELECT COUNT(*) AS count FROM watch_events WHERE user_id = %s AND recorded_at >= %s",
                (user_id, start),
            )["count"])
            # Only ads explicitly started from Crown Road advance Crown Road.
            # Unlock, check-in, chest, wallet, review and other rewarded ads are excluded.
            rewarded_rounds = int(self._one(
                connection,
                "SELECT COUNT(*) AS count FROM rewarded_ad_events WHERE user_id = %s AND placement_id = 'rv_crown_upgrade' AND processed_at >= %s",
                (user_id, start),
            )["count"])
            equivalent_rounds = rewarded_rounds // max(1, int(policy["crown_rewarded_ads_per_episode"]))
            rounds = watched_rounds + equivalent_rounds
            if rounds < item["required_rounds"]:
                continue
            rvs = rewarded_rounds
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
        wallet_policy = self._wallet_policy(connection, user_id)
        counters = self._daily_counter(connection, user_id, business_date)
        lifecycle_day = self._lifecycle_day(user["created_at"])
        lifetime_episode_count = int(self._one(
            connection, "SELECT COUNT(*) AS count FROM watch_events WHERE user_id = %s", (user_id,)
        )["count"])
        checkin = self._one(connection, "SELECT streak_day, base_gems, double_gems FROM checkins WHERE user_id = %s AND business_date = %s", (user_id, business_date))
        previous = self._one(
            connection,
            "SELECT business_date, streak_day FROM checkins WHERE user_id = %s AND business_date < %s ORDER BY business_date DESC LIMIT 1",
            (user_id, business_date),
        )
        completed_streak = int(checkin["streak_day"]) if checkin else (
            int(previous["streak_day"]) if previous and previous["business_date"] == business_date - timedelta(days=1) else 0
        )
        today_streak = completed_streak if checkin else (completed_streak % 30 + 1)
        today_reward = self._one(connection, "SELECT gems FROM signin_reward_tiers WHERE day_number = %s", (today_streak,))["gems"]
        tiers = self._all(connection, "SELECT day_number, gems FROM signin_reward_tiers ORDER BY day_number")
        episode_reward_claims = self._all(
            connection,
            """
            SELECT c.episode_id, c.base_reward_gems, c.state,
                   EXISTS(
                       SELECT 1 FROM episode_reward_boosts b
                       WHERE b.user_id = c.user_id AND b.episode_id = c.episode_id
                   ) AS boosted
            FROM episode_reward_claims c
            WHERE c.user_id = %s
            ORDER BY c.id
            """,
            (user_id,),
        )
        preboosted_episode_rows = self._all(
            connection,
            "SELECT episode_id FROM episode_reward_boosts WHERE user_id = %s ORDER BY boosted_at DESC",
            (user_id,),
        )
        chest_rows = self._all(
            connection,
            """
            SELECT source_kind, COUNT(*) AS count
            FROM random_chest_inventory
            WHERE user_id = %s AND state = 'AVAILABLE'
            GROUP BY source_kind
            """,
            (user_id,),
        )
        chest_counts = {item["source_kind"]: int(item["count"]) for item in chest_rows}
        achievements = self._all(connection, "SELECT code, gems, virtual_entitlement, earned_at FROM user_achievements WHERE user_id = %s ORDER BY earned_at", (user_id,))
        welcome = self._one(connection, "SELECT gems, state FROM welcome_rewards WHERE user_id = %s", (user_id,))
        review = self._one(connection, "SELECT gems, claimed_at FROM app_review_rewards WHERE user_id = %s", (user_id,))
        recent_ledger = self._all(
            connection,
            """
            SELECT entry_type, gems_delta, cash_wallet_delta_micros, available_balance_delta_micros, created_at
            FROM ledger_entries WHERE user_id = %s ORDER BY id DESC LIMIT 20
            """,
            (user_id,),
        )
        for item in episode_reward_claims:
            units = int(item["base_reward_gems"]) * (2 if item["boosted"] else 1)
            item["cash"] = self._money(self._reward_units_to_micros(units, wallet_policy), wallet_policy)
        for item in achievements:
            item["cash"] = self._money(self._reward_units_to_micros(int(item["gems"]), wallet_policy), wallet_policy)
        pending_episode_rewards = [item for item in episode_reward_claims if item["state"] == "PENDING"]
        pending_reward_gems = sum(int(item["base_reward_gems"]) * (2 if item["boosted"] else 1) for item in pending_episode_rewards)
        episodes_per_chest = int(policy["episodes_per_random_chest"])
        in_chest_cycle = lifetime_episode_count % episodes_per_chest
        release_cap = int(wallet_policy["daily_release_micros"])
        cash_wallet_micros = int(user["cash_wallet_micros"])
        expected_boost_units = max(0, math.floor(int(policy["episode_reward_gems"]) * float(policy["release_boost_percent"])))
        expected_boost_transfer = self._reward_units_to_micros(expected_boost_units, wallet_policy)
        currency = self._currency_payload(wallet_policy)
        task_rows = self._episode_task_progress(connection, user_id, lifetime_episode_count, wallet_policy)
        return {
            "business_date": business_date.isoformat(),
            "user": {"geo_tier": user["geo_tier"], "market_code": wallet_policy["market_code"], "lifecycle_day": lifecycle_day},
            "wallet": {
                "gems_balance": 0,
                "cash_wallet": self._money(user["cash_wallet_micros"], wallet_policy),
                "wallet_locked": self._money(user["cash_wallet_micros"], wallet_policy),
                "available_balance": self._money(user["cash_wallet_micros"], wallet_policy),
                "cash_wallet_usd_reference": self._usd_reference_money(user["cash_wallet_micros"], wallet_policy),
                "available_balance_usd_reference": self._usd_reference_money(user["cash_wallet_micros"], wallet_policy),
                "currency": currency,
                "total_gems_earned": user["total_gems_earned"],
                "reward_progress_units": user["total_gems_earned"],
                "cash_mode": True,
                "exchange_rate_gems": 10000,
                "exchange_value_per_10000_gems": self._money(int(wallet_policy["currency_micros_per_10000_gems"]), wallet_policy),
                "exchange_value_per_10000_gems_usd_reference": self._usd_reference_money(int(wallet_policy["usd_micros_per_10000_gems"]), wallet_policy),
                "minimum_exchange_gems": policy["minimum_exchange_gems"],
                "release": {
                    "released_today": False,
                    "daily_release": "0",
                    "daily_release_usd_reference": self._usd_reference_money(release_cap, wallet_policy),
                    "boost_used": counters["release_boost_count"],
                    "boost_cap": policy["release_boost_daily_limit"],
                    "boost_amount": self._money(expected_boost_transfer, wallet_policy),
                    "boost_amount_usd_reference": self._usd_reference_money(expected_boost_transfer, wallet_policy),
                    "boost_percent": float(policy["release_boost_percent"]),
                    "expected_tomorrow_transfer": "0",
                    "expected_tomorrow_transfer_usd_reference": "0",
                    "expected_boost_transfer": self._money(expected_boost_transfer, wallet_policy),
                    "expected_boost_transfer_usd_reference": self._usd_reference_money(expected_boost_transfer, wallet_policy),
                    # The boost is a direct cash reward and remains available until the daily cap.
                    "boost_available": int(policy["release_boost_daily_limit"]) <= 0 or int(counters["release_boost_count"]) < int(policy["release_boost_daily_limit"]),
                    "estimated_days_remaining": 0,
                    "pending_days": 0,
                },
            },
            "today": {
                "episode_count": counters["episode_count"],
                "lifetime_episode_count": lifetime_episode_count,
                "rv_count": counters["rv_count"],
                "rv_daily_limit": policy["rv_daily_limit"],
                "completed_rounds": counters["completed_rounds"],
                "ten_round_size": 0,
                "episodes_to_next_ten_round": 0,
                "next_ten_round_preview_gems": 0,
                "episodes_per_random_chest": episodes_per_chest,
                "episodes_to_next_random_chest": 0 if in_chest_cycle == 0 else episodes_per_chest - in_chest_cycle,
                "pending_episode_reward_count": len(pending_episode_rewards),
                "rewarded_decline_count": int(counters.get("rewarded_decline_count", 0)),
            },
            "checkin": {
                "signed_today": checkin is not None,
                "completed_streak": completed_streak,
                "today_streak": today_streak,
                "today_base_gems": checkin["base_gems"] if checkin else today_reward,
                "today_double_gems": checkin["double_gems"] if checkin else 0,
                "today_cash": self._money(self._reward_units_to_micros(int(checkin["base_gems"] if checkin else today_reward), wallet_policy), wallet_policy),
                "tiers": tiers,
            },
            "welcome": {
                "available": welcome is not None and welcome["state"] == "PENDING",
                "claimed": welcome is not None and welcome["state"] == "CLAIMED",
                "gems": int(welcome["gems"]) if welcome else 0,
                "cash": self._money(self._reward_units_to_micros(int(welcome["gems"]) if welcome else 0, wallet_policy), wallet_policy),
                "cash_usd_reference": self._usd_reference_money(self._reward_units_to_micros(int(welcome["gems"]) if welcome else 0, wallet_policy), wallet_policy),
            },
            "review": {
                "available": review is None,
                "claimed": review is not None,
                "gems": int(review["gems"]) if review else int(policy["review_reward_gems"]),
                "cash": self._money(self._reward_units_to_micros(int(review["gems"]) if review else int(policy["review_reward_gems"]), wallet_policy), wallet_policy),
            },
            "ring": {
                "enabled": True,
                "progress": len(pending_episode_rewards),
                "target": max(1, len(pending_episode_rewards)),
                "claimable": bool(pending_episode_rewards),
                "reward_gems": pending_reward_gems,
                "reward_cash": self._money(self._reward_units_to_micros(pending_reward_gems, wallet_policy), wallet_policy),
                "reward_cash_usd_reference": self._usd_reference_money(self._reward_units_to_micros(pending_reward_gems, wallet_policy), wallet_policy),
                "label": "领取现金奖励" if pending_episode_rewards else "追剧赚现金",
            },
            "chest": {
                "requires_checkin": False,
                "claimable": sum(chest_counts.values()) > 0,
                "claimed": False,
                "reward_gems": 0,
                "available_count": sum(chest_counts.values()),
                "daily_available_count": chest_counts.get("DAILY", 0),
                "watch_available_count": chest_counts.get("WATCH", 0),
                "min_gems": int(policy["random_chest_min_gems"]),
                "max_gems": int(policy["random_chest_max_gems"]),
                "min_cash": self._money(self._reward_units_to_micros(int(policy["random_chest_min_gems"]), wallet_policy), wallet_policy),
                "max_cash": self._money(self._reward_units_to_micros(int(policy["random_chest_max_gems"]), wallet_policy), wallet_policy),
                "episodes_per_chest": episodes_per_chest,
                "episodes_to_next_chest": 0 if in_chest_cycle == 0 else episodes_per_chest - in_chest_cycle,
            },
            "pending_claims": task_rows,
            "episode_reward_claims": episode_reward_claims,
            "episode_reward_preboosted_ids": [item["episode_id"] for item in preboosted_episode_rows],
            "achievements": achievements,
            "crown_progress": self._crown_progress(connection, user_id),
            "episode_unlock": {
                "free_episodes_count": policy["free_episodes_count"],
                "placement_id": "rv_episode_pass",
                "episodes_per_rewarded_unlock": int(policy.get("episodes_per_rewarded_unlock", 1)),
                "episode_reward_boost_enabled": bool(policy.get("episode_reward_boost_enabled", True)),
                "next_episode_interstitial_enabled": False,
                "next_episode_rewarded_enabled": True,
                "extra_episode_unlock_enabled": bool(policy["extra_episode_unlock_enabled"]),
                "valid_watch_seconds": policy["valid_watch_seconds"],
            },
            "interstitial": {
                "cooldown_seconds": policy["interstitial_cooldown_seconds"],
                "hourly_limit": policy["interstitial_hourly_limit"],
                "global_hourly_limit": int(policy.get("interstitial_global_hourly_limit", 0)),
                "next_episode_enabled": bool(policy["next_episode_interstitial_enabled"]),
                "next_episode_hourly_limit": int(policy["next_episode_interstitial_hourly_limit"]),
                "pause_ad_enabled": bool(policy["pause_ad_enabled"]),
                "pause_ad_min_play_seconds": int(policy["pause_ad_min_play_seconds"]),
                "pause_ad_cooldown_seconds": int(policy["pause_ad_cooldown_seconds"]),
                "pause_ad_hourly_limit": int(policy["pause_interstitial_hourly_limit"]),
                "reward_claim_enabled": bool(policy["reward_claim_interstitial_enabled"]),
                "mid_episode_enabled": bool(policy["mid_episode_interstitial_enabled"]),
                "mid_episode_trigger_seconds": int(policy["mid_episode_interstitial_trigger_seconds"]),
                "mid_episode_hourly_limit": int(policy["mid_episode_interstitial_hourly_limit"]),
                "seek_enabled": bool(policy["seek_interstitial_enabled"]),
                "seek_hourly_limit": int(policy["seek_interstitial_hourly_limit"]),
                "speed_enabled": bool(policy["speed_interstitial_enabled"]),
                "speed_hourly_limit": int(policy["speed_interstitial_hourly_limit"]),
                "action_reward_threshold_gems": int(policy["action_interstitial_reward_threshold_gems"]),
                "episode_limit": int(policy["episode_interstitial_limit"]),
                "drama_switch_enabled": bool(policy["drama_switch_interstitial_enabled"]),
                "drama_switch_every_count": int(policy["drama_switch_interstitial_every_count"]),
                "drama_switch_hourly_limit": int(policy["drama_switch_interstitial_hourly_limit"]),
                "rewarded_fallback_enabled": bool(policy["rewarded_fallback_interstitial_enabled"]),
                "rewarded_skip_threshold": int(policy.get("interstitial_rewarded_skip_threshold", 0)),
                "rewarded_skip_count": int(counters.get("rewarded_decline_count", 0)),
                "last_rv_at": counters["last_rv_at"].isoformat() if counters["last_rv_at"] else None,
                "last_interstitial_at": counters["last_interstitial_at"].isoformat() if counters["last_interstitial_at"] else None,
            },
            "gem_damping": {
                "total_gems_earned": int(user["total_gems_earned"]),
                "cap": int(policy["gems_earned_cap"]),
                "tier1_threshold": int(policy["gem_damping_tier1_threshold"]),
                "tier2_threshold": int(policy["gem_damping_tier2_threshold"]),
                "tier3_threshold": int(policy["gem_damping_tier3_threshold"]),
                "tier4_threshold": int(policy["gem_damping_tier4_threshold"]),
                "tier1_multiplier": float(policy["gem_damping_tier1_multiplier"]),
                "tier2_multiplier": float(policy["gem_damping_tier2_multiplier"]),
                "tier3_multiplier": float(policy["gem_damping_tier3_multiplier"]),
                "tier4_multiplier": float(policy["gem_damping_tier4_multiplier"]),
                "tier5_multiplier": float(policy["gem_damping_tier5_multiplier"]),
                "scope": "ALL_GEM_SOURCES",
            },
            "withdrawal": self.withdrawal_preflight_inline(connection, user_id, policy, user, wallet_policy),
            "ledger": [
                {
                    "type": item["entry_type"],
                    "gems_delta": item["gems_delta"],
                    "cash_wallet_delta": self._money(item["cash_wallet_delta_micros"], wallet_policy),
                    "available_balance_delta": self._money(item["available_balance_delta_micros"], wallet_policy),
                    "created_at": item["created_at"].isoformat(),
                }
                for item in recent_ledger
            ],
        }

    def _crown_progress(self, connection: psycopg.Connection[Any], user_id: str) -> list[dict[str, Any]]:
        now = self._now()
        rows = self._all(connection, "SELECT * FROM achievement_policy ORDER BY level_rank ASC, code ASC")
        reward_policy = self._policy(connection)
        wallet_policy = self._wallet_policy(connection, user_id)
        episode_reward_gems = int(reward_policy["episode_reward_gems"])
        crown_daily_limit = int(reward_policy.get("crown_rewarded_daily_limit", 0))
        crown_ads_today = int(self._one(
            connection,
            "SELECT COUNT(*) AS count FROM rewarded_ad_events WHERE user_id = %s AND business_date = %s AND placement_id = 'rv_crown_upgrade'",
            (user_id, self._business_date()),
        )["count"])
        earned_codes = {item["code"] for item in self._all(connection, "SELECT code FROM user_achievements WHERE user_id = %s", (user_id,))}
        result: list[dict[str, Any]] = []
        titles = {"BRONZE": "青铜", "SILVER": "白银", "GOLD": "黄金", "DIAMOND": "钻石", "CROWN": "皇冠"}
        for row in rows:
            start = now - timedelta(hours=int(row["window_hours"]))
            watched_rounds = int(self._one(connection, "SELECT COUNT(*) AS count FROM watch_events WHERE user_id = %s AND recorded_at >= %s", (user_id, start))["count"])
            # Crown Road uses watched episodes plus only its own explicit upgrade ads.
            signins = 0
            rvs = int(self._one(connection, "SELECT COUNT(*) AS count FROM rewarded_ad_events WHERE user_id = %s AND placement_id = 'rv_crown_upgrade' AND processed_at >= %s", (user_id, start))["count"])
            ads_per_episode = max(1, int(self._policy(connection)["crown_rewarded_ads_per_episode"]))
            equivalent_rounds = rvs // ads_per_episode
            rounds = watched_rounds + equivalent_rounds
            requirements = [
                (rounds, int(row["required_rounds"])),
                (signins, int(row["required_signin_days"])),
                (rvs, int(row["required_rv_count"])),
            ]
            active = [(current, target) for current, target in requirements if target > 0]
            progress = min((current / target for current, target in active), default=1.0)
            result.append({
                "code": row["code"], "title": titles.get(row["code"], row["code"]),
                "level_rank": row["level_rank"], "earned": row["code"] in earned_codes, "gems": row["reward_gems"],
                "entitlement": row["virtual_entitlement"], "window_hours": row["window_hours"],
                "episode_reward_multiplier": float(row["episode_reward_multiplier"]),
                "episode_reward_gems": math.floor(episode_reward_gems * float(row["episode_reward_multiplier"])),
                "episode_reward_cash": self._money(self._reward_units_to_micros(math.floor(episode_reward_gems * float(row["episode_reward_multiplier"])), wallet_policy), wallet_policy),
                "cash": self._money(self._reward_units_to_micros(int(row["reward_gems"]), wallet_policy), wallet_policy),
                "rounds": {"current": rounds, "target": row["required_rounds"]},
                "watched_rounds": {"current": watched_rounds, "target": row["required_rounds"]},
                "ad_equivalent_rounds": equivalent_rounds,
                "ads_per_episode": ads_per_episode,
                "crown_rewarded_ads_today": crown_ads_today,
                "crown_rewarded_daily_limit": crown_daily_limit,
                "signins": {"current": 0, "target": 0},
                "rewarded_ads": {"current": rvs, "target": 0},
                "progress": round(progress, 4),
            })
        return result

    def withdrawal_preflight_inline(
        self, connection: psycopg.Connection[Any], user_id: str, policy: dict[str, Any], user: dict[str, Any], wallet_policy: dict[str, Any]
    ) -> dict[str, Any]:
        enabled = bool(policy["withdrawal_enabled"])
        threshold = int(wallet_policy["withdrawal_threshold_micros"])
        available = int(user["cash_wallet_micros"])
        if not enabled:
            reason = "提现功能尚未启用；你的现金奖励会直接累计在钱包中。"
        elif available < threshold:
            reason = f"钱包余额未达到最低门槛 {self._money_label(threshold, wallet_policy)}，还差 {self._money_label(threshold - available, wallet_policy)}。"
        else:
            reason = "已满足余额条件；仍需通过正式 KYC 与支付服务校验。"
        return {
            "enabled": enabled,
            "eligible": enabled and available >= threshold,
            "reason": reason,
            "threshold": self._money(threshold, wallet_policy),
            "threshold_usd_reference": self._usd_reference_money(threshold, wallet_policy),
            "currency": self._currency_payload(wallet_policy),
        }

    def _credit_gems(
        self,
        connection: psycopg.Connection[Any],
        user_id: str,
        gems: int,
        policy: dict[str, Any],
        entry_type: str,
        reference_type: str,
        reference_id: str,
    ) -> int:
        if gems <= 0:
            return 0
        existing = self._one(connection, "SELECT id FROM ledger_entries WHERE reference_type = %s AND reference_id = %s", (reference_type, reference_id))
        if existing:
            return 0
        user = self._one(connection, "SELECT total_gems_earned FROM users WHERE user_id = %s FOR UPDATE", (user_id,))
        total_before = int(user["total_gems_earned"])
        cap = int(policy.get("gems_earned_cap", 0))
        if cap > 0 and total_before >= cap:
            credited_gems = 0
        else:
            # v1.6.3.5：欢迎、签到、分集/翻倍、宝箱、皇冠等全部 Gems 来源均在结算时适用同一五档阻尼。
            tier1 = int(policy.get("gem_damping_tier1_threshold", 0))
            tier2 = int(policy.get("gem_damping_tier2_threshold", 0))
            tier3 = int(policy.get("gem_damping_tier3_threshold", 0))
            tier4 = int(policy.get("gem_damping_tier4_threshold", 0))
            if tier1 > 0 and total_before < tier1:
                multiplier = float(policy.get("gem_damping_tier1_multiplier", 1.0))
            elif tier2 > 0 and total_before < tier2:
                multiplier = float(policy.get("gem_damping_tier2_multiplier", 1.0))
            elif tier3 > 0 and total_before < tier3:
                multiplier = float(policy.get("gem_damping_tier3_multiplier", 1.0))
            elif tier4 > 0 and total_before < tier4:
                multiplier = float(policy.get("gem_damping_tier4_multiplier", 1.0))
            else:
                multiplier = float(policy.get("gem_damping_tier5_multiplier", policy.get("gem_damping_tier4_multiplier", 1.0)))
            credited_gems = max(0, math.floor(gems * multiplier + 1e-9))
            if cap > 0:
                credited_gems = min(credited_gems, cap - total_before)
        total = total_before + credited_gems
        # v1.6.4: reward units are internal-only for damping/audit. Every settled
        # reward is immediately credited to the user's one local-currency wallet;
        # no Gems balance or wallet-release conversion is exposed to the user.
        wallet_policy = self._wallet_policy(connection, user_id)
        credited_micros = self._reward_units_to_micros(credited_gems, wallet_policy)
        self._execute(
            connection,
            """
            UPDATE users
            SET cash_wallet_micros = cash_wallet_micros + %s,
                total_gems_earned = %s,
                updated_at = NOW()
            WHERE user_id = %s
            """,
            (credited_micros, total, user_id),
        )
        self._execute(
            connection,
            """
            INSERT INTO ledger_entries
            (user_id, entry_type, gems_delta, cash_wallet_delta_micros, reference_type, reference_id)
            VALUES (%s, %s, %s, %s, %s, %s)
            """,
            (user_id, entry_type, credited_gems, credited_micros, reference_type, reference_id),
        )
        return credited_gems

    @staticmethod
    def _reward_units_to_micros(reward_units: int, wallet_policy: dict[str, Any]) -> int:
        return max(0, (int(reward_units) * int(wallet_policy["currency_micros_per_10000_gems"])) // 10_000)

    @staticmethod
    def _normalise_market_code(region: str | None) -> str:
        value = (region or "").strip().upper().replace("-", "_")
        aliases = {"BRA": "BR", "BRAZIL": "BR", "PT_BR": "BR", "IDN": "ID", "INDONESIA": "ID", "IN_ID": "ID", "HKG": "HK", "HONG_KONG": "HK", "ZH_HK": "HK"}
        return aliases.get(value, value if value and value != "DEFAULT" else "DEFAULT")

    def _active_market_or_default(self, connection: psycopg.Connection[Any], requested_market: str) -> str:
        if requested_market == "DEFAULT":
            return "DEFAULT"
        row = self._one(
            connection,
            "SELECT market_code FROM market_wallet_policy WHERE market_code = %s AND enabled = TRUE",
            (requested_market,),
        )
        return requested_market if row else "DEFAULT"

    def _ensure_user(self, connection: psycopg.Connection[Any], user_id: str, region: str | None = None) -> None:
        requested_market = self._active_market_or_default(connection, self._normalise_market_code(region))
        self._execute(
            connection,
            "INSERT INTO users (user_id, market_code) VALUES (%s, %s) ON CONFLICT (user_id) DO NOTHING",
            (user_id, requested_market),
        )
        # 已产生资产的用户不切换币种，避免钱包和账本混入两种本币；零资产默认用户可在首次带地区启动时归入对应市场。
        if requested_market != "DEFAULT":
            self._execute(
                connection,
                """
                UPDATE users SET market_code = %s, updated_at = NOW()
                WHERE user_id = %s AND market_code = 'DEFAULT'
                  AND gems_balance = 0 AND cash_wallet_micros = 0 AND available_balance_micros = 0
                """,
                (requested_market, user_id),
            )
        wallet_policy = self._wallet_policy(connection, user_id)
        self._execute(
            connection,
            """
            INSERT INTO welcome_rewards (user_id, gems)
            VALUES (%s, %s)
            ON CONFLICT (user_id) DO NOTHING
            """,
            (user_id, wallet_policy["welcome_bonus_gems"]),
        )
        self._daily_counter(connection, user_id, self._business_date())

    def _wallet_policy(self, connection: psycopg.Connection[Any], user_id: str) -> dict[str, Any]:
        row = self._one(
            connection,
            """
            SELECT m.*
            FROM users u
            JOIN market_wallet_policy m ON m.market_code = COALESCE(NULLIF(u.market_code, ''), 'DEFAULT')
            WHERE u.user_id = %s
            """,
            (user_id,),
        )
        if not row:
            raise ServiceError(1503, "数据库缺少市场钱包策略，请执行 sql/010_v163_localized_wallet_and_rewards.sql", 503)
        return row

    def _lifecycle_day(self, created_at: datetime) -> int:
        return max(0, (self._business_date() - created_at.astimezone(self._business_timezone).date()).days)

    @staticmethod
    def _claim_stride(policy: dict[str, Any], lifecycle_day: int) -> int:
        if lifecycle_day == 0:
            return int(policy["claim_stride_day0"])
        if lifecycle_day <= 3:
            return int(policy["claim_stride_day1_3"])
        return int(policy["claim_stride_steady"])

    def _today_base_release_micros(self, connection: psycopg.Connection[Any], user_id: str, business_date: date) -> int:
        row = self._one(
            connection,
            """
            SELECT COALESCE(SUM(available_balance_delta_micros), 0) AS amount
            FROM ledger_entries
            WHERE user_id = %s AND entry_type = 'DAILY_RELEASE'
              AND reference_id = %s
            """,
            (user_id, f"{user_id}:{business_date.isoformat()}"),
        )
        return int(row["amount"] if row else 0)

    def _apply_wallet_release_on_active_day(self, connection: psycopg.Connection[Any], user_id: str) -> None:
        business_date = self._business_date()
        user = self._one(
            connection,
            """
            SELECT cash_wallet_micros, available_balance_micros, last_wallet_release_date,
                   pending_release_micros, pending_release_days, last_active_date
            FROM users WHERE user_id = %s FOR UPDATE
            """,
            (user_id,),
        )
        if user["last_wallet_release_date"] == business_date:
            return
        wallet_policy = self._wallet_policy(connection, user_id)
        base = int(wallet_policy["daily_release_micros"])
        last_active = user["last_active_date"]
        gap = (business_date - last_active).days if last_active else 0
        pending = int(user["pending_release_micros"])
        pending_days = int(user["pending_release_days"])
        if gap > 1 and int(user["cash_wallet_micros"]) > 0:
            missed = min(3 - pending_days, gap - 1)
            if missed > 0:
                pending += base * missed
                pending_days += missed
        releasable = min(int(user["cash_wallet_micros"]), base + pending)
        if releasable > 0:
            self._execute(
                connection,
                """
                UPDATE users
                SET cash_wallet_micros = cash_wallet_micros - %s,
                    available_balance_micros = available_balance_micros + %s,
                    pending_release_micros = 0,
                    pending_release_days = 0,
                    last_wallet_release_date = %s,
                    last_active_date = %s,
                    updated_at = NOW()
                WHERE user_id = %s
                """,
                (releasable, releasable, business_date, business_date, user_id),
            )
            self._execute(
                connection,
                """
                INSERT INTO ledger_entries
                (user_id, entry_type, cash_wallet_delta_micros, available_balance_delta_micros, reference_type, reference_id)
                VALUES (%s, 'DAILY_RELEASE', %s, %s, 'daily_release', %s)
                """,
                (user_id, -releasable, releasable, f"{user_id}:{business_date.isoformat()}"),
            )
        else:
            self._execute(
                connection,
                """
                UPDATE users SET pending_release_micros = %s, pending_release_days = %s,
                    last_wallet_release_date = %s, last_active_date = %s, updated_at = NOW()
                WHERE user_id = %s
                """,
                (pending, pending_days, business_date, business_date, user_id),
            )

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

    def _admin_policy_with_crowns(self, connection: psycopg.Connection[Any], policy: dict[str, Any]) -> dict[str, Any]:
        payload = self._admin_policy_payload(policy)
        payload["crown_multipliers"] = [
            {"code": item["code"], "title": item["title"], "level_rank": int(item["level_rank"]), "episode_reward_multiplier": float(item["episode_reward_multiplier"])}
            for item in self._all(
                connection,
                """
                SELECT code, level_rank, episode_reward_multiplier,
                       CASE code WHEN 'BRONZE' THEN '青铜' WHEN 'SILVER' THEN '白银'
                           WHEN 'GOLD' THEN '黄金' WHEN 'DIAMOND' THEN '钻石' WHEN 'CROWN' THEN '皇冠'
                           ELSE code END AS title
                FROM achievement_policy ORDER BY level_rank, code
                """,
            )
        ]
        payload["market_wallet_policies"] = self._market_wallet_policies(connection)
        return payload

    def _market_wallet_policies(self, connection: psycopg.Connection[Any]) -> list[dict[str, Any]]:
        return [
            {
                "market_code": row["market_code"], "currency_code": row["currency_code"],
                "currency_symbol": row["currency_symbol"], "currency_fraction_digits": int(row["currency_fraction_digits"]),
                "currency_micros_per_10000_gems": int(row["currency_micros_per_10000_gems"]),
                "usd_micros_per_10000_gems": int(row["usd_micros_per_10000_gems"]),
                "withdrawal_threshold_micros": int(row["withdrawal_threshold_micros"]),
                "welcome_bonus_gems": int(row["welcome_bonus_gems"]),
                "daily_release_micros": int(row["daily_release_micros"]),
                "enabled": bool(row.get("enabled", True)),
                "disabled_at": row.get("disabled_at"),
            }
            for row in self._all(connection, "SELECT * FROM market_wallet_policy ORDER BY market_code")
        ]

    @staticmethod
    def _admin_policy_payload(policy: dict[str, Any]) -> dict[str, Any]:
        return {
            "episode_reward_gems": int(policy["episode_reward_gems"]),
            "episodes_per_rewarded_unlock": int(policy.get("episodes_per_rewarded_unlock", 1)),
            "episode_reward_boost_enabled": bool(policy.get("episode_reward_boost_enabled", True)),
            "random_chest_min_gems": int(policy["random_chest_min_gems"]),
            "random_chest_max_gems": int(policy["random_chest_max_gems"]),
            "daily_random_chest_count": int(policy["daily_random_chest_count"]),
            "episodes_per_random_chest": int(policy["episodes_per_random_chest"]),
            "reward_claim_interstitial_enabled": bool(policy["reward_claim_interstitial_enabled"]),
            "interstitial_cooldown_seconds": int(policy["interstitial_cooldown_seconds"]),
            "interstitial_rewarded_skip_threshold": int(policy.get("interstitial_rewarded_skip_threshold", 0)),
            "mid_episode_interstitial_enabled": bool(policy["mid_episode_interstitial_enabled"]),
            "mid_episode_interstitial_trigger_seconds": int(policy["mid_episode_interstitial_trigger_seconds"]),
            "mid_episode_interstitial_hourly_limit": int(policy["mid_episode_interstitial_hourly_limit"]),
            "seek_interstitial_enabled": bool(policy["seek_interstitial_enabled"]),
            "seek_interstitial_hourly_limit": int(policy["seek_interstitial_hourly_limit"]),
            "next_episode_interstitial_enabled": bool(policy["next_episode_interstitial_enabled"]),
            "next_episode_interstitial_hourly_limit": int(policy["next_episode_interstitial_hourly_limit"]),
            "speed_interstitial_enabled": bool(policy["speed_interstitial_enabled"]),
            "speed_interstitial_hourly_limit": int(policy["speed_interstitial_hourly_limit"]),
            "action_interstitial_reward_threshold_gems": int(policy["action_interstitial_reward_threshold_gems"]),
            "review_reward_gems": int(policy["review_reward_gems"]),
            "crown_rewarded_ads_per_episode": int(policy["crown_rewarded_ads_per_episode"]),
            "crown_rewarded_daily_limit": int(policy.get("crown_rewarded_daily_limit", 0)),
            "release_boost_percent": float(policy["release_boost_percent"]),
            "release_boost_daily_limit": int(policy["release_boost_daily_limit"]),
            "rv_daily_limit": int(policy["rv_daily_limit"]),
            "episode_interstitial_limit": int(policy["episode_interstitial_limit"]),
            "interstitial_global_hourly_limit": int(policy.get("interstitial_global_hourly_limit", 0)),
            "drama_switch_interstitial_enabled": bool(policy["drama_switch_interstitial_enabled"]),
            "drama_switch_interstitial_every_count": int(policy["drama_switch_interstitial_every_count"]),
            "drama_switch_interstitial_hourly_limit": int(policy["drama_switch_interstitial_hourly_limit"]),
            "rewarded_fallback_interstitial_enabled": bool(policy["rewarded_fallback_interstitial_enabled"]),
            "gems_earned_cap": int(policy["gems_earned_cap"]),
            "gem_damping_tier1_threshold": int(policy["gem_damping_tier1_threshold"]),
            "gem_damping_tier2_threshold": int(policy["gem_damping_tier2_threshold"]),
            "gem_damping_tier3_threshold": int(policy["gem_damping_tier3_threshold"]),
            "gem_damping_tier4_threshold": int(policy["gem_damping_tier4_threshold"]),
            "gem_damping_tier1_multiplier": float(policy["gem_damping_tier1_multiplier"]),
            "gem_damping_tier2_multiplier": float(policy["gem_damping_tier2_multiplier"]),
            "gem_damping_tier3_multiplier": float(policy["gem_damping_tier3_multiplier"]),
            "gem_damping_tier4_multiplier": float(policy["gem_damping_tier4_multiplier"]),
            "gem_damping_tier5_multiplier": float(policy["gem_damping_tier5_multiplier"]),
            "gem_damping_scope": "ALL_GEM_SOURCES",
            "next_episode_interstitial_cooldown_seconds": int(policy["next_episode_interstitial_cooldown_seconds"]),
            "pause_ad_enabled": bool(policy["pause_ad_enabled"]),
            "pause_ad_min_play_seconds": int(policy["pause_ad_min_play_seconds"]),
            "pause_ad_cooldown_seconds": int(policy["pause_ad_cooldown_seconds"]),
            "pause_ad_hourly_limit": int(policy["pause_interstitial_hourly_limit"]),
            "updated_at": policy["updated_at"].isoformat(),
        }

    def _verify_admin_token(self, authorization: str | None) -> None:
        token = self.settings.admin_dashboard_token
        supplied = authorization.removeprefix("Bearer ").strip() if authorization else ""
        if not token:
            raise ServiceError(1901, "运营后台未启用；请设置 ADMIN_DASHBOARD_TOKEN", 404)
        if not hmac.compare_digest(supplied, token):
            raise ServiceError(1902, "管理员令牌无效", 401)

    def _verify_ad_proof(self, proof: str | None) -> None:
        # 仅 development + ALLOW_DEBUG_AD_PROOF 环境接受客户端测试证明。兼容历史
        # Pangle 联调和当前 TopOn 白包迁移；生产仍必须使用服务端回调/签名验证。
        if self.settings.allow_debug_ad_proof and proof in {"TEST_PANGLE_REWARD", "TEST_TOPON_REWARD"}:
            return
        if self.settings.pangle_callback_secret and proof == self.settings.pangle_callback_secret:
            return
        raise ServiceError(1403, "广告奖励尚未由服务端验证", 403)

    def _round_reward(self, policy: dict[str, Any], round_number: int) -> int:
        raw = policy["r0_gems"] * math.pow(float(round_number), -float(policy["decay_alpha"]))
        return max(int(raw), int(policy.get("ten_round_bonus_floor_gems", policy["floor_gems"])))

    def _business_date(self) -> date:
        return self._now().date()

    def _now(self) -> datetime:
        return datetime.now(UTC).astimezone(self._business_timezone)

    @staticmethod
    def _money(micros: int, wallet_policy: dict[str, Any]) -> str:
        digits = int(wallet_policy["currency_fraction_digits"])
        amount = micros / 1_000_000
        return f"{amount:.{digits}f}"

    @staticmethod
    def _usd_reference_money(micros: int, wallet_policy: dict[str, Any]) -> str:
        numerator = int(wallet_policy["usd_micros_per_10000_gems"])
        denominator = int(wallet_policy["currency_micros_per_10000_gems"])
        usd_micros = (micros * numerator) // max(denominator, 1)
        return f"{usd_micros / 1_000_000:.2f}"

    @staticmethod
    def _currency_payload(wallet_policy: dict[str, Any]) -> dict[str, Any]:
        return {
            "market_code": wallet_policy["market_code"],
            "code": wallet_policy["currency_code"],
            "symbol": wallet_policy["currency_symbol"],
            "fraction_digits": int(wallet_policy["currency_fraction_digits"]),
            "local_micros_per_10000_gems": int(wallet_policy["currency_micros_per_10000_gems"]),
            "usd_micros_per_10000_gems": int(wallet_policy["usd_micros_per_10000_gems"]),
        }

    def _money_label(self, micros: int, wallet_policy: dict[str, Any]) -> str:
        local = f"{wallet_policy['currency_symbol']}{self._money(micros, wallet_policy)}"
        if wallet_policy["currency_code"] == "USD":
            return local
        return f"{local}（约 US${self._usd_reference_money(micros, wallet_policy)}）"

    @staticmethod
    def _json(value: dict[str, Any]) -> str:
        import json

        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))

    @staticmethod
    def _execute(connection: psycopg.Connection[Any], sql: str, params: tuple[Any, ...] = ()) -> int:
        with connection.cursor() as cursor:
            cursor.execute(sql, params)
            return cursor.rowcount

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
