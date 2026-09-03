from __future__ import annotations

import json
import os
import secrets
import sqlite3
from contextlib import contextmanager
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from threading import RLock
from typing import Any, Literal

from fastapi import HTTPException, status
from pydantic import BaseModel, Field, model_validator


class FeatureFlags(BaseModel):
    rewards_enabled: bool = True
    episode_ad_unlock_enabled: bool = True
    task_double_reward_enabled: bool = True
    checkin_enabled: bool = True
    wallet_enabled: bool = True


class UnlockRule(BaseModel):
    free_episodes_default: int = Field(default=1, ge=0, le=100)
    episodes_per_reward: int = Field(default=1, ge=1, le=10)
    daily_rewarded_unlock_limit: int = Field(default=8, ge=0, le=100)
    ad_cooldown_seconds: int = Field(default=30, ge=0, le=86_400)
    title: str = Field(default="观看广告解锁本集", max_length=80)
    description: str = Field(default="完整观看广告后即可继续追剧", max_length=180)


class AdRule(BaseModel):
    app_open_enabled: bool = True
    interstitial_enabled: bool = True
    rewarded_enabled: bool = True
    interstitial_min_interval_seconds: int = Field(default=60, ge=0, le=86_400)
    rewarded_min_interval_seconds: int = Field(default=30, ge=0, le=86_400)


class TaskDefinition(BaseModel):
    id: str = Field(min_length=1, max_length=64, pattern=r"^[a-z0-9_:-]+$")
    # 默认运营机制只基于有效观看剧集；广告仅用于解锁或对既有奖励进行可选翻倍。
    type: Literal["watch_episode"]
    title: str = Field(min_length=1, max_length=80)
    description: str = Field(min_length=1, max_length=180)
    target: int = Field(ge=1, le=100_000)
    reward_coins: int = Field(default=0, ge=0, le=1_000_000)
    reward_cash_cents: int = Field(default=0, ge=0, le=1_000_000)
    ad_double_enabled: bool = False
    daily_claim_limit: int = Field(default=1, ge=1, le=10)


class CheckinRule(BaseModel):
    rewards_coins: list[int] = Field(default_factory=lambda: [20, 25, 30, 35, 45, 55, 80], min_length=1, max_length=31)
    reward_cash_cents: int = Field(default=0, ge=0, le=100_000)
    ad_double_enabled: bool = True
    daily_claim_limit: int = Field(default=1, ge=1, le=1)


class RingRule(BaseModel):
    enabled: bool = True
    required_valid_watch_seconds: int = Field(default=300, ge=30, le=86_400)
    reward_coins: int = Field(default=50, ge=0, le=1_000_000)
    reward_cash_cents: int = Field(default=0, ge=0, le=1_000_000)
    ad_double_enabled: bool = True


class WalletRule(BaseModel):
    coin_to_cash_rate_cents_per_1000: int = Field(default=10, ge=0, le=100_000)
    minimum_coin_redemption: int = Field(default=500, ge=0, le=10_000_000)
    visible: bool = True
    eligibility_copy: str = Field(default="达到展示的兑换条件后，可在支持地区申请兑换。", max_length=240)


class RewardRules(BaseModel):
    schema_version: int = Field(default=1, ge=1)
    version: str = Field(default="development-v1", min_length=1, max_length=64)
    updated_at: datetime = Field(default_factory=lambda: datetime.now(UTC))
    feature_flags: FeatureFlags = Field(default_factory=FeatureFlags)
    unlock: UnlockRule = Field(default_factory=UnlockRule)
    ads: AdRule = Field(default_factory=AdRule)
    valid_watch_min_seconds: int = Field(default=60, ge=1, le=86_400)
    tasks: list[TaskDefinition] = Field(default_factory=list, max_length=40)
    checkin: CheckinRule = Field(default_factory=CheckinRule)
    ring: RingRule = Field(default_factory=RingRule)
    wallet: WalletRule = Field(default_factory=WalletRule)
    user_copy: dict[str, str] = Field(default_factory=dict)

    @model_validator(mode="after")
    def validate_unique_task_ids(self) -> "RewardRules":
        ids = [task.id for task in self.tasks]
        if len(ids) != len(set(ids)):
            raise ValueError("tasks 中 task id 不能重复")
        return self


DEFAULT_REWARD_RULES = RewardRules(
    version="development-v3-tiered-episode-rewards",
    tasks=[
        TaskDefinition(
            id="watch_1_episode_daily",
            type="watch_episode",
            title="完整观看 1 集",
            description="完成一集有效观看后领取 20 金币",
            target=1,
            reward_coins=20,
            ad_double_enabled=True,
        ),
        TaskDefinition(
            id="watch_3_episodes_daily",
            type="watch_episode",
            title="完整观看 3 集",
            description="完成三集有效观看后领取 60 金币",
            target=3,
            reward_coins=60,
            ad_double_enabled=True,
        ),
        TaskDefinition(
            id="watch_5_episodes_daily",
            type="watch_episode",
            title="完整观看 5 集",
            description="完成五集有效观看后领取 120 金币",
            target=5,
            reward_coins=120,
            ad_double_enabled=True,
        ),
    ],
    ring=RingRule(enabled=False),
    user_copy={"reward_notice": "完成有效剧集观看，逐级领取追剧金币奖励"},
)


class BootstrapRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=128)
    region: str = Field(min_length=2, max_length=2, pattern=r"^[A-Z]{2}$")
    app_version: str | None = Field(default=None, max_length=64)


class RewardEventRequest(BaseModel):
    event_id: str = Field(min_length=8, max_length=128)
    user_id: str = Field(min_length=1, max_length=128)
    type: Literal["watch_episode"]
    occurred_at: datetime | None = None
    drama_id: str | None = Field(default=None, max_length=128)
    episode_id: str | None = Field(default=None, max_length=128)
    valid_watch_seconds: int = Field(default=0, ge=0, le=86_400)


class AdClaimRequest(BaseModel):
    request_id: str = Field(min_length=8, max_length=128)
    user_id: str = Field(min_length=1, max_length=128)
    purpose: Literal["episode_unlock", "task_double", "checkin_double", "ring_double"]
    target_id: str = Field(min_length=1, max_length=128)
    proof: str | None = Field(default=None, max_length=4096)


class ClaimRequest(BaseModel):
    claim_id: str = Field(min_length=8, max_length=128)
    user_id: str = Field(min_length=1, max_length=128)
    type: Literal["task", "checkin", "ring", "episode_unlock"]
    target_id: str = Field(min_length=1, max_length=128)
    mode: Literal["standard", "ad_double"] = "standard"
    ad_token: str | None = Field(default=None, min_length=16, max_length=256)


class RewardConfigStore:
    """版本化 JSON 规则文件，保存时使用原子替换以避免半写入配置。"""

    def __init__(self, path: str | Path) -> None:
        self._path = Path(path)
        self._lock = RLock()
        self._rules: RewardRules | None = None

    @property
    def path(self) -> Path:
        return self._path

    def load(self) -> RewardRules:
        with self._lock:
            if not self._path.exists():
                self.save(DEFAULT_REWARD_RULES)
            raw = json.loads(self._path.read_text(encoding="utf-8"))
            self._rules = RewardRules.model_validate(raw)
            return self._rules

    def current(self) -> RewardRules:
        with self._lock:
            return self._rules or self.load()

    def save(self, rules: RewardRules) -> RewardRules:
        with self._lock:
            self._path.parent.mkdir(parents=True, exist_ok=True)
            normalized = rules.model_copy(update={"updated_at": datetime.now(UTC)})
            temp_path = self._path.with_suffix(f"{self._path.suffix}.tmp")
            temp_path.write_text(json.dumps(normalized.model_dump(mode="json"), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            os.replace(temp_path, self._path)
            self._rules = normalized
            return normalized


class RewardLedgerStore:
    """SQLite 测试账本：幂等事件 + 事务更新；不可作为生产支付账本。"""

    def __init__(self, path: str | Path) -> None:
        self._path = Path(path)
        self._path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = RLock()
        self._initialize()

    @contextmanager
    def _connection(self):
        connection = sqlite3.connect(self._path, isolation_level=None)
        connection.row_factory = sqlite3.Row
        try:
            yield connection
        finally:
            connection.close()

    def _initialize(self) -> None:
        with self._connection() as connection:
            connection.executescript(
                """
                PRAGMA journal_mode=WAL;
                PRAGMA foreign_keys=ON;
                CREATE TABLE IF NOT EXISTS users (
                    user_id TEXT PRIMARY KEY,
                    coins INTEGER NOT NULL DEFAULT 0,
                    cash_cents INTEGER NOT NULL DEFAULT 0,
                    level INTEGER NOT NULL DEFAULT 1,
                    watched_seconds INTEGER NOT NULL DEFAULT 0,
                    watched_episodes INTEGER NOT NULL DEFAULT 0,
                    checkin_streak INTEGER NOT NULL DEFAULT 0,
                    last_checkin_date TEXT,
                    ring_progress_seconds INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS task_progress (
                    user_id TEXT NOT NULL,
                    task_id TEXT NOT NULL,
                    progress INTEGER NOT NULL DEFAULT 0,
                    claimed_count INTEGER NOT NULL DEFAULT 0,
                    progress_date TEXT NOT NULL,
                    PRIMARY KEY (user_id, task_id, progress_date)
                );
                CREATE TABLE IF NOT EXISTS ad_tokens (
                    token TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    purpose TEXT NOT NULL,
                    target_id TEXT NOT NULL,
                    rule_version TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    consumed_at TEXT,
                    source_request_id TEXT NOT NULL UNIQUE
                );
                CREATE TABLE IF NOT EXISTS episode_unlocks (
                    user_id TEXT NOT NULL,
                    drama_id TEXT NOT NULL,
                    episode_index INTEGER NOT NULL,
                    rule_version TEXT NOT NULL,
                    unlocked_at TEXT NOT NULL,
                    PRIMARY KEY (user_id, drama_id, episode_index)
                );
                CREATE TABLE IF NOT EXISTS reward_ledger (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL UNIQUE,
                    kind TEXT NOT NULL,
                    target_id TEXT NOT NULL,
                    coins_delta INTEGER NOT NULL DEFAULT 0,
                    cash_cents_delta INTEGER NOT NULL DEFAULT 0,
                    rule_version TEXT NOT NULL,
                    metadata_json TEXT NOT NULL DEFAULT '{}',
                    created_at TEXT NOT NULL
                );
                """
            )

    @staticmethod
    def _now() -> datetime:
        return datetime.now(UTC)

    @staticmethod
    def _day(value: datetime | None = None) -> str:
        return (value or datetime.now(UTC)).date().isoformat()

    def ensure_user(self, user_id: str) -> None:
        now = self._now().isoformat()
        with self._lock, self._connection() as connection:
            connection.execute(
                "INSERT OR IGNORE INTO users (user_id, created_at, updated_at) VALUES (?, ?, ?)",
                (user_id, now, now),
            )

    def user_row(self, user_id: str) -> sqlite3.Row:
        self.ensure_user(user_id)
        with self._connection() as connection:
            row = connection.execute("SELECT * FROM users WHERE user_id = ?", (user_id,)).fetchone()
        assert row is not None
        return row

    def unlocked_episode_targets(self, user_id: str) -> list[str]:
        with self._connection() as connection:
            rows = connection.execute(
                "SELECT drama_id, episode_index FROM episode_unlocks WHERE user_id = ? ORDER BY drama_id, episode_index",
                (user_id,),
            ).fetchall()
        return [f"{row['drama_id']}:{row['episode_index']}" for row in rows]

    def recent_ledger(self, user_id: str, limit: int = 20) -> list[dict[str, Any]]:
        with self._connection() as connection:
            rows = connection.execute(
                "SELECT kind, target_id, coins_delta, cash_cents_delta, rule_version, metadata_json, created_at "
                "FROM reward_ledger WHERE user_id = ? ORDER BY id DESC LIMIT ?",
                (user_id, limit),
            ).fetchall()
        return [
            {
                "kind": row["kind"],
                "target_id": row["target_id"],
                "coins_delta": row["coins_delta"],
                "cash_cents_delta": row["cash_cents_delta"],
                "rule_version": row["rule_version"],
                "metadata": json.loads(row["metadata_json"]),
                "created_at": row["created_at"],
            }
            for row in rows
        ]

    def task_snapshot(self, user_id: str, rules: RewardRules) -> list[dict[str, Any]]:
        today = self._day()
        with self._connection() as connection:
            rows = connection.execute(
                "SELECT task_id, progress, claimed_count FROM task_progress WHERE user_id = ? AND progress_date = ?",
                (user_id, today),
            ).fetchall()
        indexed = {row["task_id"]: row for row in rows}
        result = []
        for task in rules.tasks:
            row = indexed.get(task.id)
            progress = int(row["progress"]) if row else 0
            claimed_count = int(row["claimed_count"]) if row else 0
            claimable = progress >= task.target and claimed_count < task.daily_claim_limit
            result.append(
                {
                    "id": task.id,
                    "type": task.type,
                    "title": task.title,
                    "description": task.description,
                    "progress": min(progress, task.target),
                    "target": task.target,
                    "reward_coins": task.reward_coins,
                    "reward_cash_cents": task.reward_cash_cents,
                    "ad_double_enabled": task.ad_double_enabled,
                    "status": "CLAIMABLE" if claimable else ("CLAIMED" if claimed_count >= task.daily_claim_limit else "IN_PROGRESS"),
                    "daily_claim_limit": task.daily_claim_limit,
                    "claimed_count": claimed_count,
                }
            )
        return result

    def bootstrap(self, user_id: str, rules: RewardRules) -> dict[str, Any]:
        user = self.user_row(user_id)
        today = self._day()
        unlocked_today = self._count_today(user_id, "episode_unlock")
        ring_complete = user["ring_progress_seconds"] >= rules.ring.required_valid_watch_seconds
        checkin_reward_index = max(0, min(user["checkin_streak"], len(rules.checkin.rewards_coins) - 1))
        return {
            "rule_version": rules.version,
            "feature_flags": rules.feature_flags.model_dump(),
            "unlock": rules.unlock.model_dump(),
            "ads": rules.ads.model_dump(),
            "wallet_rules": rules.wallet.model_dump(),
            "user_copy": rules.user_copy,
            "wallet": {
                "coins": user["coins"],
                "cash_cents": user["cash_cents"],
                "level": user["level"],
                "watched_seconds": user["watched_seconds"],
                "watched_episodes": user["watched_episodes"],
                "checkin_streak": user["checkin_streak"],
                "signed_in_today": user["last_checkin_date"] == today,
            },
            "tasks": self.task_snapshot(user_id, rules),
            "checkin": {
                "enabled": rules.feature_flags.checkin_enabled,
                "signed_in_today": user["last_checkin_date"] == today,
                "streak": user["checkin_streak"],
                "next_reward_coins": rules.checkin.rewards_coins[checkin_reward_index],
                # 完整七日阶梯直接取自动态规则，客户端仅负责展示，不自行写死奖励金额。
                "rewards_coins": rules.checkin.rewards_coins,
                "ad_double_enabled": rules.checkin.ad_double_enabled,
            },
            "ring": {
                "enabled": rules.ring.enabled,
                "progress_seconds": min(user["ring_progress_seconds"], rules.ring.required_valid_watch_seconds),
                "target_seconds": rules.ring.required_valid_watch_seconds,
                "claimable": ring_complete,
                "reward_coins": rules.ring.reward_coins,
                "ad_double_enabled": rules.ring.ad_double_enabled,
            },
            "episode_unlocks_today": unlocked_today,
            "unlocked_episode_targets": self.unlocked_episode_targets(user_id),
            "recent_ledger": self.recent_ledger(user_id),
        }

    def record_watch_event(self, payload: RewardEventRequest, rules: RewardRules) -> dict[str, Any]:
        if payload.type == "watch_episode" and payload.valid_watch_seconds < rules.valid_watch_min_seconds:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"有效观看时长不足 {rules.valid_watch_min_seconds} 秒，未计入奖励进度",
            )
        now = self._now()
        today = self._day(now)
        with self._lock, self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                self._ensure_user_in_transaction(connection, payload.user_id, now)
                duplicate = connection.execute(
                    "SELECT 1 FROM reward_ledger WHERE idempotency_key = ?", (payload.event_id,)
                ).fetchone()
                if duplicate:
                    connection.execute("COMMIT")
                    return self.bootstrap(payload.user_id, rules)

                watched_seconds_delta = payload.valid_watch_seconds
                watched_episode_delta = 1
                connection.execute(
                    "UPDATE users SET watched_seconds = watched_seconds + ?, watched_episodes = watched_episodes + ?, "
                    "ring_progress_seconds = ring_progress_seconds + ?, updated_at = ? WHERE user_id = ?",
                    (watched_seconds_delta, watched_episode_delta, watched_seconds_delta, now.isoformat(), payload.user_id),
                )
                self._apply_rule_progress(connection, payload.user_id, today, rules, "watch_episode", watched_episode_delta)
                self._insert_ledger(
                    connection,
                    user_id=payload.user_id,
                    idempotency_key=payload.event_id,
                    kind="watch_event",
                    target_id=payload.episode_id or payload.drama_id or "watch",
                    rule_version=rules.version,
                    metadata={"type": payload.type, "valid_watch_seconds": payload.valid_watch_seconds},
                    now=now,
                )
                connection.execute("COMMIT")
            except Exception:
                connection.execute("ROLLBACK")
                raise
        return self.bootstrap(payload.user_id, rules)

    def issue_ad_token(self, payload: AdClaimRequest, rules: RewardRules, environment: str) -> dict[str, Any]:
        if not rules.feature_flags.rewards_enabled or not rules.ads.rewarded_enabled:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="当前规则未启用激励广告")
        if environment != "development" or payload.proof != "TEST_PANGLE_REWARD":
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="广告奖励需要服务端回调或验签；仅开发环境可使用 TEST_PANGLE_REWARD 进行联调",
            )
        now = self._now()
        expires_at = now + timedelta(minutes=5)
        with self._lock, self._connection() as connection:
            existing = connection.execute(
                "SELECT token, expires_at FROM ad_tokens WHERE source_request_id = ?", (payload.request_id,)
            ).fetchone()
            if existing:
                return {"verified": True, "ad_token": existing["token"], "expires_at": existing["expires_at"], "rule_version": rules.version}
            token = secrets.token_urlsafe(32)
            connection.execute(
                "INSERT INTO ad_tokens (token, user_id, purpose, target_id, rule_version, expires_at, source_request_id) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (token, payload.user_id, payload.purpose, payload.target_id, rules.version, expires_at.isoformat(), payload.request_id),
            )
            # 广告只验证解锁/翻倍资格，不形成以广告观看次数为奖励目标的任务进度。
        return {"verified": True, "ad_token": token, "expires_at": expires_at.isoformat(), "rule_version": rules.version}

    def claim(self, payload: ClaimRequest, rules: RewardRules) -> dict[str, Any]:
        now = self._now()
        today = self._day(now)
        with self._lock, self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                self._ensure_user_in_transaction(connection, payload.user_id, now)
                prior = connection.execute(
                    "SELECT * FROM reward_ledger WHERE idempotency_key = ?", (payload.claim_id,)
                ).fetchone()
                if prior:
                    connection.execute("COMMIT")
                    return self.bootstrap(payload.user_id, rules)

                multiplier = self._resolve_multiplier(connection, payload, rules, now)
                coins, cash_cents, kind = self._claim_reward(connection, payload, rules, today, multiplier, now)
                self._insert_ledger(
                    connection,
                    user_id=payload.user_id,
                    idempotency_key=payload.claim_id,
                    kind=kind,
                    target_id=payload.target_id,
                    coins_delta=coins,
                    cash_cents_delta=cash_cents,
                    rule_version=rules.version,
                    metadata={"mode": payload.mode, "multiplier": multiplier},
                    now=now,
                )
                connection.execute(
                    "UPDATE users SET coins = coins + ?, cash_cents = cash_cents + ?, updated_at = ? WHERE user_id = ?",
                    (coins, cash_cents, now.isoformat(), payload.user_id),
                )
                connection.execute("COMMIT")
            except Exception:
                connection.execute("ROLLBACK")
                raise
        return self.bootstrap(payload.user_id, rules)

    def _claim_reward(
        self,
        connection: sqlite3.Connection,
        payload: ClaimRequest,
        rules: RewardRules,
        today: str,
        multiplier: int,
        now: datetime,
    ) -> tuple[int, int, str]:
        if payload.type == "task":
            task = next((item for item in rules.tasks if item.id == payload.target_id), None)
            if task is None:
                raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="任务不存在或当前规则已移除该任务")
            row = connection.execute(
                "SELECT progress, claimed_count FROM task_progress WHERE user_id = ? AND task_id = ? AND progress_date = ?",
                (payload.user_id, task.id, today),
            ).fetchone()
            progress, claimed_count = (int(row["progress"]), int(row["claimed_count"])) if row else (0, 0)
            if progress < task.target:
                raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="任务进度不足，暂不能领取")
            if multiplier == 2 and not task.ad_double_enabled:
                raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="当前任务规则不支持广告翻倍领取")
            if claimed_count >= task.daily_claim_limit:
                raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="该任务今日已达到领取次数上限")
            connection.execute(
                "INSERT INTO task_progress (user_id, task_id, progress, claimed_count, progress_date) VALUES (?, ?, ?, ?, ?) "
                "ON CONFLICT(user_id, task_id, progress_date) DO UPDATE SET claimed_count = excluded.claimed_count",
                (payload.user_id, task.id, progress, claimed_count + 1, today),
            )
            return task.reward_coins * multiplier, task.reward_cash_cents * multiplier, "task_claim"

        if payload.type == "checkin":
            if not rules.feature_flags.checkin_enabled:
                raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="当前规则未启用签到")
            user = connection.execute("SELECT checkin_streak, last_checkin_date FROM users WHERE user_id = ?", (payload.user_id,)).fetchone()
            assert user is not None
            if user["last_checkin_date"] == today:
                raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="今天已经签到")
            if multiplier == 2 and not rules.checkin.ad_double_enabled:
                raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="当前签到规则不支持广告翻倍领取")
            yesterday = (date.fromisoformat(today) - timedelta(days=1)).isoformat()
            streak = int(user["checkin_streak"]) + 1 if user["last_checkin_date"] == yesterday else 1
            reward_index = min(streak - 1, len(rules.checkin.rewards_coins) - 1)
            connection.execute(
                "UPDATE users SET checkin_streak = ?, last_checkin_date = ?, updated_at = ? WHERE user_id = ?",
                (streak, today, now.isoformat(), payload.user_id),
            )
            return rules.checkin.rewards_coins[reward_index] * multiplier, rules.checkin.reward_cash_cents * multiplier, "checkin_claim"

        if payload.type == "ring":
            if not rules.ring.enabled:
                raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="当前规则未启用观看圆环奖励")
            user = connection.execute("SELECT ring_progress_seconds FROM users WHERE user_id = ?", (payload.user_id,)).fetchone()
            assert user is not None
            if int(user["ring_progress_seconds"]) < rules.ring.required_valid_watch_seconds:
                raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="观看进度不足，暂不能领取")
            if multiplier == 2 and not rules.ring.ad_double_enabled:
                raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="当前圆环规则不支持广告翻倍领取")
            connection.execute(
                "UPDATE users SET ring_progress_seconds = ring_progress_seconds - ?, updated_at = ? WHERE user_id = ?",
                (rules.ring.required_valid_watch_seconds, now.isoformat(), payload.user_id),
            )
            return rules.ring.reward_coins * multiplier, rules.ring.reward_cash_cents * multiplier, "ring_claim"

        if payload.type == "episode_unlock":
            if not rules.feature_flags.episode_ad_unlock_enabled:
                raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="当前规则未启用广告解锁")
            if multiplier != 1:
                raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="分集解锁不支持广告翻倍领取模式")
            drama_id, _, raw_index = payload.target_id.rpartition(":")
            if not drama_id or not raw_index.isdigit() or int(raw_index) < 1:
                raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="分集解锁目标格式必须为 drama_id:episode_index")
            unlocked_today = self._count_today_in_connection(connection, payload.user_id, "episode_unlock")
            if unlocked_today >= rules.unlock.daily_rewarded_unlock_limit:
                raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="今日广告解锁次数已达到服务端上限")
            start = int(raw_index)
            for index in range(start, start + rules.unlock.episodes_per_reward):
                connection.execute(
                    "INSERT OR IGNORE INTO episode_unlocks (user_id, drama_id, episode_index, rule_version, unlocked_at) VALUES (?, ?, ?, ?, ?)",
                    (payload.user_id, drama_id, index, rules.version, now.isoformat()),
                )
            return 0, 0, "episode_unlock"

        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="不支持的奖励领取类型")

    def _resolve_multiplier(self, connection: sqlite3.Connection, payload: ClaimRequest, rules: RewardRules, now: datetime) -> int:
        if payload.type == "episode_unlock":
            if not payload.ad_token:
                raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="广告解锁需要广告权益令牌")
            expected_purpose = "episode_unlock"
        elif payload.mode == "standard":
            return 1
        else:
            if not payload.ad_token:
                raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="广告翻倍领取需要广告权益令牌")
            expected_purpose = {
                "task": "task_double",
                "checkin": "checkin_double",
                "ring": "ring_double",
            }.get(payload.type)
            if expected_purpose is None:
                raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="该领取类型不支持广告翻倍")
        token = connection.execute("SELECT * FROM ad_tokens WHERE token = ?", (payload.ad_token,)).fetchone()
        if token is None or token["user_id"] != payload.user_id or token["purpose"] != expected_purpose or token["target_id"] != payload.target_id:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="广告权益令牌无效")
        if token["consumed_at"] is not None or datetime.fromisoformat(token["expires_at"]) <= now:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="广告权益令牌已使用或已过期")
        connection.execute("UPDATE ad_tokens SET consumed_at = ? WHERE token = ?", (now.isoformat(), payload.ad_token))
        return 1 if payload.type == "episode_unlock" else 2

    @staticmethod
    def _ensure_user_in_transaction(connection: sqlite3.Connection, user_id: str, now: datetime) -> None:
        connection.execute(
            "INSERT OR IGNORE INTO users (user_id, created_at, updated_at) VALUES (?, ?, ?)",
            (user_id, now.isoformat(), now.isoformat()),
        )

    @staticmethod
    def _apply_rule_progress(
        connection: sqlite3.Connection,
        user_id: str,
        day: str,
        rules: RewardRules,
        task_type: str,
        increment: int,
    ) -> None:
        if increment <= 0:
            return
        for task in rules.tasks:
            if task.type != task_type:
                continue
            connection.execute(
                "INSERT INTO task_progress (user_id, task_id, progress, claimed_count, progress_date) VALUES (?, ?, ?, 0, ?) "
                "ON CONFLICT(user_id, task_id, progress_date) DO UPDATE SET progress = task_progress.progress + excluded.progress",
                (user_id, task.id, increment, day),
            )

    @staticmethod
    def _insert_ledger(
        connection: sqlite3.Connection,
        user_id: str,
        idempotency_key: str,
        kind: str,
        target_id: str,
        rule_version: str,
        metadata: dict[str, Any],
        now: datetime,
        coins_delta: int = 0,
        cash_cents_delta: int = 0,
    ) -> None:
        connection.execute(
            "INSERT INTO reward_ledger (user_id, idempotency_key, kind, target_id, coins_delta, cash_cents_delta, rule_version, metadata_json, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (user_id, idempotency_key, kind, target_id, coins_delta, cash_cents_delta, rule_version, json.dumps(metadata, ensure_ascii=False), now.isoformat()),
        )

    def _count_today(self, user_id: str, kind: str) -> int:
        with self._connection() as connection:
            return self._count_today_in_connection(connection, user_id, kind)

    def _count_today_in_connection(self, connection: sqlite3.Connection, user_id: str, kind: str) -> int:
        day_start = datetime.combine(date.today(), datetime.min.time(), tzinfo=UTC).isoformat()
        return int(
            connection.execute(
                "SELECT COUNT(*) AS count FROM reward_ledger WHERE user_id = ? AND kind = ? AND created_at >= ?",
                (user_id, kind, day_start),
            ).fetchone()["count"]
        )


class RewardsService:
    def __init__(self, config_store: RewardConfigStore, ledger_store: RewardLedgerStore, environment: str) -> None:
        self._config_store = config_store
        self._ledger_store = ledger_store
        self._environment = environment
        self._config_store.load()

    def bootstrap(self, payload: BootstrapRequest) -> dict[str, Any]:
        return self._ledger_store.bootstrap(payload.user_id, self._config_store.current())

    def event(self, payload: RewardEventRequest) -> dict[str, Any]:
        return self._ledger_store.record_watch_event(payload, self._config_store.current())

    def ad_claim(self, payload: AdClaimRequest) -> dict[str, Any]:
        return self._ledger_store.issue_ad_token(payload, self._config_store.current(), self._environment)

    def claim(self, payload: ClaimRequest) -> dict[str, Any]:
        return self._ledger_store.claim(payload, self._config_store.current())

    def ledger(self, user_id: str) -> list[dict[str, Any]]:
        self._ledger_store.ensure_user(user_id)
        return self._ledger_store.recent_ledger(user_id)

    def current_rules(self) -> RewardRules:
        return self._config_store.current()

    def replace_rules(self, rules: RewardRules) -> RewardRules:
        current = self._config_store.current()
        if rules.version == current.version:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="新规则版本必须不同于当前版本")
        return self._config_store.save(rules)

    def reload_rules(self) -> RewardRules:
        return self._config_store.load()
