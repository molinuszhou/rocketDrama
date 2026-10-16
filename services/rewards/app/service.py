from __future__ import annotations

import base64
import hashlib
import hmac
import ipaddress
import json
import math
import secrets
from contextlib import contextmanager
from datetime import UTC, date, datetime, timedelta
from decimal import ROUND_DOWN, ROUND_HALF_UP, Decimal
from typing import Any, Iterator
from zoneinfo import ZoneInfo

import psycopg
from cryptography.fernet import Fernet, InvalidToken
from psycopg.rows import dict_row

from app.config import Settings


CHECKIN_CYCLE_DAYS = 7


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
        sensitive_cipher = self._sensitive_telemetry_cipher()
        return {
            "status": "ok",
            "database": "ok",
            "environment": self.settings.environment,
            "now": self._now(),
            "sensitive_telemetry": {
                "raw_audit_enabled": sensitive_cipher is not None,
                "raw_ip_retention_days": 14,
                "raw_appsflyer_retention_days": 30,
                "ip_fingerprint_retention_days": 180,
            },
        }

    def bootstrap(
        self,
        user_id: str,
        region: str | None = None,
        *,
        geo_country: str | None = None,
        client_ip: str | None = None,
        attribution: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        with self._transaction() as connection:
            # Bootstrap is also the session renewal endpoint. Requiring the old token here would
            # create a permanent 401 loop after its 30-day expiry or a signing-secret rotation:
            # the client could not obtain the very token required to refresh itself.
            self._ensure_user(connection, user_id, region, geo_country)
            self._record_bootstrap_ip_audit(connection, user_id, client_ip)
            if attribution:
                self._update_attribution(connection, user_id=user_id, **attribution)
            self._ensure_random_chests(connection, user_id, self._business_date(), self._policy(connection))
            snapshot = self._snapshot(connection, user_id)
            snapshot["session_token"] = self.issue_user_session(user_id)
            return snapshot

    def issue_user_session(self, user_id: str, ttl_seconds: int = 30 * 24 * 60 * 60) -> str:
        secret = self.settings.session_signing_secret
        if not secret:
            raise ServiceError(1903, "服务端未配置 SESSION_SIGNING_SECRET", 503)
        expires = int(self._now().timestamp()) + ttl_seconds
        subject = self._b64encode(user_id)
        nonce = secrets.token_urlsafe(18)
        payload = f"usr.{subject}.{expires}.{nonce}"
        signature = hmac.new(secret.encode(), payload.encode(), hashlib.sha256).hexdigest()
        return f"{payload}.{signature}"

    def verify_user_session(self, user_id: str, authorization: str | None) -> None:
        token = authorization.removeprefix("Bearer ").strip() if authorization else ""
        parts = token.split(".")
        if len(parts) != 5 or parts[0] != "usr":
            raise ServiceError(1904, "用户会话无效或已过期", 401)
        try:
            subject = self._b64decode(parts[1])
            expires = int(parts[2])
        except (ValueError, UnicodeDecodeError):
            raise ServiceError(1904, "用户会话无效或已过期", 401) from None
        if subject != user_id or expires <= int(self._now().timestamp()):
            raise ServiceError(1904, "用户会话无效或已过期", 401)
        payload = ".".join(parts[:4])
        expected = hmac.new(self.settings.session_signing_secret.encode(), payload.encode(), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(parts[4], expected):
            raise ServiceError(1904, "用户会话无效或已过期", 401)

    def admin_login(self, username: str, password: str) -> dict[str, Any]:
        normalized = username.strip()[:128]
        if not normalized or not password:
            raise ServiceError(1906, "管理员账号或密码不能为空", 422)
        with self._transaction() as connection:
            row = self._one(connection, "SELECT username, password_hash, enabled FROM admin_users WHERE username = %s", (normalized,))
            if not row and self.settings.admin_initial_username == normalized and self.settings.admin_initial_password:
                password_hash = self._hash_password(self.settings.admin_initial_password)
                self._execute(connection, "INSERT INTO admin_users (username, password_hash) VALUES (%s, %s) ON CONFLICT (username) DO NOTHING", (normalized, password_hash))
                row = self._one(connection, "SELECT username, password_hash, enabled FROM admin_users WHERE username = %s", (normalized,))
            if not row or not bool(row["enabled"]) or not self._verify_password(password, row["password_hash"]):
                raise ServiceError(1902, "管理员账号或密码无效", 401)
            self._execute(connection, "UPDATE admin_users SET last_login_at = NOW() WHERE username = %s", (normalized,))
        return {"session_token": self._issue_admin_session(normalized), "expires_in": 12 * 60 * 60}

    def update_attribution(
        self,
        *,
        user_id: str,
        appsflyer_uid: str | None,
        af_status: str,
        media_source: str | None,
        campaign: str | None,
        channel: str | None,
        raw_conversion_payload: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """Persist normalized AppsFlyer attribution and an optional encrypted raw audit.

        The client sends this only from AppsFlyer's conversion callback. The server derives
        the A/B surface from ``af_status`` rather than trusting a client-selected surface;
        optional blank values never erase a previously received attribution value. The raw SDK
        map is written only to the separately encrypted, retention-bounded audit table.
        """
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            self._update_attribution(
                connection,
                user_id=user_id,
                appsflyer_uid=appsflyer_uid,
                af_status=af_status,
                media_source=media_source,
                campaign=campaign,
                channel=channel,
            )
            sensitive_audit_stored = self._record_appsflyer_conversion_audit(
                connection,
                user_id=user_id,
                raw_conversion_payload=raw_conversion_payload,
            )
            user = self._one(connection, "SELECT * FROM users WHERE user_id = %s", (user_id,))
            return {
                "attribution": self._attribution_payload(user),
                "sensitive_audit_stored": sensitive_audit_stored,
            }

    def allow_episode_unlock_when_ad_unavailable(
        self,
        *,
        user_id: str,
        episode_id: str,
        episode_index: int,
    ) -> dict[str, Any]:
        """Allow playback only after failed ad delivery, without creating any cash reward."""
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            business_date = self._business_date()
            # Serialize same-user decisions before evaluating and consuming the daily cap.
            self._daily_counter(connection, user_id, business_date, lock=True)
            policy = self._policy(connection)
            daily_limit = int(policy["ad_unavailable_episode_unlock_daily_limit"])
            existing = self._one(
                connection,
                """SELECT 1 FROM ad_unavailable_episode_unlocks
                   WHERE user_id = %s AND business_date = %s AND episode_id = %s""",
                (user_id, business_date, episode_id),
            )
            if existing:
                self._grant_episode_unlock_entitlements(
                    connection, user_id, episode_id, 1, "AD_UNAVAILABLE", None,
                )
                return {"idempotent": True, "daily_limit": daily_limit, "snapshot": self._snapshot(connection, user_id)}
            if daily_limit <= 0:
                raise ServiceError(1031, "广告不可用时的剧集放行当前未启用", 409)
            used = int(self._one(
                connection,
                """SELECT COUNT(*) AS count FROM ad_unavailable_episode_unlocks
                   WHERE user_id = %s AND business_date = %s""",
                (user_id, business_date),
            )["count"])
            if used >= daily_limit:
                raise ServiceError(1032, "今日广告不可用放行次数已用完，请稍后再试", 429)
            self._execute(
                connection,
                """INSERT INTO ad_unavailable_episode_unlocks
                   (user_id, business_date, episode_id, episode_index)
                   VALUES (%s, %s, %s, %s)""",
                (user_id, business_date, episode_id, episode_index),
            )
            self._grant_episode_unlock_entitlements(
                connection, user_id, episode_id, 1, "AD_UNAVAILABLE", None,
            )
            return {
                "idempotent": False,
                "daily_limit": daily_limit,
                "daily_used": used + 1,
                "snapshot": self._snapshot(connection, user_id),
            }

    @staticmethod
    def _episode_unlock_targets(episode_target: str, unlock_count: int) -> list[str]:
        """Normalise legacy PSSDK IDs and expand a rewarded multi-episode pass."""
        target = episode_target.strip()
        legacy_prefix, legacy_marker, legacy_number = target.rpartition("-episode-")
        if legacy_marker and legacy_prefix and legacy_number.isdigit():
            target = f"{legacy_prefix}:{int(legacy_number)}"
        prefix, separator, episode_number = target.rpartition(":")
        if not separator or not prefix or not episode_number.isdigit():
            return [target]
        first_episode_number = int(episode_number)
        return [
            f"{prefix}:{first_episode_number + offset}"
            for offset in range(max(1, unlock_count))
        ]

    def _grant_episode_unlock_entitlements(
        self,
        connection: psycopg.Connection[Any],
        user_id: str,
        episode_target: str,
        unlock_count: int,
        source: str,
        source_event_id: str | None,
    ) -> None:
        for target in self._episode_unlock_targets(episode_target, unlock_count):
            self._execute(
                connection,
                """INSERT INTO episode_unlock_entitlements
                   (user_id, episode_target, source, source_event_id)
                   VALUES (%s, %s, %s, %s)
                   ON CONFLICT (user_id, episode_target) DO NOTHING""",
                (user_id, target, source, source_event_id),
            )

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
        # TopOn SDK 已通过客户端完整观看回调；服务端在这里按 ad_event_id
        # 记账、结算并幂等去重。proof 仅保留接口兼容，不参与正式环境验签。
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            business_date = self._business_date()
            existing = self._one(
                connection,
                "SELECT placement_id, result_payload FROM rewarded_ad_events WHERE ad_event_id = %s",
                (ad_event_id,),
            )
            if existing:
                existing_result = existing["result_payload"]
                if placement_id == "rv_episode_pass" and isinstance(existing_result, dict):
                    recorded_target = str(existing_result.get("episode_id") or target_id or "").strip()
                    if recorded_target:
                        self._grant_episode_unlock_entitlements(
                            connection,
                            user_id,
                            recorded_target,
                            int(existing_result.get("unlock_episode_count", 1)),
                            "AD_REWARDED",
                            ad_event_id,
                        )
                return {"idempotent": True, "result": existing["result_payload"], "snapshot": self._snapshot(connection, user_id)}

            policy = self._policy(connection)
            counters = self._daily_counter(connection, user_id, business_date, lock=True)
            rv_daily_limit = int(policy["rv_daily_limit"])
            if rv_daily_limit > 0 and int(counters["rv_count"]) >= rv_daily_limit:
                raise ServiceError(1001, "今日激励广告次数已达上限，请明天再来", 429)
            cooldown_policy = {
                "rv_crown_upgrade": ("crown_rewarded_cooldown_seconds", "皇冠成长正在充能，请稍后再试"),
                "rv_release_boost": ("release_boost_cooldown_seconds", "钱包额外奖励正在充能，请稍后再试"),
            }
            if placement_id in cooldown_policy:
                cooldown_field, message = cooldown_policy[placement_id]
                cooldown_seconds = int(policy.get(cooldown_field, 30))
                if cooldown_seconds > 0:
                    last_charge = self._one(
                        connection,
                        """SELECT processed_at FROM rewarded_ad_events
                           WHERE user_id = %s AND placement_id = %s
                           ORDER BY processed_at DESC LIMIT 1""",
                        (user_id, placement_id),
                    )
                    if last_charge:
                        elapsed = int((self._now() - last_charge["processed_at"]).total_seconds())
                        remaining = cooldown_seconds - elapsed
                        if remaining > 0:
                            raise ServiceError(1004, f"{message}（{remaining} 秒后可再次领取）", 429)
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
            verification_method = "debug_test_callback" if self.settings.allow_debug_ad_proof else "client_reward_callback"
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
            achievements = self._award_achievements_if_due(connection, user_id, policy)
            result["achievements_awarded"] = achievements
            # A Crown level may be reached by this completed rewarded ad. Fold that
            # cash into the same result so the client uses the standard receipt and
            # transparent wallet-flight animation rather than a silent balance jump.
            achievement_units = sum(int(item["gems"]) for item in achievements)
            if achievement_units > 0:
                wallet_policy = self._wallet_policy(connection, user_id)
                total_units = int(result.get("gems_added", 0)) + achievement_units
                total_micros = self._reward_units_to_micros(total_units, wallet_policy)
                result["gems_added"] = total_units
                result["cash_added"] = self._money(total_micros, wallet_policy)
                result["cash_added_usd_reference"] = self._usd_reference_money(total_micros, wallet_policy)
            # Persist the final payload only after all direct and Crown-level cash has
            # been settled; idempotent retries must return the same receipt amount.
            self._execute(
                connection,
                "UPDATE rewarded_ad_events SET result_payload = %s::jsonb WHERE ad_event_id = %s",
                (self._json(result), ad_event_id),
            )
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
            # Serialize each user's episode completion decision before checking IDs so
            # concurrent requests with different event UUIDs cannot advance progress twice.
            self._one(connection, "SELECT user_id FROM users WHERE user_id = %s FOR UPDATE", (user_id,))
            duplicate = self._one(connection, "SELECT event_id FROM watch_events WHERE event_id = %s", (event_id,))
            already_rewarded = self._one(
                connection,
                "SELECT id FROM episode_reward_claims WHERE user_id = %s AND episode_id = %s",
                (user_id, episode_id),
            )
            already_watched = self._one(
                connection,
                "SELECT event_id FROM watch_events WHERE user_id = %s AND drama_id = %s AND episode_id = %s LIMIT 1",
                (user_id, drama_id, episode_id),
            )
            if duplicate or already_rewarded or already_watched:
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
            self._daily_counter(connection, user_id, business_date, lock=True)
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
            self._ensure_episode_task_claims(connection, user_id, business_date, int(counters["episode_count"]))
            achievements = self._award_achievements_if_due(connection, user_id, policy)
            result = {
                "gems_added": 0,
                "episode_count": counters["episode_count"],
                "lifetime_episode_count": lifetime_episode_count,
                "episode_reward": episode_reward,
                # v1.6.4.7: watch-task random redpacks replace the chest module.
                "random_chests_created": [],
                "achievements_awarded": achievements,
            }
            return {"idempotent": False, "result": result, "snapshot": self._snapshot(connection, user_id)}

    def claim_episode_task(self, user_id: str, milestone_episode_count: int, reference_id: str) -> dict[str, Any]:
        """Legacy endpoint kept only to prevent an old client from bypassing the claim ad."""
        raise ServiceError(1224, "追剧任务需要完整观看激励广告后领取随机现金红包", 409)

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
                streak_day = previous["streak_day"] % CHECKIN_CYCLE_DAYS + 1
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
            "episode_reward_claim_prompt_threshold_gems",
            "episode_task_max_episode", "episode_task_post18_interval",
            "episode_task_redpacket_min_gems", "episode_task_redpacket_max_gems",
            "episode_task_tier1_min_gems", "episode_task_tier1_max_gems",
            "episode_task_tier2_min_gems", "episode_task_tier2_max_gems",
            "episode_task_tier3_min_gems", "episode_task_tier3_max_gems",
            "episode_task_tier4_min_gems", "episode_task_tier4_max_gems",
            "wallet_redpacket_min_gems", "wallet_redpacket_max_gems",
            "random_chest_min_gems", "random_chest_max_gems", "daily_random_chest_count", "episodes_per_random_chest",
            "interstitial_cooldown_seconds", "interstitial_rewarded_skip_threshold",
            "reward_claim_interstitial_enabled", "mid_episode_interstitial_enabled", "mid_episode_interstitial_trigger_seconds", "mid_episode_interstitial_repeat_seconds",
            "mid_episode_interstitial_hourly_limit", "seek_interstitial_enabled", "seek_interstitial_hourly_limit",
            "next_episode_interstitial_enabled", "speed_interstitial_enabled", "speed_interstitial_hourly_limit",
            "action_interstitial_reward_threshold_gems", "rv_daily_limit", "episode_interstitial_limit",
            "drama_switch_interstitial_enabled", "drama_switch_interstitial_every_count", "drama_switch_interstitial_hourly_limit",
            "rewarded_fallback_interstitial_enabled", "gems_earned_cap", "gem_damping_tier1_threshold",
            "gem_damping_tier2_threshold", "gem_damping_tier3_threshold", "gem_damping_tier4_threshold", "gem_damping_tier5_threshold", "gem_damping_tier1_multiplier",
            "gem_damping_tier2_multiplier", "gem_damping_tier3_multiplier", "gem_damping_tier4_multiplier", "gem_damping_tier5_multiplier", "gem_damping_tier6_multiplier",
            "crown_rewarded_ads_per_episode", "crown_rewarded_daily_limit", "crown_episode_progress_points",
            "crown_rewarded_ad_progress_points", "crown_rewarded_cooldown_seconds", "release_boost_percent",
            "release_boost_daily_limit", "release_boost_cooldown_seconds", "ad_unavailable_episode_unlock_daily_limit",
            "interstitial_global_hourly_limit",
        }
        changed = {key: value for key, value in updates.items() if key in allowed and value is not None}
        if not changed:
            raise ServiceError(1903, "未提供可更新的运营规则", 422)
        with self._transaction() as connection:
            policy = self._policy(connection)
            task_max_episode = int(changed.get("episode_task_max_episode", policy.get("episode_task_max_episode", 90)))
            if task_max_episode > 90:
                raise ServiceError(1908, "每日追剧任务最高只能设置为 90 集", 422)
            chest_min = int(changed.get("random_chest_min_gems", policy["random_chest_min_gems"]))
            chest_max = int(changed.get("random_chest_max_gems", policy["random_chest_max_gems"]))
            if chest_min > chest_max:
                raise ServiceError(1904, "随机宝箱最小宝石不能大于最大宝石", 422)
            task_red_min = int(changed.get("episode_task_redpacket_min_gems", policy["episode_task_redpacket_min_gems"]))
            task_red_max = int(changed.get("episode_task_redpacket_max_gems", policy["episode_task_redpacket_max_gems"]))
            wallet_red_min = int(changed.get("wallet_redpacket_min_gems", policy["wallet_redpacket_min_gems"]))
            wallet_red_max = int(changed.get("wallet_redpacket_max_gems", policy["wallet_redpacket_max_gems"]))
            if task_red_min > task_red_max or wallet_red_min > wallet_red_max:
                raise ServiceError(1904, "随机红包最小奖励不能大于最大奖励", 422)
            for tier in range(1, 5):
                task_min = int(changed.get(f"episode_task_tier{tier}_min_gems", policy.get(f"episode_task_tier{tier}_min_gems", task_red_min)))
                task_max = int(changed.get(f"episode_task_tier{tier}_max_gems", policy.get(f"episode_task_tier{tier}_max_gems", task_red_max)))
                if task_min > task_max:
                    raise ServiceError(1904, f"第 {tier} 档追剧任务红包最小奖励不能大于最大奖励", 422)
            damping_tier1 = int(changed.get("gem_damping_tier1_threshold", policy.get("gem_damping_tier1_threshold", 0)))
            damping_tier2 = int(changed.get("gem_damping_tier2_threshold", policy.get("gem_damping_tier2_threshold", 0)))
            damping_tier3 = int(changed.get("gem_damping_tier3_threshold", policy.get("gem_damping_tier3_threshold", 0)))
            damping_tier4 = int(changed.get("gem_damping_tier4_threshold", policy.get("gem_damping_tier4_threshold", 0)))
            damping_tier5 = int(changed.get("gem_damping_tier5_threshold", policy.get("gem_damping_tier5_threshold", 0)))
            gems_cap = int(changed.get("gems_earned_cap", policy.get("gems_earned_cap", 0)))
            if any(next_tier > 0 and current > next_tier for current, next_tier in ((damping_tier1, damping_tier2), (damping_tier2, damping_tier3), (damping_tier3, damping_tier4), (damping_tier4, damping_tier5))):
                raise ServiceError(1906, "六档奖励阻尼阈值必须按从小到大排序", 422)
            if gems_cap > 0 and any(tier > gems_cap for tier in (damping_tier1, damping_tier2, damping_tier3, damping_tier4, damping_tier5)):
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
            # Materialise new task nodes immediately so an increased maximum is visible
            # in the next client snapshot without requiring an additional watch event.
            self._sync_episode_task_policy(connection, self._policy(connection))
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
            # Horizontal drama switching and a failed rewarded placement are explicit
            # exception routes. Their own enablement/hourly controls below remain in
            # force, but a prior content mid-roll must not silently suppress them.
            independent_exception = placement_id in {"int_drama_switch", "int_rewarded_fallback", "int_incentive_action"}

            cooldown_seconds = int(policy.get("interstitial_cooldown_seconds", 0))
            if not independent_exception and counters["last_interstitial_at"] and now - counters["last_interstitial_at"] < timedelta(seconds=cooldown_seconds):
                raise ServiceError(2002, "插屏冷却期未结束", 409)

            global_hourly_limit = int(policy.get("interstitial_global_hourly_limit", 0))
            if not independent_exception and global_hourly_limit > 0:
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

            # Playback-time mid-rolls follow their own first-interval/repeat schedule.
            # Do not let the legacy one-ad-per-episode cap suppress the later 6-minute
            # intervals in a long episode. Seek and speed remain legacy action caps.
            if episode_id and placement_id in {"int_seek", "int_speed"}:
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
            elif placement_id == "int_incentive_action":
                # Organic A uses an interstitial as the explicitly disclosed incentive format.
                # Reuse the existing operator toggle so this route can be disabled immediately.
                if not bool(policy["rewarded_fallback_interstitial_enabled"]):
                    raise ServiceError(2020, "插屏激励当前未启用", 409)
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

    def record_ad_mediation_event(
        self,
        *,
        client_event_id: str,
        install_id_hash: str,
        event_type: str,
        logical_placement: str,
        topon_placement_id: str,
        ad_format: str,
        ad_network: str,
        ad_source_id: str,
        app_version: str,
        currency: str,
        ecpm: float | None,
        reporting_targets: list[str],
        occurred_at_ms: int,
    ) -> dict[str, Any]:
        """Persist a de-duplicated, non-financial mirror of TopOn callbacks.

        The Android client only sends a SHA-256 installation pseudonym. Event values
        are observed mediation estimates (``eCPM / 1000``), not a settlement or a
        replacement for TopOn/MAX financial reports.
        """

        occurred_at = datetime.fromtimestamp(occurred_at_ms / 1_000, UTC)
        # Reject obviously stale/future client clocks rather than contaminating daily
        # operations reporting. A retry queue is expected to deliver events shortly
        # after the device becomes online.
        if abs((datetime.now(UTC) - occurred_at).total_seconds()) > 31 * 24 * 60 * 60:
            raise ServiceError(2301, "广告运营事件时间超出允许范围", 422)
        normalized_currency = currency.strip().upper()
        normalized_ecpm = Decimal(str(ecpm)) if ecpm is not None else None
        estimated_value = (
            normalized_ecpm / Decimal("1000")
            # A single merged SHOWN record now carries its paid-impression eCPM when
            # TopOn/MAX makes it available. Do not require a second PAID_REVENUE row.
            if normalized_ecpm is not None
            else Decimal("0")
        )
        normalized_targets = list(dict.fromkeys(
            target.strip()[:40] for target in reporting_targets if target.strip()
        ))[:10]
        metric_date = occurred_at.date()

        with self._transaction() as connection:
            existing = self._one(
                connection,
                "SELECT client_event_id FROM ad_mediation_events WHERE client_event_id = %s",
                (client_event_id,),
            )
            if existing:
                return {
                    "idempotent": True,
                    "accepted": True,
                    "client_event_id": client_event_id,
                    "event_type": event_type,
                }
            self._execute(
                connection,
                """
                INSERT INTO ad_mediation_events (
                    client_event_id, install_id_hash, event_type, logical_placement,
                    topon_placement_id, ad_format, ad_network, ad_source_id,
                    app_version, currency, ecpm, impression_value, reporting_targets, client_occurred_at
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                """,
                (
                    client_event_id,
                    install_id_hash,
                    event_type,
                    logical_placement,
                    topon_placement_id,
                    ad_format,
                    ad_network,
                    ad_source_id,
                    app_version,
                    normalized_currency,
                    normalized_ecpm,
                    estimated_value,
                    normalized_targets,
                    occurred_at,
                ),
            )
            self._execute(
                connection,
                """
                INSERT INTO ad_mediation_daily_metrics (
                    metric_date, app_version, logical_placement, topon_placement_id,
                    ad_format, ad_network, ad_source_id, currency,
                    shown_count, paid_revenue_count, revenue_value
                ) VALUES (
                    %s, %s, %s, %s, %s, %s, %s, %s,
                    %s, %s, %s
                )
                ON CONFLICT (
                    metric_date, app_version, logical_placement, topon_placement_id,
                    ad_format, ad_network, ad_source_id, currency
                ) DO UPDATE SET
                    shown_count = ad_mediation_daily_metrics.shown_count + EXCLUDED.shown_count,
                    paid_revenue_count = ad_mediation_daily_metrics.paid_revenue_count + EXCLUDED.paid_revenue_count,
                    revenue_value = ad_mediation_daily_metrics.revenue_value + EXCLUDED.revenue_value,
                    updated_at = NOW()
                """,
                (
                    metric_date,
                    app_version,
                    logical_placement,
                    topon_placement_id,
                    ad_format,
                    ad_network,
                    ad_source_id,
                    normalized_currency,
                    1 if event_type == "SHOWN" else 0,
                    1 if normalized_ecpm is not None else 0,
                    estimated_value,
                ),
            )
            # Preserve compact daily aggregates indefinitely while retaining only a
            # bounded reconciliation window of raw client callbacks.
            self._execute(
                connection,
                "DELETE FROM ad_mediation_events WHERE accepted_at < NOW() - INTERVAL '90 days'",
            )
            return {
                "idempotent": False,
                "accepted": True,
                "client_event_id": client_event_id,
                "event_type": event_type,
                "estimated_value": format(estimated_value, "f"),
            }

    def admin_ad_mediation_daily_metrics(
        self,
        authorization: str | None,
        start_date: date | None = None,
        end_date: date | None = None,
        summary_page: int = 1,
        summary_page_size: int = 50,
        raw_page: int = 1,
        raw_page_size: int = 100,
        logical_placement: str = "",
        topon_placement_id: str = "",
        ad_format: str = "",
        ad_network: str = "",
        event_type: str = "",
        reporting_target: str = "",
        app_version: str = "",
        currency: str = "",
    ) -> dict[str, Any]:
        """Return compact daily reconciliations plus independently pageable raw callbacks.

        One actual impression is mirrored once.  A single row may contain eCPM and all
        downstream reporting targets, so the operations database does not multiply rows
        by AppsFlyer/Firebase/Xuanyu/PSSDK delivery attempts.
        """
        self._verify_admin_token(authorization)
        default_end = datetime.now(UTC).date()
        actual_end = end_date or default_end
        actual_start = start_date or actual_end - timedelta(days=6)
        if actual_start > actual_end:
            raise ServiceError(2302, "广告运营报表开始日期不能晚于结束日期", 422)
        if actual_end - actual_start > timedelta(days=90):
            raise ServiceError(2303, "广告运营原始回调最大查询范围为 90 天", 422)

        summary_page = max(int(summary_page), 1)
        raw_page = max(int(raw_page), 1)
        summary_page_size = min(max(int(summary_page_size), 1), 200)
        raw_page_size = min(max(int(raw_page_size), 1), 200)
        normalised = {
            "logical_placement": logical_placement.strip()[:160],
            "topon_placement_id": topon_placement_id.strip()[:160],
            "ad_format": ad_format.strip().lower()[:40],
            "ad_network": ad_network.strip()[:160],
            "event_type": event_type.strip().upper()[:40],
            "reporting_target": reporting_target.strip()[:40],
            "app_version": app_version.strip()[:80],
            "currency": currency.strip().upper()[:16],
        }
        allowed_event_types = {"", "SHOWN", "PAID_REVENUE"}
        if normalised["event_type"] not in allowed_event_types:
            raise ServiceError(2304, "不支持的广告事件类型筛选", 422)

        raw_filters = ["client_occurred_at >= %s", "client_occurred_at < %s"]
        raw_params: list[Any] = [
            self._utc_start_of_day(actual_start),
            self._utc_start_of_day(actual_end + timedelta(days=1)),
        ]
        for column in ("logical_placement", "topon_placement_id", "ad_format", "ad_network", "app_version", "currency"):
            value = normalised[column]
            if value:
                raw_filters.append(f"{column} = %s")
                raw_params.append(value)
        if normalised["event_type"]:
            raw_filters.append("event_type = %s")
            raw_params.append(normalised["event_type"])
        if normalised["reporting_target"]:
            raw_filters.append("%s = ANY(reporting_targets)")
            raw_params.append(normalised["reporting_target"])
        raw_where = " WHERE " + " AND ".join(raw_filters)

        # Daily aggregates lack target/event-type dimensions by design.  For those two
        # filters we aggregate the retained raw records instead, maintaining exactness.
        raw_only_summary = bool(normalised["event_type"] or normalised["reporting_target"])
        if raw_only_summary:
            summary_where, summary_params = raw_where, list(raw_params)
            summary_source = "ad_mediation_events"
            summary_select = """
                client_occurred_at::date AS metric_date, app_version, logical_placement,
                topon_placement_id, ad_format, ad_network, ad_source_id, currency,
                COUNT(*) FILTER (WHERE event_type = 'SHOWN') AS shown_count,
                COUNT(*) FILTER (WHERE ecpm IS NOT NULL) AS paid_revenue_count,
                COALESCE(SUM(impression_value), 0) AS revenue_value,
                MAX(accepted_at) AS updated_at
            """
            summary_group = "GROUP BY client_occurred_at::date, app_version, logical_placement, topon_placement_id, ad_format, ad_network, ad_source_id, currency"
        else:
            summary_filters = ["metric_date BETWEEN %s AND %s"]
            summary_params = [actual_start, actual_end]
            for column in ("logical_placement", "topon_placement_id", "ad_format", "ad_network", "app_version", "currency"):
                value = normalised[column]
                if value:
                    summary_filters.append(f"{column} = %s")
                    summary_params.append(value)
            summary_where = " WHERE " + " AND ".join(summary_filters)
            summary_source = "ad_mediation_daily_metrics"
            summary_select = """
                metric_date, app_version, logical_placement, topon_placement_id,
                ad_format, ad_network, ad_source_id, currency,
                shown_count, paid_revenue_count, revenue_value, updated_at
            """
            summary_group = ""

        with self._transaction() as connection:
            summary_total = self._one(connection, f"SELECT COUNT(*) AS count FROM (SELECT 1 FROM {summary_source}{summary_where} {summary_group}) grouped", tuple(summary_params)) or {}
            rows = self._all(connection, f"""
                SELECT {summary_select}
                FROM {summary_source}
                {summary_where}
                {summary_group}
                ORDER BY metric_date DESC, revenue_value DESC, shown_count DESC,
                         logical_placement ASC, ad_network ASC
                LIMIT %s OFFSET %s
            """, tuple(summary_params + [summary_page_size, (summary_page - 1) * summary_page_size]))
            if raw_only_summary:
                totals = self._one(connection, f"""
                    SELECT COALESCE(SUM(CASE WHEN event_type = 'SHOWN' THEN 1 ELSE 0 END), 0) AS shown_count,
                           COALESCE(SUM(CASE WHEN ecpm IS NOT NULL THEN 1 ELSE 0 END), 0) AS paid_revenue_count,
                           COALESCE(SUM(impression_value), 0) AS revenue_value
                    FROM ad_mediation_events {raw_where}
                """, tuple(raw_params)) or {}
            else:
                totals = self._one(connection, f"""
                    SELECT COALESCE(SUM(shown_count), 0) AS shown_count,
                           COALESCE(SUM(paid_revenue_count), 0) AS paid_revenue_count,
                           COALESCE(SUM(revenue_value), 0) AS revenue_value
                    FROM ad_mediation_daily_metrics {summary_where}
                """, tuple(summary_params)) or {}
            raw_total = self._one(connection, f"SELECT COUNT(*) AS count FROM ad_mediation_events {raw_where}", tuple(raw_params)) or {}
            raw_events = self._all(connection, f"""
                SELECT client_event_id, event_type, logical_placement, topon_placement_id,
                       ad_format, ad_network, ad_source_id, app_version, currency, ecpm,
                       impression_value, reporting_targets, client_occurred_at, accepted_at
                FROM ad_mediation_events {raw_where}
                ORDER BY client_occurred_at DESC, accepted_at DESC
                LIMIT %s OFFSET %s
            """, tuple(raw_params + [raw_page_size, (raw_page - 1) * raw_page_size]))

        def serialize(row: dict[str, Any]) -> dict[str, Any]:
            return {
                **row,
                "metric_date": row["metric_date"].isoformat(),
                "currency": row["currency"].strip(),
                "shown_count": int(row["shown_count"]),
                "paid_revenue_count": int(row["paid_revenue_count"]),
                "revenue_value": format(Decimal(str(row["revenue_value"])), "f"),
                "updated_at": row["updated_at"].isoformat(),
            }
        def serialize_event(row: dict[str, Any]) -> dict[str, Any]:
            return {
                **row,
                "currency": row["currency"].strip(),
                "ecpm": format(Decimal(str(row["ecpm"])), "f") if row["ecpm"] is not None else "",
                "impression_value": format(Decimal(str(row["impression_value"])), "f"),
                "reporting_targets": row.get("reporting_targets") or [],
                "client_occurred_at": row["client_occurred_at"].isoformat(),
                "accepted_at": row["accepted_at"].isoformat(),
            }
        target_note = "；按事件或回传目标筛选时，日汇总由原始回调实时聚合，查询范围最多 90 天" if raw_only_summary else ""
        return {
            "start_date": actual_start.isoformat(),
            "end_date": actual_end.isoformat(),
            "summary_page": summary_page,
            "summary_page_size": summary_page_size,
            "summary_total": int(summary_total.get("count", 0)),
            "raw_page": raw_page,
            "raw_page_size": raw_page_size,
            "raw_total": int(raw_total.get("count", 0)),
            "filters": normalised,
            "totals": {
                "shown_count": int(totals.get("shown_count", 0)),
                "paid_revenue_count": int(totals.get("paid_revenue_count", 0)),
                "revenue_value": format(Decimal(str(totals.get("revenue_value", 0))), "f"),
            },
            "rows": [serialize(row) for row in rows],
            "raw_events": [serialize_event(row) for row in raw_events],
            "disclaimer": "每次展示只保留一条客户端广告运营记录；eCPM/1000 仅为运营估算，TopOn/MAX 后台结算报表为财务事实来源" + target_note,
        }

    def record_reporting_delivery_event(
        self,
        *,
        client_event_id: str,
        install_id_hash: str,
        user_uid: str = "",
        provider: str,
        event_name: str,
        status: str,
        logical_placement: str,
        ad_unit_id: str,
        error_code: str,
        currency: str,
        ecpm: float | None,
        value: float | None,
        occurred_at_ms: int,
    ) -> dict[str, Any]:
        """Persist a privacy-minimised reporting delivery outcome for operations review."""

        occurred_at = datetime.fromtimestamp(occurred_at_ms / 1_000, UTC)
        if abs((datetime.now(UTC) - occurred_at).total_seconds()) > 31 * 24 * 60 * 60:
            raise ServiceError(2311, "上报记录时间超出允许范围", 422)
        normalized_currency = currency.strip().upper()
        normalized_ecpm = Decimal(str(ecpm)) if ecpm is not None else None
        reported_value = Decimal(str(value)) if value is not None else Decimal("0")
        with self._transaction() as connection:
            existing = self._one(
                connection,
                "SELECT client_event_id FROM reporting_delivery_events WHERE client_event_id = %s",
                (client_event_id,),
            )
            if existing:
                return {"idempotent": True, "accepted": True, "client_event_id": client_event_id}
            self._execute(
                connection,
                """
                INSERT INTO reporting_delivery_events (
                    client_event_id, user_uid, install_id_hash, provider, event_name, status,
                    logical_placement, ad_unit_id, error_code, currency, ecpm,
                    reported_value, client_occurred_at
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                """,
                (
                    client_event_id, user_uid[:128], install_id_hash, provider, event_name, status,
                    logical_placement, ad_unit_id, error_code, normalized_currency, normalized_ecpm,
                    reported_value, occurred_at,
                ),
            )
            self._execute(
                connection,
                "DELETE FROM reporting_delivery_events WHERE accepted_at < NOW() - INTERVAL '90 days'",
            )
        return {"idempotent": False, "accepted": True, "client_event_id": client_event_id}

    def record_user_behavior_event(
        self,
        *,
        user_id: str,
        event_id: str,
        session_id: str,
        event_category: str,
        event_name: str,
        properties: dict[str, Any],
        app_version: str,
        occurred_at_ms: int,
    ) -> dict[str, Any]:
        """Store a de-duplicated, privacy-minimised client lifecycle event.

        This stream exists to understand the early product funnel. It is not a
        financial ledger and deliberately rejects unbounded or nested metadata
        that could accidentally become a raw attribution or advertising payload.
        """

        occurred_at = datetime.fromtimestamp(occurred_at_ms / 1_000, UTC)
        if abs((datetime.now(UTC) - occurred_at).total_seconds()) > 31 * 24 * 60 * 60:
            raise ServiceError(2319, "用户行为事件时间超出允许范围", 422)
        normalized_properties = self._sanitize_behavior_properties(properties)
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            existing = self._one(
                connection,
                "SELECT event_id FROM user_behavior_events WHERE event_id = %s",
                (event_id,),
            )
            if existing:
                return {"idempotent": True, "accepted": True, "event_id": event_id}
            self._execute(
                connection,
                """
                INSERT INTO user_behavior_events (
                    event_id, user_id, session_id, event_category, event_name,
                    properties, app_version, client_occurred_at
                ) VALUES (%s, %s, %s, %s, %s, %s::jsonb, %s, %s)
                """,
                (
                    event_id,
                    user_id,
                    session_id,
                    event_category,
                    event_name,
                    self._json(normalized_properties),
                    app_version,
                    occurred_at,
                ),
            )
            # Behaviour telemetry is operationally useful only for short-term
            # funnel analysis. Keep its retention deliberately bounded.
            self._execute(
                connection,
                "DELETE FROM user_behavior_events WHERE accepted_at < NOW() - INTERVAL '90 days'",
            )
        return {"idempotent": False, "accepted": True, "event_id": event_id}

    @staticmethod
    def _sanitize_behavior_properties(properties: dict[str, Any]) -> dict[str, str | int | float | bool]:
        """Keep only small, scalar event dimensions safe for JSONB storage."""

        clean: dict[str, str | int | float | bool] = {}
        for raw_key, raw_value in properties.items():
            key = str(raw_key).strip().lower()[:40]
            if not key or not all(character.isascii() and (character.isalnum() or character == "_") for character in key):
                continue
            if isinstance(raw_value, bool):
                clean[key] = raw_value
            elif isinstance(raw_value, int):
                clean[key] = raw_value
            elif isinstance(raw_value, float) and math.isfinite(raw_value):
                clean[key] = raw_value
            elif isinstance(raw_value, str):
                value = raw_value.strip()
                if value:
                    clean[key] = value[:512]
            if len(clean) >= 16:
                break
        return clean

    def admin_behavior_flow_metrics(
        self,
        authorization: str | None,
        *,
        start_date: date | None = None,
        end_date: date | None = None,
        user_id: str = "",
        event_category: str = "",
        event_name: str = "",
        page: int = 1,
        page_size: int = 100,
    ) -> dict[str, Any]:
        """Return an operator view of the anonymous lifecycle and early-video funnel."""

        self._verify_admin_token(authorization)
        actual_end = end_date or datetime.now(UTC).date()
        actual_start = start_date or actual_end - timedelta(days=6)
        if actual_start > actual_end:
            raise ServiceError(2320, "行为记录开始日期不能晚于结束日期", 422)
        if actual_end - actual_start > timedelta(days=90):
            raise ServiceError(2321, "行为记录最大查询范围为 90 天", 422)
        normalized_category = event_category.strip().upper()
        categories = {"APP", "SDK", "ONBOARDING", "PLAYBACK", "AD", "NETWORK", "REWARDS"}
        if normalized_category and normalized_category not in categories:
            raise ServiceError(2322, "行为类别不受支持", 422)
        normalized_event = event_name.strip().upper()
        if normalized_event and (len(normalized_event) > 80 or not normalized_event.replace("_", "").isalnum()):
            raise ServiceError(2323, "行为事件名称格式无效", 422)
        normalized_user_id = user_id.strip()[:128]
        actual_page = max(int(page), 1)
        actual_limit = min(max(int(page_size), 1), 500)
        actual_offset = (actual_page - 1) * actual_limit
        base_filters = ["client_occurred_at >= %s", "client_occurred_at < %s"]
        base_params: list[Any] = [
            self._utc_start_of_day(actual_start),
            self._utc_start_of_day(actual_end + timedelta(days=1)),
        ]
        if normalized_user_id:
            base_filters.append("user_id = %s")
            base_params.append(normalized_user_id)
        where_base = " WHERE " + " AND ".join(base_filters)
        event_filters = list(base_filters)
        event_params = list(base_params)
        if normalized_category:
            event_filters.append("event_category = %s")
            event_params.append(normalized_category)
        if normalized_event:
            event_filters.append("event_name = %s")
            event_params.append(normalized_event)
        where_events = " WHERE " + " AND ".join(event_filters)

        with self._transaction() as connection:
            totals = self._one(
                connection,
                f"""
                SELECT COUNT(*) AS event_count, COUNT(DISTINCT user_id) AS user_count
                FROM user_behavior_events
                {where_events}
                """,
                tuple(event_params),
            ) or {}
            breakdown = self._all(
                connection,
                f"""
                SELECT event_category, event_name,
                       COUNT(*) AS event_count, COUNT(DISTINCT user_id) AS user_count
                FROM user_behavior_events
                {where_events}
                GROUP BY event_category, event_name
                ORDER BY event_category ASC, event_name ASC
                """,
                tuple(event_params),
            )
            early_videos = self._all(
                connection,
                f"""
                SELECT
                    (properties ->> 'early_video_rank')::INTEGER AS early_video_rank,
                    COUNT(DISTINCT user_id) FILTER (WHERE event_name = 'EARLY_VIDEO_STARTED') AS started_users,
                    COUNT(DISTINCT user_id) FILTER (WHERE event_name = 'EARLY_VIDEO_COMPLETED') AS completed_users,
                    COUNT(*) FILTER (WHERE event_name = 'EARLY_VIDEO_PLAYBACK_FAILED') AS failure_events
                FROM user_behavior_events
                {where_base}
                  AND event_category = 'PLAYBACK'
                  AND event_name IN ('EARLY_VIDEO_STARTED', 'EARLY_VIDEO_COMPLETED', 'EARLY_VIDEO_PLAYBACK_FAILED')
                  AND (properties ->> 'early_video_rank') ~ '^[1-5]$'
                GROUP BY (properties ->> 'early_video_rank')::INTEGER
                ORDER BY early_video_rank ASC
                """,
                tuple(base_params),
            )
            rows = self._all(
                connection,
                f"""
                SELECT user_id, session_id, event_category, event_name, properties,
                       app_version, client_occurred_at, accepted_at
                FROM user_behavior_events
                {where_events}
                ORDER BY client_occurred_at DESC, accepted_at DESC
                LIMIT %s OFFSET %s
                """,
                tuple(event_params + [actual_limit, actual_offset]),
            )

        early_by_rank = {int(item["early_video_rank"]): item for item in early_videos}
        early_rows: list[dict[str, int]] = []
        for rank in range(1, 6):
            row = early_by_rank.get(rank, {})
            early_rows.append({
                "early_video_rank": rank,
                "started_users": int(row.get("started_users", 0)),
                "completed_users": int(row.get("completed_users", 0)),
                "failure_events": int(row.get("failure_events", 0)),
            })

        return {
            "start_date": actual_start.isoformat(),
            "end_date": actual_end.isoformat(),
            "page": actual_page,
            "page_size": actual_limit,
            "offset": actual_offset,
            "total": int(totals.get("event_count", 0)),
            "unique_users": int(totals.get("user_count", 0)),
            "filters": {
                "user_id": normalized_user_id,
                "event_category": normalized_category,
                "event_name": normalized_event,
            },
            "breakdown": [
                {
                    "event_category": item["event_category"],
                    "event_name": item["event_name"],
                    "event_count": int(item["event_count"]),
                    "user_count": int(item["user_count"]),
                }
                for item in breakdown
            ],
            "early_videos": early_rows,
            "rows": [
                {
                    **item,
                    "client_occurred_at": item["client_occurred_at"].isoformat(),
                    "accepted_at": item["accepted_at"].isoformat(),
                }
                for item in rows
            ],
            "definition": (
                "按客户端事件发生时间的 UTC+0 自然日统计。仅保留匿名用户、有限事件名称及最小化标量属性；"
                "首五视频从安装了此埋点版本后首次观察到的不同视频开始计数，不追溯历史版本。"
            ),
        }

    def admin_sensitive_telemetry(
        self,
        authorization: str | None,
        *,
        user_id: str,
        limit: int = 50,
    ) -> dict[str, Any]:
        """Return decrypted raw diagnostics for one user to an authenticated administrator.

        This deliberately has no list/export endpoint: operators must enter a complete anonymous
        user identifier, and the same encryption key required for writing is required for reading.
        """

        self._verify_admin_token(authorization)
        normalized_user_id = user_id.strip()[:128]
        if not normalized_user_id:
            raise ServiceError(2324, "受限审计查询需要完整匿名用户 ID", 422)
        actual_limit = min(max(int(limit), 1), 100)
        cipher = self._sensitive_telemetry_cipher()
        if cipher is None:
            return {
                "available": False,
                "reason": "服务端未配置 TELEMETRY_ENCRYPTION_KEY，未保存原始敏感审计数据。",
                "events": [],
                "ip_fingerprints": [],
                "retention": {"raw_ip_days": 14, "raw_appsflyer_days": 30, "ip_fingerprint_days": 180},
            }
        with self._transaction() as connection:
            self._purge_expired_sensitive_telemetry(connection)
            rows = self._all(
                connection,
                """
                SELECT audit_type, encrypted_payload, received_at, expires_at
                FROM sensitive_telemetry_audit_events
                WHERE user_id = %s
                ORDER BY received_at DESC, audit_id DESC
                LIMIT %s
                """,
                (normalized_user_id, actual_limit),
            )
            ip_fingerprints = self._all(
                connection,
                """
                SELECT ip_fingerprint, first_seen_at, last_seen_at, expires_at
                FROM sensitive_telemetry_ip_hash_observations
                WHERE user_id = %s
                ORDER BY last_seen_at DESC
                LIMIT %s
                """,
                (normalized_user_id, actual_limit),
            )
        events: list[dict[str, Any]] = []
        for row in rows:
            try:
                payload = json.loads(cipher.decrypt(str(row["encrypted_payload"]).encode()).decode("utf-8"))
            except (InvalidToken, UnicodeDecodeError, json.JSONDecodeError):
                payload = {"decryption_error": "受限审计记录无法解密；请检查 TELEMETRY_ENCRYPTION_KEY 是否被更换。"}
            events.append({
                "audit_type": row["audit_type"],
                "payload": payload,
                "received_at": row["received_at"].isoformat(),
                "expires_at": row["expires_at"].isoformat(),
            })
        return {
            "available": True,
            "reason": "仅限单用户受限审计查询；不会出现在普通用户列表或 CSV 导出中。",
            "events": events,
            "ip_fingerprints": [
                {
                    "ip_fingerprint": row["ip_fingerprint"],
                    "first_seen_at": row["first_seen_at"].isoformat(),
                    "last_seen_at": row["last_seen_at"].isoformat(),
                    "expires_at": row["expires_at"].isoformat(),
                }
                for row in ip_fingerprints
            ],
            "retention": {"raw_ip_days": 14, "raw_appsflyer_days": 30, "ip_fingerprint_days": 180},
        }

    def admin_users(
        self,
        authorization: str | None,
        *,
        query: str = "",
        market_code: str = "",
        attribution_surface: str = "",
        geo_country: str = "",
        created_start: date | None = None,
        created_end: date | None = None,
        limit: int = 50,
        offset: int = 0,
    ) -> dict[str, Any]:
        """Return a bounded, token-protected operational view of anonymous Rewards users."""

        self._verify_admin_token(authorization)
        actual_limit = min(max(int(limit), 1), 10_000)
        actual_offset = max(int(offset), 0)
        clauses: list[str] = []
        params: list[Any] = []
        normalized_query = query.strip()
        normalized_market = market_code.strip().upper()
        normalized_surface = attribution_surface.strip().upper()
        normalized_country = geo_country.strip().upper()
        if normalized_query:
            clauses.append(
                "(u.user_id ILIKE %s OR u.appsflyer_uid ILIKE %s OR u.attribution_media_source ILIKE %s OR u.attribution_campaign ILIKE %s)"
            )
            wildcard = f"%{normalized_query[:160]}%"
            params.extend([wildcard, wildcard, wildcard, wildcard])
        if normalized_market:
            clauses.append("u.market_code = %s")
            params.append(normalized_market)
        if normalized_surface:
            if normalized_surface not in {"A", "B"}:
                raise ServiceError(2312, "归因面仅支持 A 或 B", 422)
            clauses.append("u.attribution_surface = %s")
            params.append(normalized_surface)
        if normalized_country:
            clauses.append("u.geo_country = %s")
            params.append(normalized_country[:2])
        if created_start:
            clauses.append("u.created_at >= %s")
            params.append(self._utc_start_of_day(created_start))
        if created_end:
            clauses.append("u.created_at < %s")
            params.append(self._utc_start_of_day(created_end + timedelta(days=1)))
        where = f" WHERE {' AND '.join(clauses)}" if clauses else ""
        with self._transaction() as connection:
            total = self._one(
                connection,
                f"SELECT COUNT(*) AS count FROM users u{where}",
                tuple(params),
            ) or {"count": 0}
            rows = self._all(
                connection,
                f"""
                SELECT
                    u.user_id, u.market_code, u.geo_country, u.cash_wallet_micros, u.available_balance_micros,
                    u.total_gems_earned, u.created_at, u.updated_at,
                    u.appsflyer_uid, u.attribution_status, u.attribution_media_source,
                    u.attribution_campaign, u.attribution_channel, u.attribution_surface,
                    u.attribution_first_recorded_at, u.attribution_updated_at,
                    m.currency_code, m.currency_symbol, m.currency_fraction_digits,
                    COALESCE(w.watch_count, 0) AS watch_count,
                    COALESCE(r.rewarded_count, 0) AS rewarded_count,
                    COALESCE(i.interstitial_count, 0) AS interstitial_count,
                    COALESCE(l.ledger_count, 0) AS ledger_count
                FROM users u
                JOIN market_wallet_policy m ON m.market_code = COALESCE(NULLIF(u.market_code, ''), 'DEFAULT')
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS watch_count FROM watch_events WHERE user_id = u.user_id
                ) w ON TRUE
                LEFT JOIN LATERAL (
                    -- A rewarded-to-interstitial fallback creates an authorization row in
                    -- rewarded_ad_events so its cash settlement stays idempotent. It is not an
                    -- actual rewarded-video impression and must not be counted as one beside
                    -- the same interstitial in the operator-facing user list.
                    SELECT COUNT(*) AS rewarded_count
                    FROM rewarded_ad_events
                    WHERE user_id = u.user_id
                      AND verification_method IN ('client_reward_callback', 'debug_test_callback')
                ) r ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS interstitial_count FROM interstitial_events WHERE user_id = u.user_id
                ) i ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS ledger_count FROM ledger_entries WHERE user_id = u.user_id
                ) l ON TRUE
                {where}
                ORDER BY u.updated_at DESC, u.user_id ASC
                LIMIT %s OFFSET %s
                """,
                tuple(params + [actual_limit, actual_offset]),
            )
        def serialize(row: dict[str, Any]) -> dict[str, Any]:
            wallet_policy = row
            return {
                "user_id": row["user_id"],
                "market_code": row["market_code"],
                "geo_country": row["geo_country"] or "",
                "currency": {"code": row["currency_code"], "symbol": row["currency_symbol"]},
                "wallet": {
                    "cash_wallet": self._money(int(row["cash_wallet_micros"]), wallet_policy),
                    "available_balance": self._money(int(row["available_balance_micros"]), wallet_policy),
                    "reward_progress_units": int(row["total_gems_earned"]),
                },
                "activity": {
                    "watch_count": int(row["watch_count"]),
                    "rewarded_count": int(row["rewarded_count"]),
                    "interstitial_count": int(row["interstitial_count"]),
                    "ledger_count": int(row["ledger_count"]),
                },
                "attribution": self._attribution_payload(row),
                "created_at": row["created_at"].isoformat(),
                "updated_at": row["updated_at"].isoformat(),
            }
        return {
            "total": int(total["count"]),
            "limit": actual_limit,
            "offset": actual_offset,
            "rows": [serialize(row) for row in rows],
        }


    def admin_user_metrics(
        self,
        authorization: str | None,
        *,
        query: str = "",
        market_code: str = "",
        attribution_surface: str = "",
        geo_country: str = "",
        start_date: date | None = None,
        end_date: date | None = None,
    ) -> dict[str, Any]:
        """Return operator-facing registration and daily active-user metrics.

        A daily counter is created during every authenticated bootstrap and is
        subsequently updated by viewing, advertising and reward actions. It is
        therefore the durable active-day source instead of a volatile browser
        session or the mutable ``users.updated_at`` timestamp.
        """

        self._verify_admin_token(authorization)
        # Operator registration reports reconcile against AppsFlyer on a UTC+0 calendar,
        # independently of any legacy reward-day timezone configuration.
        actual_end = end_date or datetime.now(UTC).date()
        actual_start = start_date or actual_end - timedelta(days=6)
        if actual_start > actual_end:
            raise ServiceError(2316, "用户统计开始日期不能晚于结束日期", 422)
        if actual_end - actual_start > timedelta(days=365):
            raise ServiceError(2317, "用户日统计最大查询范围为 365 天", 422)

        clauses: list[str] = []
        params: list[Any] = []
        normalized_query = query.strip()
        normalized_market = market_code.strip().upper()
        normalized_surface = attribution_surface.strip().upper()
        normalized_country = geo_country.strip().upper()
        if normalized_query:
            clauses.append(
                "(u.user_id ILIKE %s OR u.appsflyer_uid ILIKE %s OR u.attribution_media_source ILIKE %s OR u.attribution_campaign ILIKE %s)"
            )
            wildcard = f"%{normalized_query[:160]}%"
            params.extend([wildcard, wildcard, wildcard, wildcard])
        if normalized_market:
            clauses.append("u.market_code = %s")
            params.append(normalized_market)
        if normalized_surface:
            if normalized_surface not in {"A", "B"}:
                raise ServiceError(2312, "归因面仅支持 A 或 B", 422)
            clauses.append("u.attribution_surface = %s")
            params.append(normalized_surface)
        if normalized_country:
            clauses.append("u.geo_country = %s")
            params.append(normalized_country[:2])
        user_where = f" WHERE {' AND '.join(clauses)}" if clauses else ""
        registration_start = self._utc_start_of_day(actual_start)
        registration_end = self._utc_start_of_day(actual_end + timedelta(days=1))
        registration_filters = list(clauses) + ["u.created_at >= %s", "u.created_at < %s"]
        registration_where = " WHERE " + " AND ".join(registration_filters)
        active_filters = ["dc.business_date BETWEEN %s AND %s"] + list(clauses)
        active_where = " WHERE " + " AND ".join(active_filters)

        with self._transaction() as connection:
            total = self._one(
                connection,
                f"SELECT COUNT(*) AS count FROM users u{user_where}",
                tuple(params),
            ) or {"count": 0}
            registrations = self._all(
                connection,
                f"""
                SELECT (u.created_at AT TIME ZONE 'UTC')::date AS metric_date, COUNT(*) AS registration_count
                FROM users u
                {registration_where}
                GROUP BY (u.created_at AT TIME ZONE 'UTC')::date
                """,
                tuple(params + [registration_start, registration_end]),
            )
            active_users = self._all(
                connection,
                f"""
                SELECT dc.business_date AS metric_date, COUNT(DISTINCT dc.user_id) AS daily_active_users
                FROM daily_counters dc
                JOIN users u ON u.user_id = dc.user_id
                {active_where}
                GROUP BY dc.business_date
                """,
                tuple([actual_start, actual_end] + params),
            )
            funnel = self._one(
                connection,
                f"""
                WITH cohort AS (
                    SELECT u.user_id, u.attribution_status, u.attribution_surface
                    FROM users u
                    {registration_where}
                ), behaviour AS (
                    SELECT
                        c.user_id,
                        c.attribution_status,
                        c.attribution_surface,
                        COALESCE(rewarded.completed_count, 0) AS rewarded_completed_count,
                        COALESCE(rewarded.welcome_count, 0) AS welcome_reward_count,
                        COALESCE(watched.watch_count, 0) AS watch_count,
                        COALESCE(interstitial.interstitial_count, 0) AS interstitial_count,
                        COALESCE(checkin.checkin_count, 0) AS checkin_count
                    FROM cohort c
                    LEFT JOIN LATERAL (
                        SELECT
                            COUNT(*) FILTER (
                                WHERE verification_method IN ('client_reward_callback', 'debug_test_callback')
                            ) AS completed_count,
                            COUNT(*) FILTER (
                                WHERE placement_id = 'rv_welcome_bonus'
                                  AND verification_method IN ('client_reward_callback', 'debug_test_callback')
                            ) AS welcome_count
                        FROM rewarded_ad_events r
                        WHERE r.user_id = c.user_id
                    ) rewarded ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS watch_count
                        FROM watch_events w
                        WHERE w.user_id = c.user_id
                    ) watched ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS interstitial_count
                        FROM interstitial_events i
                        WHERE i.user_id = c.user_id
                    ) interstitial ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS checkin_count
                        FROM checkins ci
                        WHERE ci.user_id = c.user_id
                    ) checkin ON TRUE
                )
                SELECT
                    COUNT(*) AS cohort_users,
                    COUNT(*) FILTER (WHERE COALESCE(BTRIM(attribution_status), '') <> '') AS attribution_recorded_users,
                    COUNT(*) FILTER (WHERE attribution_surface = 'A') AS organic_a_users,
                    COUNT(*) FILTER (WHERE attribution_surface = 'B') AS attributed_b_users,
                    COUNT(*) FILTER (WHERE COALESCE(BTRIM(attribution_status), '') = '') AS attribution_pending_users,
                    COUNT(*) FILTER (WHERE welcome_reward_count > 0) AS welcome_reward_completed_users,
                    COUNT(*) FILTER (WHERE rewarded_completed_count > 0) AS rewarded_completed_users,
                    COUNT(*) FILTER (WHERE watch_count > 0) AS valid_watch_users,
                    COUNT(*) FILTER (WHERE interstitial_count > 0) AS interstitial_users,
                    COUNT(*) FILTER (WHERE checkin_count > 0) AS checkin_users,
                    COUNT(*) FILTER (
                        WHERE rewarded_completed_count = 0
                          AND watch_count = 0
                          AND interstitial_count = 0
                          AND checkin_count = 0
                    ) AS no_recorded_action_users,
                    COUNT(*) FILTER (WHERE rewarded_completed_count = 0) AS rewarded_zero_users,
                    COUNT(*) FILTER (WHERE rewarded_completed_count = 1) AS rewarded_once_users,
                    COUNT(*) FILTER (WHERE rewarded_completed_count = 2) AS rewarded_twice_users,
                    COUNT(*) FILTER (WHERE rewarded_completed_count >= 3) AS rewarded_three_or_more_users
                FROM behaviour
                """,
                tuple(params + [registration_start, registration_end]),
            ) or {}

        registration_by_day = {row["metric_date"]: int(row["registration_count"]) for row in registrations}
        active_by_day = {row["metric_date"]: int(row["daily_active_users"]) for row in active_users}
        daily: list[dict[str, Any]] = []
        cursor = actual_start
        while cursor <= actual_end:
            daily.append({
                "metric_date": cursor.isoformat(),
                "registration_count": registration_by_day.get(cursor, 0),
                "daily_active_users": active_by_day.get(cursor, 0),
            })
            cursor += timedelta(days=1)
        return {
            "total_users": int(total["count"]),
            "start_date": actual_start.isoformat(),
            "end_date": actual_end.isoformat(),
            "daily": daily,
            "definition": "注册、日活和 cohort 统计均按 UTC+0 自然日；日活用户指当日已创建或更新 Rewards 每日计数器的匿名用户。",
            "funnel": {
                "cohort_users": int(funnel.get("cohort_users", 0)),
                "attribution_recorded_users": int(funnel.get("attribution_recorded_users", 0)),
                "organic_a_users": int(funnel.get("organic_a_users", 0)),
                "attributed_b_users": int(funnel.get("attributed_b_users", 0)),
                "attribution_pending_users": int(funnel.get("attribution_pending_users", 0)),
                "welcome_reward_completed_users": int(funnel.get("welcome_reward_completed_users", 0)),
                "rewarded_completed_users": int(funnel.get("rewarded_completed_users", 0)),
                "valid_watch_users": int(funnel.get("valid_watch_users", 0)),
                "interstitial_users": int(funnel.get("interstitial_users", 0)),
                "checkin_users": int(funnel.get("checkin_users", 0)),
                "no_recorded_action_users": int(funnel.get("no_recorded_action_users", 0)),
                "rewarded_distribution": {
                    "zero": int(funnel.get("rewarded_zero_users", 0)),
                    "once": int(funnel.get("rewarded_once_users", 0)),
                    "twice": int(funnel.get("rewarded_twice_users", 0)),
                    "three_or_more": int(funnel.get("rewarded_three_or_more_users", 0)),
                },
                "definition": "以所选 UTC+0 注册日期内创建的用户为 cohort，行为为该 cohort 截至查询时已成功写入 Rewards 的累计业务记录；不等同于客户端页面曝光、广告请求或未成功送达服务端的行为。",
            },
        }

    def admin_reporting_delivery_metrics(
        self,
        authorization: str | None,
        start_date: date | None = None,
        end_date: date | None = None,
        page: int = 1,
        page_size: int = 100,
        provider: str = "",
        event_name: str = "",
        status: str = "",
        user_uid: str = "",
        logical_placement: str = "",
        ad_unit_id: str = "",
    ) -> dict[str, Any]:
        """Return recent AppsFlyer/Firebase/Xuanyu delivery results retained by Rewards."""

        self._verify_admin_token(authorization)
        default_end = datetime.now(UTC).date()
        actual_end = end_date or default_end
        actual_start = start_date or actual_end - timedelta(days=6)
        if actual_start > actual_end:
            raise ServiceError(2313, "上报记录开始日期不能晚于结束日期", 422)
        if actual_end - actual_start > timedelta(days=90):
            raise ServiceError(2314, "上报记录最大查询范围为 90 天", 422)
        actual_page = max(int(page), 1)
        actual_limit = min(max(int(page_size), 1), 10_000)
        actual_offset = (actual_page - 1) * actual_limit
        filters = ["client_occurred_at >= %s", "client_occurred_at < %s"]
        filter_params: list[Any] = [
            self._utc_start_of_day(actual_start),
            self._utc_start_of_day(actual_end + timedelta(days=1)),
        ]
        for column, value in (("provider", provider), ("event_name", event_name), ("status", status), ("logical_placement", logical_placement), ("ad_unit_id", ad_unit_id)):
            normalized = value.strip()
            if normalized:
                filters.append(f"{column} = %s")
                filter_params.append(normalized[:160])
        if user_uid.strip():
            filters.append("user_uid ILIKE %s")
            filter_params.append(f"%{user_uid.strip()[:128]}%")
        where = " WHERE " + " AND ".join(filters)
        with self._transaction() as connection:
            rows = self._all(
                connection,
                f"""
                SELECT user_uid, provider, event_name, status, logical_placement, ad_unit_id,
                       error_code, currency, ecpm, reported_value, client_occurred_at, accepted_at
                FROM reporting_delivery_events
                {where}
                ORDER BY client_occurred_at DESC, accepted_at DESC
                LIMIT %s OFFSET %s
                """,
                tuple(filter_params + [actual_limit, actual_offset]),
            )
            totals = self._one(
                connection,
                f"""
                SELECT
                    COUNT(*) AS event_count,
                    COUNT(*) FILTER (WHERE status = 'SUCCESS') AS success_count,
                    COUNT(*) FILTER (WHERE status IN ('FAILED', 'FINAL_FAILED')) AS failure_count,
                    COUNT(*) FILTER (WHERE status = 'RETRYING') AS retrying_count
                FROM reporting_delivery_events
                {where}
                """,
                tuple(filter_params),
            ) or {}
            breakdown = self._all(
                connection,
                f"""
                SELECT provider, status, COUNT(*) AS event_count
                FROM reporting_delivery_events
                {where}
                GROUP BY provider, status
                ORDER BY provider ASC, status ASC
                """,
                tuple(filter_params),
            )
        def serialize(row: dict[str, Any]) -> dict[str, Any]:
            return {
                **row,
                "currency": row["currency"].strip(),
                "ecpm": format(Decimal(str(row["ecpm"])), "f") if row["ecpm"] is not None else "",
                "value": format(Decimal(str(row["reported_value"])), "f"),
                "client_occurred_at": row["client_occurred_at"].isoformat(),
                "accepted_at": row["accepted_at"].isoformat(),
            }
        return {
            "start_date": actual_start.isoformat(),
            "end_date": actual_end.isoformat(),
            "page": actual_page,
            "page_size": actual_limit,
            "offset": actual_offset,
            "total": int(totals.get("event_count", 0)),
            "filters": {"provider": provider, "event_name": event_name, "status": status, "user_uid": user_uid, "logical_placement": logical_placement, "ad_unit_id": ad_unit_id},
            "totals": {key: int(totals.get(key, 0)) for key in ("event_count", "success_count", "failure_count", "retrying_count")},
            "breakdown": [dict(item) | {"event_count": int(item["event_count"])} for item in breakdown],
            "rows": [serialize(row) for row in rows],
            "disclaimer": "仅记录客户端回调观察到的投递状态；AppsFlyer、Firebase 与炫羽各后台仍是最终接收与归因事实来源。",
        }

    def settle_rewarded_fallback(
        self,
        *,
        user_id: str,
        interstitial_event_id: str,
        original_placement_id: str,
        target_id: str | None,
        episode_index: int | None,
        expected_interstitial_placement: str = "int_rewarded_fallback",
        verification_method: str = "interstitial_fallback",
        authorization_prefix: str = "fallback",
    ) -> dict[str, Any]:
        """Settle an incentive action only after an actual, auditable interstitial.

        This path is intentionally separate from ``complete_rewarded_ad``. It
        requires the expected recorded interstitial and preserves an auditable,
        idempotent link between that verified display and the original action.
        The existing fallback ledger is shared with A-surface incentive displays
        so all non-rewarded verification remains in one one-time grant ledger.
        """
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            business_date = self._business_date()
            existing = self._one(
                connection,
                "SELECT result_payload FROM rewarded_fallback_grants WHERE interstitial_event_id = %s",
                (interstitial_event_id,),
            )
            if existing:
                return {"idempotent": True, "result": existing["result_payload"], "snapshot": self._snapshot(connection, user_id)}

            interstitial = self._one(
                connection,
                """SELECT placement_id FROM interstitial_events
                   WHERE event_id = %s AND user_id = %s FOR UPDATE""",
                (interstitial_event_id, user_id),
            )
            if interstitial and interstitial["placement_id"] != expected_interstitial_placement:
                raise ServiceError(2023, "未找到可结算的插屏激励展示", 409)
            if not interstitial:
                # Never let a client manufacture a displayed interstitial by choosing a
                # fresh event ID. The client must first record the actual shown event;
                # a lost telemetry request is retryable, not proof of an impression.
                raise ServiceError(2023, "未找到可结算的插屏激励展示", 409)
            policy = self._policy(connection)
            if not bool(policy["rewarded_fallback_interstitial_enabled"]):
                raise ServiceError(2020, "激励回退插屏当前未启用", 409)
            result = self._rewarded_action(
                connection=connection,
                user_id=user_id,
                business_date=business_date,
                placement_id=original_placement_id,
                target_id=target_id,
                episode_index=episode_index,
                policy=policy,
            )
            fallback_ad_event_id = f"{authorization_prefix}-{interstitial_event_id}"
            # Downstream actions such as the aggregated episode-reward claim
            # must use the rewarded-event identifier that created the server
            # authorization, not the interstitial telemetry identifier.
            result["ad_event_id"] = fallback_ad_event_id
            self._execute(
                connection,
                """INSERT INTO rewarded_ad_events
                   (ad_event_id, user_id, business_date, placement_id, target_id, ad_timestamp, verification_method, result_payload)
                   VALUES (%s, %s, %s, %s, %s, NOW(), %s, %s::jsonb)""",
                (fallback_ad_event_id, user_id, business_date, original_placement_id, target_id, verification_method, self._json(result)),
            )
            credited_units = self._settle_rewarded_payload(
                connection=connection,
                user_id=user_id,
                business_date=business_date,
                placement_id=original_placement_id,
                target_id=target_id,
                ad_event_id=fallback_ad_event_id,
                payload=result,
            )
            if credited_units is not None:
                wallet_policy = self._wallet_policy(connection, user_id)
                result["gems_added"] = credited_units
                result["cash_added"] = self._money(self._reward_units_to_micros(credited_units, wallet_policy), wallet_policy)
                result["cash_added_usd_reference"] = self._usd_reference_money(self._reward_units_to_micros(credited_units, wallet_policy), wallet_policy)
            result["interstitial_incentive_settled"] = True
            result["interstitial_placement_id"] = expected_interstitial_placement
            self._execute(
                connection,
                """UPDATE daily_counters
                   SET rv_count = rv_count + 1,
                       last_rv_at = NOW(),
                       rewarded_decline_count = 0,
                       last_rewarded_declined_at = NULL
                   WHERE user_id = %s AND business_date = %s""",
                (user_id, business_date),
            )
            self._execute(
                connection,
                """INSERT INTO rewarded_fallback_grants
                   (interstitial_event_id, user_id, original_placement_id, target_id, episode_index, result_payload)
                   VALUES (%s, %s, %s, %s, %s, %s::jsonb)""",
                (interstitial_event_id, user_id, original_placement_id, target_id, episode_index, self._json(result)),
            )
            achievements = self._award_achievements_if_due(connection, user_id, policy)
            result["achievements_awarded"] = achievements
            achievement_units = sum(int(item["gems"]) for item in achievements)
            if achievement_units > 0:
                wallet_policy = self._wallet_policy(connection, user_id)
                total_units = int(result.get("gems_added", 0)) + achievement_units
                total_micros = self._reward_units_to_micros(total_units, wallet_policy)
                result["gems_added"] = total_units
                result["cash_added"] = self._money(total_micros, wallet_policy)
                result["cash_added_usd_reference"] = self._usd_reference_money(total_micros, wallet_policy)
            # Update both idempotency records after all reward components are known.
            self._execute(
                connection,
                "UPDATE rewarded_ad_events SET result_payload = %s::jsonb WHERE ad_event_id = %s",
                (self._json(result), fallback_ad_event_id),
            )
            self._execute(
                connection,
                "UPDATE rewarded_fallback_grants SET result_payload = %s::jsonb WHERE interstitial_event_id = %s",
                (self._json(result), interstitial_event_id),
            )
            return {"idempotent": False, "result": result, "snapshot": self._snapshot(connection, user_id)}

    def settle_interstitial_incentive(
        self,
        *,
        user_id: str,
        interstitial_event_id: str,
        original_placement_id: str,
        target_id: str | None,
        episode_index: int | None,
    ) -> dict[str, Any]:
        """Organic A surface: grant after the configured incentive interstitial closes.

        This is not a rewarded-ad verification bypass. The original action is
        settled only when the client has recorded the A-surface interstitial as
        shown, and receives its own authorization ID for downstream claims.
        """
        if original_placement_id == "rv_episode_pass":
            # Episode access is intentionally excluded from the Organic A substitution:
            # it must remain an explicitly requested rewarded-video unlock on both surfaces.
            raise ServiceError(2024, "剧集解锁必须通过激励广告完成", 409)
        return self.settle_rewarded_fallback(
            user_id=user_id,
            interstitial_event_id=interstitial_event_id,
            original_placement_id=original_placement_id,
            target_id=target_id,
            episode_index=episode_index,
            expected_interstitial_placement="int_incentive_action",
            verification_method="interstitial_incentive",
            authorization_prefix="interstitial-incentive",
        )

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

    def save_withdrawal_account(self, *, user_id: str, provider: str, account_identifier: str) -> dict[str, Any]:
        normalized_provider = provider.strip().upper()
        identifier = account_identifier.strip()
        if normalized_provider not in {"PAYPAL", "STRIPE", "LINK"}:
            raise ServiceError(2410, "提现通道不受支持", 422)
        if len(identifier) < 3:
            raise ServiceError(2411, "提现账户信息过短", 422)
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            self._execute(connection, """
                INSERT INTO withdrawal_accounts (user_id, provider, account_identifier)
                VALUES (%s, %s, %s)
                ON CONFLICT (user_id) DO UPDATE SET provider = EXCLUDED.provider,
                    account_identifier = EXCLUDED.account_identifier, updated_at = NOW()
            """, (user_id, normalized_provider, identifier))
            return {"provider": normalized_provider, "account_identifier": identifier, "linked": True}

    def create_withdrawal_request(self, *, user_id: str, threshold: str, client_request_id: str | None = None) -> dict[str, Any]:
        with self._transaction() as connection:
            self._ensure_user(connection, user_id)
            policy = self._policy(connection)
            wallet_policy = self._wallet_policy(connection, user_id)
            # Pre-v1.7.1.6 clients do not send a key. Keep their UI compatible while
            # enforcing the reservation lock; new clients always supply a stable key.
            request_key = client_request_id.strip() if client_request_id else f"legacy-withdrawal-{secrets.token_urlsafe(18)}"
            # Serialise both the available-balance decision and duplicate request-key check.
            user = self._one(connection, "SELECT cash_wallet_micros FROM users WHERE user_id = %s FOR UPDATE", (user_id,))
            existing = self._one(
                connection,
                """SELECT request_id, provider, amount_micros, status, created_at
                FROM withdrawal_requests WHERE user_id = %s AND client_request_id = %s""",
                (user_id, request_key),
            )
            if existing:
                return {
                    "idempotent": True, "request_id": str(existing["request_id"]), "provider": existing["provider"],
                    "amount": self._money(int(existing["amount_micros"]), wallet_policy), "status": existing["status"],
                    "created_at": existing["created_at"].isoformat(),
                }
            account = self._one(connection, "SELECT provider FROM withdrawal_accounts WHERE user_id = %s", (user_id,))
            if not account:
                raise ServiceError(2412, "请先关联提现账户", 409)
            try:
                requested_amount = Decimal(threshold)
            except Exception as error:
                raise ServiceError(2413, "提现金额格式无效", 422) from error
            if not requested_amount.is_finite() or requested_amount <= 0:
                raise ServiceError(2413, "提现金额格式无效", 422)
            fraction_digits = int(wallet_policy["currency_fraction_digits"])
            smallest_unit = Decimal(10) ** -fraction_digits
            if requested_amount != requested_amount.quantize(smallest_unit, rounding=ROUND_DOWN):
                raise ServiceError(2413, "提现金额的小数位不符合当前地区币种规则", 422)
            amount_micros = int((requested_amount * Decimal(1_000_000)).quantize(Decimal("1"), rounding=ROUND_DOWN))
            minimum = int(wallet_policy["withdrawal_threshold_micros"])
            balance = int(user["cash_wallet_micros"])
            if not bool(policy["withdrawal_enabled"]):
                raise ServiceError(2414, "提现功能当前未启用", 409)
            if amount_micros < minimum or balance < amount_micros:
                raise ServiceError(2415, "余额未达到该提现目标", 409)
            # The row lock and in-transaction debit make the same balance unavailable to a
            # later pending request. Rejection/cancellation must restore this reservation.
            self._execute(
                connection,
                "UPDATE users SET cash_wallet_micros = cash_wallet_micros - %s, updated_at = NOW() WHERE user_id = %s",
                (amount_micros, user_id),
            )
            row = self._one(connection, """
                INSERT INTO withdrawal_requests (
                    user_id, provider, amount_micros, client_request_id, currency_code,
                    currency_fraction_digits, market_code, reserved_at
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, NOW())
                RETURNING request_id, status, created_at
            """, (
                user_id, account["provider"], amount_micros, request_key, wallet_policy["currency_code"],
                fraction_digits, wallet_policy["market_code"],
            ))
            return {"idempotent": False, "request_id": str(row["request_id"]), "provider": account["provider"],
                    "amount": self._money(amount_micros, wallet_policy), "status": row["status"],
                    "created_at": row["created_at"].isoformat()}

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
            streak_day = previous["streak_day"] % CHECKIN_CYCLE_DAYS + 1 if previous and previous["business_date"] == business_date - timedelta(days=1) else 1
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

        if placement_id == "rv_episode_task_claim":
            if not target_id:
                raise ServiceError(1225, "追剧任务领取缺少任务节点", 422)
            try:
                milestone = int(target_id)
            except ValueError as error:
                raise ServiceError(1225, "追剧任务节点无效", 422) from error
            daily_episode_count = int(self._daily_counter(connection, user_id, business_date, lock=True)["episode_count"])
            self._ensure_episode_task_claims(connection, user_id, business_date, daily_episode_count)
            if milestone not in self._task_milestones(policy):
                raise ServiceError(1221, "该追剧任务不存在", 404)
            if daily_episode_count < milestone:
                raise ServiceError(1222, "追剧任务尚未完成", 409)
            claim = self._one(
                connection,
                """SELECT state FROM episode_task_claims
                   WHERE user_id = %s AND business_date = %s AND milestone_episode_count = %s FOR UPDATE""",
                (user_id, business_date, milestone),
            )
            if not claim or claim["state"] != "PENDING":
                raise ServiceError(1223, "该追剧任务已领取或不可用", 409)
            return {
                "action": "EPISODE_TASK_REDPACKET",
                "milestone": milestone,
                "gems_added": self._random_reward_units(
                    *self._task_reward_bounds(policy, milestone),
                ),
            }

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
            # Single-wallet mode: each wallet acceleration is a random cash redpacket,
            # not a visible conversion or fixed proportion of an episode reward.
            reward_units = self._random_reward_units(
                int(policy["wallet_redpacket_min_gems"]),
                int(policy["wallet_redpacket_max_gems"]),
            )
            return {
                "action": "WALLET_CASH_BOOST",
                "gems_added": reward_units,
                "released_micros": 0,
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
        event = self._one(connection, "SELECT result_payload FROM rewarded_ad_events WHERE ad_event_id = %s", (ad_event_id,))
        return self._settle_rewarded_payload(
            connection=connection,
            user_id=user_id,
            business_date=business_date,
            placement_id=placement_id,
            target_id=target_id,
            ad_event_id=ad_event_id,
            payload=event["result_payload"],
        )

    def _settle_rewarded_payload(
        self,
        *,
        connection: psycopg.Connection[Any],
        user_id: str,
        business_date: date,
        placement_id: str,
        target_id: str | None,
        ad_event_id: str,
        payload: dict[str, Any],
    ) -> int | None:
        policy = self._policy(connection)
        action = payload["action"]
        if action == "EPISODE_PASS":
            # 广告只授予内容通行权益；观看完成后才会形成独立的待领取宝石。
            # Keep the entitlement server-side so a process restart cannot relock paid views.
            self._grant_episode_unlock_entitlements(
                connection,
                user_id,
                str(payload["episode_id"]),
                int(payload.get("unlock_episode_count", 1)),
                "AD_REWARDED",
                ad_event_id,
            )
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
        elif action == "EPISODE_TASK_REDPACKET":
            updated = self._execute(
                connection,
                """UPDATE episode_task_claims SET state = 'CLAIMED', claimed_at = NOW()
                   WHERE user_id = %s AND business_date = %s AND milestone_episode_count = %s AND state = 'PENDING'""",
                (user_id, business_date, int(payload["milestone"])),
            )
            if updated != 1:
                raise ServiceError(1223, "该追剧任务已领取或不可用", 409)
            return self._credit_gems(
                connection, user_id, int(payload["gems_added"]), policy,
                "EPISODE_TASK_REDPACKET", "episode_task", f"{user_id}:{business_date.isoformat()}:{payload['milestone']}",
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

    @staticmethod
    def _random_reward_units(minimum: int, maximum: int) -> int:
        if minimum < 0 or maximum < minimum:
            raise ServiceError(1904, "随机红包最小奖励不能大于最大奖励", 422)
        return minimum + secrets.randbelow(maximum - minimum + 1)

    @staticmethod
    def _task_milestones(policy: dict[str, Any]) -> list[int]:
        maximum = min(90, max(18, int(policy.get("episode_task_max_episode", 90))))
        interval = max(1, int(policy.get("episode_task_post18_interval", 6)))
        milestones = [1, 3, 6, 9, 12, 15, 18] + list(range(18 + interval, maximum + 1, interval))
        if milestones[-1] != maximum:
            milestones.append(maximum)
        return milestones

    @staticmethod
    def _task_reward_bounds(policy: dict[str, Any], milestone: int) -> tuple[int, int]:
        if milestone <= 1:
            tier = 1
        elif milestone <= 9:
            tier = 2
        elif milestone <= 15:
            tier = 3
        else:
            tier = 4
        minimum = int(policy.get(f"episode_task_tier{tier}_min_gems", policy["episode_task_redpacket_min_gems"]))
        maximum = int(policy.get(f"episode_task_tier{tier}_max_gems", policy["episode_task_redpacket_max_gems"]))
        if minimum > maximum:
            raise ServiceError(1904, "追剧任务随机红包最小奖励不能大于最大奖励", 422)
        return minimum, maximum

    def _sync_episode_task_policy(self, connection: psycopg.Connection[Any], policy: dict[str, Any]) -> list[int]:
        milestones = self._task_milestones(policy)
        for sort_order, milestone in enumerate(milestones, start=1):
            self._execute(
                connection,
                """INSERT INTO episode_task_policy (milestone_episode_count, reward_gems, sort_order)
                   VALUES (%s, %s, %s)
                   ON CONFLICT (milestone_episode_count) DO UPDATE
                   SET reward_gems = EXCLUDED.reward_gems, sort_order = EXCLUDED.sort_order""",
                (milestone, self._task_reward_bounds(policy, milestone)[0], sort_order),
            )
        return milestones

    def _ensure_episode_task_claims(
        self,
        connection: psycopg.Connection[Any],
        user_id: str,
        business_date: date,
        daily_episode_count: int,
    ) -> None:
        """Materialise today's claimable milestones; repeat calls are safe."""
        policy = self._policy(connection)
        for milestone in self._sync_episode_task_policy(connection, policy):
            if milestone <= daily_episode_count:
                self._execute(
                    connection,
                    """INSERT INTO episode_task_claims (user_id, business_date, milestone_episode_count)
                       VALUES (%s, %s, %s) ON CONFLICT (user_id, business_date, milestone_episode_count) DO NOTHING""",
                    (user_id, business_date, milestone),
                )

    def _episode_task_progress(
        self,
        connection: psycopg.Connection[Any],
        user_id: str,
        business_date: date,
        daily_episode_count: int,
        wallet_policy: dict[str, Any],
    ) -> list[dict[str, Any]]:
        self._ensure_episode_task_claims(connection, user_id, business_date, daily_episode_count)
        policy = self._policy(connection)
        milestones = self._task_milestones(policy)
        rows = self._all(
            connection,
            """
            SELECT p.milestone_episode_count, p.reward_gems, p.sort_order,
                   COALESCE(c.state, CASE WHEN p.milestone_episode_count <= %s THEN 'PENDING' ELSE 'LOCKED' END) AS state
            FROM episode_task_policy p
            LEFT JOIN episode_task_claims c
                   ON c.user_id = %s AND c.business_date = %s AND c.milestone_episode_count = p.milestone_episode_count
            WHERE p.milestone_episode_count = ANY(%s)
            ORDER BY p.sort_order
            """,
            (daily_episode_count, user_id, business_date, milestones),
        )
        progress: list[dict[str, Any]] = []
        for row in rows:
            milestone = int(row["milestone_episode_count"])
            minimum, maximum = self._task_reward_bounds(policy, milestone)
            progress.append(
                {
                "milestone_episode_count": int(row["milestone_episode_count"]),
                "reward_gems": 0,
                "reward_cash": "0.00",
                "reward_min_gems": minimum,
                "reward_max_gems": maximum,
                "reward_min_cash": self._money(self._reward_units_to_micros(minimum, wallet_policy), wallet_policy),
                "reward_max_cash": self._money(self._reward_units_to_micros(maximum, wallet_policy), wallet_policy),
                "random_redpacket": True,
                "state": row["state"],
                "progress": min(daily_episode_count, milestone),
                "target": milestone,
            }
            )
        return progress

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
            # Crown Road scoring is intentionally explicit: completed episodes add two
            # points and a Crown Road rewarded ad adds one. Other rewarded placements
            # never advance this score.
            rewarded_rounds = int(self._one(
                connection,
                "SELECT COUNT(*) AS count FROM rewarded_ad_events WHERE user_id = %s AND placement_id = 'rv_crown_upgrade' AND processed_at >= %s",
                (user_id, start),
            )["count"])
            episode_points = int(policy.get("crown_episode_progress_points", 2))
            ad_points = int(policy.get("crown_rewarded_ad_progress_points", 1))
            equivalent_rounds = rewarded_rounds * ad_points
            rounds = watched_rounds * episode_points + equivalent_rounds
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
        today_streak = completed_streak if checkin else (completed_streak % CHECKIN_CYCLE_DAYS + 1)
        today_reward = self._one(connection, "SELECT gems FROM signin_reward_tiers WHERE day_number = %s", (today_streak,))["gems"]
        tiers = self._all(
            connection,
            "SELECT day_number, gems FROM signin_reward_tiers WHERE day_number <= %s ORDER BY day_number",
            (CHECKIN_CYCLE_DAYS,),
        )
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
        unlocked_episode_rows = self._all(
            connection,
            """SELECT episode_target FROM episode_unlock_entitlements
               WHERE user_id = %s ORDER BY unlocked_at DESC""",
            (user_id,),
        )
        achievements = self._all(connection, "SELECT code, gems, virtual_entitlement, earned_at FROM user_achievements WHERE user_id = %s ORDER BY earned_at", (user_id,))
        welcome = self._one(connection, "SELECT gems, state FROM welcome_rewards WHERE user_id = %s", (user_id,))
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
        release_cap = int(wallet_policy["daily_release_micros"])
        cash_wallet_micros = int(user["cash_wallet_micros"])
        expected_boost_units = (int(policy["wallet_redpacket_min_gems"]) + int(policy["wallet_redpacket_max_gems"])) // 2
        expected_boost_transfer = self._reward_units_to_micros(expected_boost_units, wallet_policy)
        wallet_charge_cooldown_seconds = int(policy.get("release_boost_cooldown_seconds", 30))
        last_wallet_charge = self._one(
            connection,
            """SELECT processed_at FROM rewarded_ad_events
               WHERE user_id = %s AND placement_id = 'rv_release_boost'
               ORDER BY processed_at DESC LIMIT 1""",
            (user_id,),
        )
        wallet_charge_cooldown_remaining = max(
            0,
            wallet_charge_cooldown_seconds - int((self._now() - last_wallet_charge["processed_at"]).total_seconds()),
        ) if last_wallet_charge and wallet_charge_cooldown_seconds > 0 else 0
        currency = self._currency_payload(wallet_policy)
        task_rows = self._episode_task_progress(connection, user_id, business_date, int(counters["episode_count"]), wallet_policy)
        return {
            "business_date": business_date.isoformat(),
            "user": {
                "geo_tier": user["geo_tier"],
                "market_code": wallet_policy["market_code"],
                "lifecycle_day": lifecycle_day,
                "attribution": self._attribution_payload(user),
            },
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
                    "charge_cooldown_seconds": wallet_charge_cooldown_seconds,
                    "charge_cooldown_remaining_seconds": wallet_charge_cooldown_remaining,
                    # The boost is a direct cash reward and remains available until the daily cap or a short charge cooldown.
                    "boost_available": (
                        wallet_charge_cooldown_remaining <= 0
                        and (int(policy["release_boost_daily_limit"]) <= 0 or int(counters["release_boost_count"]) < int(policy["release_boost_daily_limit"]))
                    ),
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
                "episodes_per_random_chest": 0,
                "episodes_to_next_random_chest": 0,
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
                "available": False,
                "claimed": False,
                "gems": 0,
                "cash": self._money(0, wallet_policy),
            },
            "ring": {
                "enabled": True,
                "progress": len(pending_episode_rewards),
                "target": max(1, len(pending_episode_rewards)),
                "claimable": bool(pending_episode_rewards),
                "claim_prompt_threshold_gems": int(policy.get("episode_reward_claim_prompt_threshold_gems", 50_000)),
                "reward_gems": pending_reward_gems,
                "reward_cash": self._money(self._reward_units_to_micros(pending_reward_gems, wallet_policy), wallet_policy),
                "reward_cash_usd_reference": self._usd_reference_money(self._reward_units_to_micros(pending_reward_gems, wallet_policy), wallet_policy),
                "label": "领取现金奖励" if pending_episode_rewards else "追剧赚现金",
            },
            "chest": {
                "requires_checkin": False,
                "claimable": False,
                "claimed": False,
                "reward_gems": 0,
                "available_count": 0,
                "daily_available_count": 0,
                "watch_available_count": 0,
                "min_gems": 0,
                "max_gems": 0,
                "min_cash": "0.00",
                "max_cash": "0.00",
                "episodes_per_chest": 0,
                "episodes_to_next_chest": 0,
            },
            "pending_claims": task_rows,
            "episode_reward_claims": episode_reward_claims,
            "episode_reward_preboosted_ids": [item["episode_id"] for item in preboosted_episode_rows],
            "unlocked_episode_targets": [item["episode_target"] for item in unlocked_episode_rows],
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
                "mid_episode_repeat_seconds": int(policy.get("mid_episode_interstitial_repeat_seconds", 360)),
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
                "tier5_threshold": int(policy["gem_damping_tier5_threshold"]),
                "tier1_multiplier": float(policy["gem_damping_tier1_multiplier"]),
                "tier2_multiplier": float(policy["gem_damping_tier2_multiplier"]),
                "tier3_multiplier": float(policy["gem_damping_tier3_multiplier"]),
                "tier4_multiplier": float(policy["gem_damping_tier4_multiplier"]),
                "tier5_multiplier": float(policy["gem_damping_tier5_multiplier"]),
                "tier6_multiplier": float(policy["gem_damping_tier6_multiplier"]),
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
        crown_cooldown_seconds = int(reward_policy.get("crown_rewarded_cooldown_seconds", 30))
        last_crown_charge = self._one(
            connection,
            """SELECT processed_at FROM rewarded_ad_events
               WHERE user_id = %s AND placement_id = 'rv_crown_upgrade'
               ORDER BY processed_at DESC LIMIT 1""",
            (user_id,),
        )
        crown_cooldown_remaining = max(
            0,
            crown_cooldown_seconds - int((now - last_crown_charge["processed_at"]).total_seconds()),
        ) if last_crown_charge and crown_cooldown_seconds > 0 else 0
        episode_points = int(reward_policy.get("crown_episode_progress_points", 2))
        ad_points = int(reward_policy.get("crown_rewarded_ad_progress_points", 1))
        earned_codes = {item["code"] for item in self._all(connection, "SELECT code FROM user_achievements WHERE user_id = %s", (user_id,))}
        result: list[dict[str, Any]] = []
        titles = {"BRONZE": "青铜", "SILVER": "白银", "GOLD": "黄金", "DIAMOND": "钻石", "CROWN": "皇冠"}
        for row in rows:
            start = now - timedelta(hours=int(row["window_hours"]))
            watched_rounds = int(self._one(connection, "SELECT COUNT(*) AS count FROM watch_events WHERE user_id = %s AND recorded_at >= %s", (user_id, start))["count"])
            # Only completed episodes and explicit Crown Road ads produce Crown points.
            signins = 0
            rvs = int(self._one(connection, "SELECT COUNT(*) AS count FROM rewarded_ad_events WHERE user_id = %s AND placement_id = 'rv_crown_upgrade' AND processed_at >= %s", (user_id, start))["count"])
            equivalent_rounds = rvs * ad_points
            rounds = watched_rounds * episode_points + equivalent_rounds
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
                "ads_per_episode": 1,
                "episode_progress_points": episode_points,
                "rewarded_ad_progress_points": ad_points,
                "crown_rewarded_ads_today": crown_ads_today,
                "crown_rewarded_daily_limit": crown_daily_limit,
                "charge_cooldown_seconds": crown_cooldown_seconds,
                "charge_cooldown_remaining_seconds": crown_cooldown_remaining,
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
        # The app only needs to know that an account is linked and which channel to label.
        # Never place the account identifier itself in a routine bootstrap response.
        account = self._one(
            connection,
            "SELECT provider FROM withdrawal_accounts WHERE user_id = %s",
            (user_id,),
        )
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
            "account_linked": account is not None,
            "account_provider": account["provider"] if account else "",
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
        # Lock first so a concurrent delivery of the same idempotency key observes
        # the entry inserted by the first transaction rather than racing the unique key.
        user = self._one(connection, "SELECT total_gems_earned FROM users WHERE user_id = %s FOR UPDATE", (user_id,))
        existing = self._one(
            connection,
            "SELECT id FROM ledger_entries WHERE user_id = %s AND reference_type = %s AND reference_id = %s",
            (user_id, reference_type, reference_id),
        )
        if existing:
            return 0
        total_before = int(user["total_gems_earned"])
        cap = int(policy.get("gems_earned_cap", 0))
        if cap > 0 and total_before >= cap:
            credited_gems = 0
        else:
            # 欢迎、签到、分集、任务红包、钱包红包和皇冠等全部来源均在结算时适用同一六档阻尼。
            tier1 = int(policy.get("gem_damping_tier1_threshold", 0))
            tier2 = int(policy.get("gem_damping_tier2_threshold", 0))
            tier3 = int(policy.get("gem_damping_tier3_threshold", 0))
            tier4 = int(policy.get("gem_damping_tier4_threshold", 0))
            tier5 = int(policy.get("gem_damping_tier5_threshold", 0))
            if tier1 > 0 and total_before < tier1:
                multiplier = Decimal(str(policy.get("gem_damping_tier1_multiplier", 1.0)))
            elif tier2 > 0 and total_before < tier2:
                multiplier = Decimal(str(policy.get("gem_damping_tier2_multiplier", 1.0)))
            elif tier3 > 0 and total_before < tier3:
                multiplier = Decimal(str(policy.get("gem_damping_tier3_multiplier", 1.0)))
            elif tier4 > 0 and total_before < tier4:
                multiplier = Decimal(str(policy.get("gem_damping_tier4_multiplier", 1.0)))
            elif tier5 > 0 and total_before < tier5:
                multiplier = Decimal(str(policy.get("gem_damping_tier5_multiplier", policy.get("gem_damping_tier4_multiplier", 1.0))))
            else:
                multiplier = Decimal(str(policy.get("gem_damping_tier6_multiplier", policy.get("gem_damping_tier5_multiplier", 1.0))))
            credited_gems = max(0, int(Decimal(gems) * multiplier))
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
        aliases = {"BRA": "BR", "BRAZIL": "BR", "PT_BR": "BR", "IDN": "ID", "INDONESIA": "ID", "IN_ID": "ID"}
        return aliases.get(value, value if value and value != "DEFAULT" else "DEFAULT")

    def _update_attribution(
        self,
        connection: psycopg.Connection[Any],
        *,
        user_id: str,
        appsflyer_uid: str | None,
        af_status: str,
        media_source: str | None,
        campaign: str | None,
        channel: str | None,
    ) -> None:
        status = af_status.strip()
        if not status:
            raise ServiceError(1400, "归因状态不能为空", 422)
        surface = "B" if status.lower() == "non-organic" else "A"
        self._execute(
            connection,
            """
            UPDATE users
            SET appsflyer_uid = COALESCE(NULLIF(%s, ''), appsflyer_uid),
                attribution_status = %s,
                attribution_media_source = COALESCE(NULLIF(%s, ''), attribution_media_source),
                attribution_campaign = COALESCE(NULLIF(%s, ''), attribution_campaign),
                attribution_channel = COALESCE(NULLIF(%s, ''), attribution_channel),
                attribution_surface = %s,
                attribution_first_recorded_at = COALESCE(attribution_first_recorded_at, NOW()),
                attribution_updated_at = NOW(),
                updated_at = NOW()
            WHERE user_id = %s
            """,
            (
                self._attribution_value(appsflyer_uid),
                self._attribution_value(status),
                self._attribution_value(media_source),
                self._attribution_value(campaign),
                self._attribution_value(channel),
                surface,
                user_id,
            ),
        )

    @staticmethod
    def _attribution_value(value: str | None) -> str:
        return (value or "").strip()[:160]

    @staticmethod
    def _attribution_payload(user: dict[str, Any]) -> dict[str, Any] | None:
        status = (user.get("attribution_status") or "").strip()
        if not status:
            return None
        return {
            "appsflyer_uid": user.get("appsflyer_uid") or "",
            "af_status": status,
            "media_source": user.get("attribution_media_source") or "",
            "campaign": user.get("attribution_campaign") or "",
            "channel": user.get("attribution_channel") or "",
            "surface": user.get("attribution_surface") or "A",
            "first_recorded_at": user["attribution_first_recorded_at"].isoformat() if user.get("attribution_first_recorded_at") else None,
            "updated_at": user["attribution_updated_at"].isoformat() if user.get("attribution_updated_at") else None,
        }

    def _active_market_or_default(self, connection: psycopg.Connection[Any], requested_market: str) -> str:
        if requested_market == "DEFAULT":
            return "DEFAULT"
        row = self._one(
            connection,
            "SELECT market_code FROM market_wallet_policy WHERE market_code = %s AND enabled = TRUE",
            (requested_market,),
        )
        return requested_market if row else "DEFAULT"

    def _ensure_user(
        self,
        connection: psycopg.Connection[Any],
        user_id: str,
        region: str | None = None,
        geo_country: str | None = None,
    ) -> None:
        # The client locale is useful but can remain tied to a handset language or a
        # Google Play profile.  For a new/zero-balance wallet, the server-resolved
        # public-network country is the trustworthy fallback for configured markets.
        locale_market = self._active_market_or_default(connection, self._normalise_market_code(region))
        geo_market = self._active_market_or_default(connection, self._normalise_market_code(geo_country))
        requested_market = locale_market if locale_market != "DEFAULT" else geo_market
        self._execute(
            connection,
            "INSERT INTO users (user_id, market_code) VALUES (%s, %s) ON CONFLICT (user_id) DO NOTHING",
            (user_id, requested_market),
        )
        # 已产生资产的用户不切换币种，避免钱包和账本混入两种本币；零资产默认用户可在首次带地区或 GeoIP 启动时归入对应市场。
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
        country = (geo_country or "").strip().upper()[:2]
        if len(country) == 2 and country.isalpha():
            self._execute(
                connection,
                "UPDATE users SET geo_country = %s, updated_at = NOW() WHERE user_id = %s AND (geo_country IS NULL OR geo_country = '')",
                (country, user_id),
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
            "episode_reward_claim_prompt_threshold_gems": int(policy.get("episode_reward_claim_prompt_threshold_gems", 50_000)),
            "episode_task_max_episode": int(policy.get("episode_task_max_episode", 90)),
            "episode_task_post18_interval": int(policy.get("episode_task_post18_interval", 6)),
            "episode_task_redpacket_min_gems": int(policy.get("episode_task_redpacket_min_gems", 4000)),
            "episode_task_redpacket_max_gems": int(policy.get("episode_task_redpacket_max_gems", 8000)),
            "episode_task_tier1_min_gems": int(policy.get("episode_task_tier1_min_gems", 800)),
            "episode_task_tier1_max_gems": int(policy.get("episode_task_tier1_max_gems", 1200)),
            "episode_task_tier2_min_gems": int(policy.get("episode_task_tier2_min_gems", 1200)),
            "episode_task_tier2_max_gems": int(policy.get("episode_task_tier2_max_gems", 1800)),
            "episode_task_tier3_min_gems": int(policy.get("episode_task_tier3_min_gems", 2000)),
            "episode_task_tier3_max_gems": int(policy.get("episode_task_tier3_max_gems", 3000)),
            "episode_task_tier4_min_gems": int(policy.get("episode_task_tier4_min_gems", 3000)),
            "episode_task_tier4_max_gems": int(policy.get("episode_task_tier4_max_gems", 4000)),
            "wallet_redpacket_min_gems": int(policy.get("wallet_redpacket_min_gems", 800)),
            "wallet_redpacket_max_gems": int(policy.get("wallet_redpacket_max_gems", 1600)),
            "random_chest_min_gems": int(policy["random_chest_min_gems"]),
            "random_chest_max_gems": int(policy["random_chest_max_gems"]),
            "daily_random_chest_count": int(policy["daily_random_chest_count"]),
            "episodes_per_random_chest": int(policy["episodes_per_random_chest"]),
            "reward_claim_interstitial_enabled": bool(policy["reward_claim_interstitial_enabled"]),
            "interstitial_cooldown_seconds": int(policy["interstitial_cooldown_seconds"]),
            "interstitial_rewarded_skip_threshold": int(policy.get("interstitial_rewarded_skip_threshold", 0)),
            "mid_episode_interstitial_enabled": bool(policy["mid_episode_interstitial_enabled"]),
            "mid_episode_interstitial_trigger_seconds": int(policy["mid_episode_interstitial_trigger_seconds"]),
            "mid_episode_interstitial_repeat_seconds": int(policy.get("mid_episode_interstitial_repeat_seconds", 360)),
            "mid_episode_interstitial_hourly_limit": int(policy["mid_episode_interstitial_hourly_limit"]),
            "seek_interstitial_enabled": bool(policy["seek_interstitial_enabled"]),
            "seek_interstitial_hourly_limit": int(policy["seek_interstitial_hourly_limit"]),
            "next_episode_interstitial_enabled": bool(policy["next_episode_interstitial_enabled"]),
            "next_episode_interstitial_hourly_limit": int(policy["next_episode_interstitial_hourly_limit"]),
            "speed_interstitial_enabled": bool(policy["speed_interstitial_enabled"]),
            "speed_interstitial_hourly_limit": int(policy["speed_interstitial_hourly_limit"]),
            "action_interstitial_reward_threshold_gems": int(policy["action_interstitial_reward_threshold_gems"]),
            "crown_rewarded_ads_per_episode": int(policy["crown_rewarded_ads_per_episode"]),
            "crown_rewarded_daily_limit": int(policy.get("crown_rewarded_daily_limit", 0)),
            "crown_episode_progress_points": int(policy.get("crown_episode_progress_points", 2)),
            "crown_rewarded_ad_progress_points": int(policy.get("crown_rewarded_ad_progress_points", 1)),
            "crown_rewarded_cooldown_seconds": int(policy.get("crown_rewarded_cooldown_seconds", 30)),
            "release_boost_percent": float(policy["release_boost_percent"]),
            "release_boost_daily_limit": int(policy["release_boost_daily_limit"]),
            "release_boost_cooldown_seconds": int(policy.get("release_boost_cooldown_seconds", 30)),
            "ad_unavailable_episode_unlock_daily_limit": int(policy.get("ad_unavailable_episode_unlock_daily_limit", 3)),
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
            "gem_damping_tier5_threshold": int(policy["gem_damping_tier5_threshold"]),
            "gem_damping_tier1_multiplier": float(policy["gem_damping_tier1_multiplier"]),
            "gem_damping_tier2_multiplier": float(policy["gem_damping_tier2_multiplier"]),
            "gem_damping_tier3_multiplier": float(policy["gem_damping_tier3_multiplier"]),
            "gem_damping_tier4_multiplier": float(policy["gem_damping_tier4_multiplier"]),
            "gem_damping_tier5_multiplier": float(policy["gem_damping_tier5_multiplier"]),
            "gem_damping_tier6_multiplier": float(policy["gem_damping_tier6_multiplier"]),
            "gem_damping_scope": "ALL_GEM_SOURCES",
            "next_episode_interstitial_cooldown_seconds": int(policy["next_episode_interstitial_cooldown_seconds"]),
            "pause_ad_enabled": bool(policy["pause_ad_enabled"]),
            "pause_ad_min_play_seconds": int(policy["pause_ad_min_play_seconds"]),
            "pause_ad_cooldown_seconds": int(policy["pause_ad_cooldown_seconds"]),
            "pause_ad_hourly_limit": int(policy["pause_interstitial_hourly_limit"]),
            "updated_at": policy["updated_at"].isoformat(),
        }

    def _verify_admin_token(self, authorization: str | None) -> None:
        supplied = authorization.removeprefix("Bearer ").strip() if authorization else ""
        if supplied.startswith("adm."):
            parts = supplied.split(".")
            if len(parts) != 5:
                raise ServiceError(1902, "管理员会话无效", 401)
            try:
                username = self._b64decode(parts[1])
                expires = int(parts[2])
            except (ValueError, UnicodeDecodeError):
                raise ServiceError(1902, "管理员会话无效", 401) from None
            payload = ".".join(parts[:4])
            expected = hmac.new(self.settings.session_signing_secret.encode(), payload.encode(), hashlib.sha256).hexdigest()
            if expires <= int(self._now().timestamp()) or not hmac.compare_digest(parts[4], expected):
                raise ServiceError(1902, "管理员会话无效或已过期", 401)
            with self._transaction() as connection:
                account = self._one(connection, "SELECT enabled FROM admin_users WHERE username = %s", (username,))
            if not account or not bool(account["enabled"]):
                raise ServiceError(1902, "管理员账号已停用", 401)
            return
        token = self.settings.admin_dashboard_token
        if token and hmac.compare_digest(supplied, token):
            return
        raise ServiceError(1902, "管理员令牌无效", 401)

    def _sensitive_telemetry_cipher(self) -> Fernet | None:
        key = self.settings.telemetry_encryption_key
        if not key:
            return None
        try:
            return Fernet(key.encode("utf-8"))
        except (TypeError, ValueError) as error:
            raise ServiceError(2325, "TELEMETRY_ENCRYPTION_KEY 不是有效的 Fernet 密钥", 503) from error

    def _record_bootstrap_ip_audit(
        self,
        connection: psycopg.Connection[Any],
        user_id: str,
        client_ip: str | None,
    ) -> bool:
        raw_ip = (client_ip or "").strip()
        if not raw_ip:
            return False
        try:
            canonical_ip = str(ipaddress.ip_address(raw_ip))
        except ValueError:
            # Never preserve an untrusted forwarding-header fragment as an IP address.
            return False
        cipher = self._sensitive_telemetry_cipher()
        if cipher is None:
            return False
        stored = self._record_sensitive_telemetry(
            connection,
            user_id=user_id,
            audit_type="BOOTSTRAP_IP",
            payload={"ip": canonical_ip},
            retention_days=14,
            cipher=cipher,
        )
        if not stored:
            return False
        key = self.settings.telemetry_encryption_key or ""
        fingerprint = hmac.new(key.encode("utf-8"), canonical_ip.encode("utf-8"), hashlib.sha256).hexdigest()
        expires_at = datetime.now(UTC) + timedelta(days=180)
        self._execute(
            connection,
            """
            INSERT INTO sensitive_telemetry_ip_hash_observations (
                user_id, ip_fingerprint, first_seen_at, last_seen_at, expires_at
            ) VALUES (%s, %s, NOW(), NOW(), %s)
            ON CONFLICT (user_id, ip_fingerprint) DO UPDATE
            SET last_seen_at = NOW(), expires_at = EXCLUDED.expires_at
            """,
            (user_id, fingerprint, expires_at),
        )
        return True

    def _record_appsflyer_conversion_audit(
        self,
        connection: psycopg.Connection[Any],
        *,
        user_id: str,
        raw_conversion_payload: dict[str, Any] | None,
    ) -> bool:
        if not raw_conversion_payload:
            return False
        cipher = self._sensitive_telemetry_cipher()
        if cipher is None:
            return False
        return self._record_sensitive_telemetry(
            connection,
            user_id=user_id,
            audit_type="APPSFLYER_CONVERSION",
            payload=raw_conversion_payload,
            retention_days=30,
            cipher=cipher,
        )

    def _record_sensitive_telemetry(
        self,
        connection: psycopg.Connection[Any],
        *,
        user_id: str,
        audit_type: str,
        payload: dict[str, Any],
        retention_days: int,
        cipher: Fernet,
    ) -> bool:
        try:
            serialized = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), default=str)
        except (TypeError, ValueError):
            return False
        if len(serialized.encode("utf-8")) > 64 * 1024:
            # Keep raw diagnostic capacity meaningful while preventing an authenticated client
            # from turning the audit table into an unbounded arbitrary-payload store.
            return False
        key = self.settings.telemetry_encryption_key or ""
        fingerprint = hmac.new(key.encode("utf-8"), serialized.encode("utf-8"), hashlib.sha256).hexdigest()
        encrypted = cipher.encrypt(serialized.encode("utf-8")).decode("ascii")
        expires_at = datetime.now(UTC) + timedelta(days=retention_days)
        self._execute(
            connection,
            """
            INSERT INTO sensitive_telemetry_audit_events (
                user_id, audit_type, payload_fingerprint, encrypted_payload, received_at, expires_at
            ) VALUES (%s, %s, %s, %s, NOW(), %s)
            ON CONFLICT (user_id, audit_type, payload_fingerprint) DO UPDATE
            SET encrypted_payload = EXCLUDED.encrypted_payload,
                received_at = NOW(), expires_at = EXCLUDED.expires_at
            """,
            (user_id, audit_type, fingerprint, encrypted, expires_at),
        )
        self._purge_expired_sensitive_telemetry(connection)
        return True

    @staticmethod
    def _purge_expired_sensitive_telemetry(connection: psycopg.Connection[Any]) -> None:
        with connection.cursor() as cursor:
            cursor.execute("DELETE FROM sensitive_telemetry_audit_events WHERE expires_at < NOW()")
            cursor.execute("DELETE FROM sensitive_telemetry_ip_hash_observations WHERE expires_at < NOW()")

    def _issue_admin_session(self, username: str, ttl_seconds: int = 12 * 60 * 60) -> str:
        expires = int(self._now().timestamp()) + ttl_seconds
        payload = f"adm.{self._b64encode(username)}.{expires}.{secrets.token_urlsafe(18)}"
        signature = hmac.new(self.settings.session_signing_secret.encode(), payload.encode(), hashlib.sha256).hexdigest()
        return f"{payload}.{signature}"

    @staticmethod
    def _b64encode(value: str) -> str:
        return base64.urlsafe_b64encode(value.encode()).decode().rstrip("=")

    @staticmethod
    def _b64decode(value: str) -> str:
        return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4)).decode()

    @staticmethod
    def _hash_password(password: str) -> str:
        iterations = 310_000
        salt = secrets.token_bytes(16)
        digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, iterations)
        return f"pbkdf2_sha256${iterations}${base64.urlsafe_b64encode(salt).decode()}${base64.urlsafe_b64encode(digest).decode()}"

    @staticmethod
    def _verify_password(password: str, encoded: str) -> bool:
        try:
            algorithm, iterations, salt_b64, digest_b64 = encoded.split("$", 3)
            if algorithm != "pbkdf2_sha256":
                return False
            actual = hashlib.pbkdf2_hmac("sha256", password.encode(), base64.urlsafe_b64decode(salt_b64), int(iterations))
            expected = base64.urlsafe_b64decode(digest_b64)
            return hmac.compare_digest(actual, expected)
        except (ValueError, TypeError):
            return False

    def _round_reward(self, policy: dict[str, Any], round_number: int) -> int:
        raw = policy["r0_gems"] * math.pow(float(round_number), -float(policy["decay_alpha"]))
        return max(int(raw), int(policy.get("ten_round_bonus_floor_gems", policy["floor_gems"])))

    def _business_date(self) -> date:
        return self._now().date()

    def _now(self) -> datetime:
        return datetime.now(UTC).astimezone(self._business_timezone)

    @staticmethod
    def _utc_start_of_day(value: date) -> datetime:
        """Return the UTC+0 instant that starts a calendar reporting day."""
        return datetime(value.year, value.month, value.day, tzinfo=UTC)

    @staticmethod
    def _money(micros: int, wallet_policy: dict[str, Any]) -> str:
        digits = int(wallet_policy["currency_fraction_digits"])
        precision = Decimal(1).scaleb(-digits)
        amount = (Decimal(micros) / Decimal(1_000_000)).quantize(precision, rounding=ROUND_HALF_UP)
        return format(amount, f".{digits}f")

    @staticmethod
    def _usd_reference_money(micros: int, wallet_policy: dict[str, Any]) -> str:
        numerator = int(wallet_policy["usd_micros_per_10000_gems"])
        denominator = int(wallet_policy["currency_micros_per_10000_gems"])
        usd_micros = (micros * numerator) // max(denominator, 1)
        amount = (Decimal(usd_micros) / Decimal(1_000_000)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
        return format(amount, ".2f")

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
