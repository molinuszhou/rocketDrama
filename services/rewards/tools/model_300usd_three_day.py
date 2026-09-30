#!/usr/bin/env python3
"""Reproduce the v1.7.0.4 US$300 reward-curve qualification model.

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
EPISODES_PER_DAY = 80
DAYS = 3
CHECKIN_CYCLE_DAYS = 7
# The service uses this seven-day cycle repeatedly. The first three entries are
# the qualification-path check-ins; the later values make the complete cycle
# explicit for the workbook and operational review.
CHECKIN_CYCLE_UNITS = (10_000, 20_000, 30_000, 40_000, 50_000, 60_000, 70_000)
CHECKIN_UNITS = CHECKIN_CYCLE_UNITS[:DAYS]
WALLET_BOOST_UNITS = 2_000
WALLET_BOOSTS_PER_DAY = 10
CROWN_ACTIVE_BOOSTS_PER_DAY = 10
CROWN_REWARDED_AD_PROGRESS_POINTS = 1

# Mirrors services/rewards/sql/031_v1702_damping_starts_after_290usd.sql.
# US$290 is settled at the full rate. The 0.50x attenuation starts from the
# next settlement after the balance reaches 2,900,000 internal units.
DAMPING_TIERS: tuple[tuple[int, Decimal], ...] = (
    (2_900_000, Decimal("1.00")),
    (2_950_000, Decimal("0.50")),
    (2_975_000, Decimal("0.25")),
    (2_990_000, Decimal("0.12")),
    (2_998_000, Decimal("0.06")),
    (3_000_000, Decimal("0.01")),
)
TASK_REWARDS = {
    1: 10_000,
    3: 18_500,
    6: 18_500,
    9: 18_500,
    12: 27_000,
    15: 27_000,
    18: 40_500,
    24: 40_500,
    30: 40_500,
    36: 40_500,
    42: 40_500,
    48: 40_500,
    54: 40_500,
    60: 40_500,
}
CROWN_LEVELS = (
    ("BRONZE", 30, 12_000, Decimal("1.10")),
    ("SILVER", 100, 26_000, Decimal("1.25")),
    ("GOLD", 200, 67_000, Decimal("1.50")),
    ("DIAMOND", 500, 80_000, Decimal("2.00")),
    ("CROWN", 800, 100_000, Decimal("2.50")),
)
# Calibrated against the server's event-by-event settlement using only
# user-facing rewards that are whole-thousand or 500-unit values. The complete
# three-day path reaches exactly 2,900,000 units (US$290.00), retains the
# US$300 hard ceiling and avoids pre-US$290 attenuation.
BASE_EPISODE_REWARD = 2_000
CROWN_EPISODE_PROGRESS_POINTS = 2
TARGET_UNITS = 2_900_000
TARGET_TOLERANCE_UNITS = 100  # sub-cent settlement rounding allowance


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
    """Model the server settlement order for the full qualification path.

    Each business day uses the seven-day sign-in schedule, then ten Crown Road
    active ads, sixty completed episodes, all task claims, the daily aggregate
    episode claim, and ten wallet boosts. Crown ads contribute progress points
    but no direct wallet units; reaching a level changes only subsequent episode
    claims, matching ``record_watch_event`` and ``complete_rewarded_ad``.
    """
    settlements: list[Settlement] = []
    total = 0
    earned_crowns: set[str] = set()
    crown_points = 0

    def settle_event(day: int, source: str, raw_units: int) -> None:
        nonlocal total
        event, total = settle(day, source, raw_units, total)
        settlements.append(event)

    def award_crowns_if_due(day: int) -> None:
        for code, threshold, reward_units, _ in CROWN_LEVELS:
            if code not in earned_crowns and crown_points >= threshold:
                earned_crowns.add(code)
                settle_event(day, f"crown:{code.lower()}", reward_units)

    settle_event(1, "welcome", WELCOME_UNITS)

    for day in range(1, DAYS + 1):
        settle_event(day, "checkin", CHECKIN_CYCLE_UNITS[(day - 1) % CHECKIN_CYCLE_DAYS])

        # Qualification-path ordering: a user completes the daily Crown Road
        # accelerations before watching. The ad events add progress only.
        for index in range(1, CROWN_ACTIVE_BOOSTS_PER_DAY + 1):
            crown_points += CROWN_REWARDED_AD_PROGRESS_POINTS
            settle_event(day, f"crown_active_ad:{index}", 0)
            award_crowns_if_due(day)

        episode_claims: list[int] = []
        for episode_in_day in range(1, EPISODES_PER_DAY + 1):
            episode_claims.append(
                floor_units(Decimal(BASE_EPISODE_REWARD) * current_episode_multiplier(earned_crowns))
            )
            crown_points += CROWN_EPISODE_PROGRESS_POINTS
            award_crowns_if_due(day)
            task_units = TASK_REWARDS.get(episode_in_day)
            if task_units is not None:
                settle_event(day, f"watch_task:{episode_in_day}", task_units)

        for episode_index, raw_units in enumerate(episode_claims, start=1):
            settle_event(day, f"episode_claim:{episode_index}", raw_units)

        for index in range(1, WALLET_BOOSTS_PER_DAY + 1):
            settle_event(day, f"wallet_boost:{index}", WALLET_BOOST_UNITS)

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
        "model": "v1.7.0.4-rounded-damping-after-290usd",
        "assumptions": {
            "days": DAYS,
            "episodes_per_day": EPISODES_PER_DAY,
            "welcome_reward_units": WELCOME_UNITS,
            "checkin_cycle_days": CHECKIN_CYCLE_DAYS,
            "checkin_cycle_units": list(CHECKIN_CYCLE_UNITS),
            "daily_checkin_units": list(CHECKIN_UNITS),
            "wallet_boosts_per_day": WALLET_BOOSTS_PER_DAY,
            "wallet_boost_units": WALLET_BOOST_UNITS,
            "crown_active_boosts_per_day": CROWN_ACTIVE_BOOSTS_PER_DAY,
            "crown_active_ad_progress_points": CROWN_REWARDED_AD_PROGRESS_POINTS,
            "damping_starts_after_units": 2_900_000,
            "damping_starts_after_usd": "290.00",
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


def write_chart(settlements: list[Settlement], path: Path) -> None:
    """Render the report chart from the same event ledger used for the JSON."""
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib import font_manager

    cjk_font = next(
        (
            candidate
            for candidate in (
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
            )
            if Path(candidate).exists()
        ),
        None,
    )
    if cjk_font:
        font_manager.fontManager.addfont(cjk_font)
        plt.rcParams["font.family"] = font_manager.FontProperties(fname=cjk_font).get_name()
    plt.rcParams["axes.unicode_minus"] = False

    day_end: list[int] = []
    raw_running = 0
    raw_end: list[int] = []
    for day in range(1, DAYS + 1):
        for item in settlements:
            if item.day == day:
                raw_running += item.raw_units
        day_rows = [item for item in settlements if item.day == day]
        day_end.append(day_rows[-1].cumulative_units)
        raw_end.append(raw_running)

    x = list(range(1, DAYS + 1))
    fig, axis = plt.subplots(figsize=(14, 8), dpi=150)
    fig.patch.set_facecolor("#F4F8FC")
    axis.set_facecolor("#F4F8FC")
    axis.plot(x, [value / float(UNITS_PER_USD) for value in raw_end], "--o", color="#AAB7C8", linewidth=2.5, label="原始奖励累计")
    axis.plot(x, [value / float(UNITS_PER_USD) for value in day_end], "-o", color="#FF6B4A", linewidth=4, markersize=9, label="结算后钱包累计")
    axis.axhline(float(CAP_UNITS / UNITS_PER_USD), color="#263547", linestyle=":", linewidth=2, label="300 美元硬上限")
    axis.axhline(float(TARGET_UNITS / UNITS_PER_USD), color="#E6A05E", linestyle=":", linewidth=2, label="三日目标 290 美元")
    axis.fill_between(x, float(TARGET_UNITS / UNITS_PER_USD), float(CAP_UNITS / UNITS_PER_USD), color="#FFE9D6", alpha=0.42)
    axis.set_xticks(x, [f"第{day}天" for day in x])
    axis.set_ylabel("默认 USD 市场钱包累计")
    fig.suptitle("JoyeDrama 三日 290 美元奖励路径", x=0.12, y=0.985, ha="left", fontsize=22, fontweight="bold", color="#17263A")
    fig.text(0.12, 0.90, "高参与度路径：每天 80 集、七日循环签到、每日各 10 次皇冠与钱包加速；US$290 前全额结算", color="#5A687A", fontsize=12)
    for day, value in zip(x, day_end):
        axis.annotate(f"${Decimal(value) / UNITS_PER_USD:.2f}", (day, value / float(UNITS_PER_USD)), xytext=(0, 14), textcoords="offset points", ha="center", fontsize=12, fontweight="bold", color="#17263A")
    axis.grid(axis="y", alpha=0.35)
    axis.spines[["top", "right"]].set_visible(False)
    axis.legend(loc="upper left", ncols=2, frameon=False)
    axis.set_ylim(0, max(float(CAP_UNITS / UNITS_PER_USD) * 1.18, max(raw_end) / float(UNITS_PER_USD) * 1.05))
    axis.yaxis.set_major_formatter(lambda value, _position: f"${value:,.0f}")
    fig.tight_layout(rect=(0, 0, 1, 0.83))
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, default=Path("docs/reward-model-300usd-3days"))
    args = parser.parse_args()
    if multiplier_for(2_899_999) != Decimal("1.00") or multiplier_for(2_900_000) != Decimal("0.50"):
        raise SystemExit("invalid damping boundary: attenuation must start after US$290.00")
    settlements = run_model()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    write_csv(settlements, args.output_dir / "settlements.csv")
    result = summary(settlements)
    (args.output_dir / "summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_chart(settlements, args.output_dir / "reward-curve.png")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    deviation = abs(int(result["credited_units"]) - TARGET_UNITS)
    if deviation > TARGET_TOLERANCE_UNITS:
        raise SystemExit(
            f"unexpected model result: {result['credited_units']} "
            f"(target={TARGET_UNITS}, tolerance={TARGET_TOLERANCE_UNITS})"
        )


if __name__ == "__main__":
    main()
