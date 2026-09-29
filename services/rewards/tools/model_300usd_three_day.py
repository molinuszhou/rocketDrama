#!/usr/bin/env python3
"""Reproduce the v1.7.0.0 US$300 reward-curve qualification model.

The model mirrors the settlement ordering in RewardsService._credit_gems:
settlement uses the user's credited total immediately before each event to pick
the applicable damping band, floors the result, then applies the hard cap.
It models a high-engagement qualification path, not expected average earnings.
"""

from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from dataclasses import asdict, dataclass
from decimal import Decimal, ROUND_DOWN
from pathlib import Path

UNITS_PER_USD = Decimal("10000")
CAP_UNITS = 3_000_000
WELCOME_UNITS = 600_000
EPISODES_PER_DAY = 60
DAYS = 3
CHECKIN_UNITS = (10_000, 20_000, 30_000)
WALLET_BOOST_UNITS = 1_000
WALLET_BOOSTS_PER_DAY = 1

# Mirrors services/rewards/sql/029_v1700_300usd_three_day_curve.sql.
DAMPING_TIERS: tuple[tuple[int, Decimal], ...] = (
    (2_650_000, Decimal("1.00")),
    (2_780_000, Decimal("0.50")),
    (2_860_000, Decimal("0.25")),
    (2_920_000, Decimal("0.12")),
    (2_970_000, Decimal("0.06")),
    (3_000_000, Decimal("0.01")),
)
TASK_REWARDS = {
    1: 10_000,
    3: 15_000,
    6: 15_000,
    9: 15_000,
    12: 20_000,
    15: 20_000,
    18: 25_000,
    24: 25_000,
    30: 25_000,
    36: 25_000,
    42: 25_000,
    48: 25_000,
    54: 25_000,
    60: 25_000,
}
CROWN_LEVELS = (
    ("BRONZE", 30, 12_000, Decimal("1.10")),
    ("SILVER", 100, 26_000, Decimal("1.25")),
    ("GOLD", 200, 66_000, Decimal("1.50")),
    ("DIAMOND", 500, 80_000, Decimal("2.00")),
    ("CROWN", 800, 100_000, Decimal("2.50")),
)
BASE_EPISODE_REWARD = 8_000
CROWN_EPISODE_PROGRESS_POINTS = 2


@dataclass(frozen=True)
class Settlement:
    day: int
    source: str
    raw_units: int
    damping_multiplier: str
    credited_units: int
    cumulative_units: int


def floor_units(value: Decimal) -> int:
    return int(value.to_integral_value(rounding=ROUND_DOWN))


def multiplier_for(cumulative_units: int) -> Decimal:
    for threshold, multiplier in DAMPING_TIERS:
        if cumulative_units < threshold:
            return multiplier
    return DAMPING_TIERS[-1][1]


def settle(day: int, source: str, raw_units: int, cumulative_units: int) -> tuple[Settlement, int]:
    multiplier = multiplier_for(cumulative_units)
    credited = min(
        floor_units(Decimal(raw_units) * multiplier),
        max(0, CAP_UNITS - cumulative_units),
    )
    total = cumulative_units + credited
    return (
        Settlement(
            day=day,
            source=source,
            raw_units=raw_units,
            damping_multiplier=f"{multiplier:.2f}",
            credited_units=credited,
            cumulative_units=total,
        ),
        total,
    )


def current_episode_multiplier(earned_crowns: set[str]) -> Decimal:
    return max(
        (multiplier for code, _, _, multiplier in CROWN_LEVELS if code in earned_crowns),
        default=Decimal("1.00"),
    )


def run_model() -> list[Settlement]:
    settlements: list[Settlement] = []
    total = 0
    earned_crowns: set[str] = set()
    lifetime_episode = 0

    welcome, total = settle(1, "welcome", WELCOME_UNITS, total)
    settlements.append(welcome)

    for day in range(1, DAYS + 1):
        checkin, total = settle(day, "checkin", CHECKIN_UNITS[day - 1], total)
        settlements.append(checkin)
        episode_claims: list[int] = []

        # Rewards for completed episodes are pending until the daily aggregate
        # claim. Crown milestones and task rewards settle in the order in which
        # they are created, matching the service's current event model.
        for episode_in_day in range(1, EPISODES_PER_DAY + 1):
            lifetime_episode += 1
            episode_claims.append(
                floor_units(Decimal(BASE_EPISODE_REWARD) * current_episode_multiplier(earned_crowns))
            )
            points = lifetime_episode * CROWN_EPISODE_PROGRESS_POINTS
            for code, threshold, reward_units, _ in CROWN_LEVELS:
                if code not in earned_crowns and points >= threshold:
                    earned_crowns.add(code)
                    award, total = settle(day, f"crown:{code.lower()}", reward_units, total)
                    settlements.append(award)
            task_units = TASK_REWARDS.get(episode_in_day)
            if task_units is not None:
                task, total = settle(day, f"watch_task:{episode_in_day}", task_units, total)
                settlements.append(task)

        for episode_index, raw_units in enumerate(episode_claims, start=1):
            episode, total = settle(day, f"episode_claim:{episode_index}", raw_units, total)
            settlements.append(episode)

        for index in range(1, WALLET_BOOSTS_PER_DAY + 1):
            boost, total = settle(day, f"wallet_boost:{index}", WALLET_BOOST_UNITS, total)
            settlements.append(boost)

    return settlements


def write_csv(settlements: list[Settlement], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=list(asdict(settlements[0]).keys()),
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerows(asdict(item) for item in settlements)


def summary(settlements: list[Settlement]) -> dict[str, object]:
    total_raw = sum(item.raw_units for item in settlements)
    credited_by_source: dict[str, int] = defaultdict(int)
    credited_by_day: dict[int, int] = defaultdict(int)
    raw_by_day: dict[int, int] = defaultdict(int)
    for item in settlements:
        credited_by_source[item.source.split(":", maxsplit=1)[0]] += item.credited_units
        credited_by_day[item.day] = item.cumulative_units
        raw_by_day[item.day] += item.raw_units
    final_units = settlements[-1].cumulative_units
    return {
        "model": "v1.7.0.0-three-day-usd300",
        "assumptions": {
            "days": DAYS,
            "episodes_per_day": EPISODES_PER_DAY,
            "welcome_reward_units": WELCOME_UNITS,
            "daily_checkin_units": list(CHECKIN_UNITS),
            "wallet_boosts_per_day": WALLET_BOOSTS_PER_DAY,
            "wallet_boost_units": WALLET_BOOST_UNITS,
            "crown_road_ads": 0,
            "unit_to_usd": "10,000 internal units = US$1.00",
        },
        "day_end_units": {str(day): credited_by_day[day] for day in range(1, DAYS + 1)},
        "day_end_usd": {str(day): f"{Decimal(credited_by_day[day]) / UNITS_PER_USD:.2f}" for day in range(1, DAYS + 1)},
        "raw_units": total_raw,
        "raw_usd": f"{Decimal(total_raw) / UNITS_PER_USD:.2f}",
        "credited_units": final_units,
        "credited_usd": f"{Decimal(final_units) / UNITS_PER_USD:.2f}",
        "cap_units": CAP_UNITS,
        "cap_usd": f"{Decimal(CAP_UNITS) / UNITS_PER_USD:.2f}",
        "headroom_units": CAP_UNITS - final_units,
        "headroom_usd": f"{Decimal(CAP_UNITS - final_units) / UNITS_PER_USD:.2f}",
        "credited_by_source_units": dict(sorted(credited_by_source.items())),
        "raw_by_day_units": {str(day): raw_by_day[day] for day in range(1, DAYS + 1)},
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, default=Path("docs/reward-model-300usd-3days"))
    args = parser.parse_args()
    settlements = run_model()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    write_csv(settlements, args.output_dir / "settlements.csv")
    result = summary(settlements)
    (args.output_dir / "summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if result["credited_units"] != 2_900_000:
        raise SystemExit(f"unexpected model result: {result['credited_units']}")


if __name__ == "__main__":
    main()
