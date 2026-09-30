#!/usr/bin/env python3
"""Export the v1.7.0.0 qualification path as an auditable Excel workbook.

The source of truth for the economics remains model_300usd_three_day.py and the
SQL migration. This exporter supplies an editable, formula-backed workbook for
product and operations review; it never writes production policy.
"""

from __future__ import annotations

import argparse
import sys
from copy import copy
from dataclasses import dataclass
from decimal import Decimal, ROUND_DOWN
from pathlib import Path
from typing import Callable

from openpyxl import Workbook, load_workbook
from openpyxl.chart import BarChart, LineChart, Reference
from openpyxl.drawing.image import Image as ExcelImage
from openpyxl.formatting.rule import DataBarRule
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter
from PIL import Image as PILImage, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
import model_300usd_three_day as model  # noqa: E402


OUTPUT_DEFAULT = Path("docs/reward-model-300usd-3days/JoyeDrama_奖励模型_USD300_三日.xlsx")

NAVY = "172B4D"
BLUE = "2F75B5"
LIGHT_BLUE = "D9EAF7"
INPUT_YELLOW = "FFF2CC"
OUTPUT_GREEN = "E2F0D9"
LIGHT_GREY = "F2F2F2"
WHITE = "FFFFFF"
DARK = "202124"
ORANGE = "F4B183"
RED = "F4CCCC"
THIN_GREY = Side(style="thin", color="D9E1F2")


@dataclass(frozen=True)
class Event:
    day: int
    group: str
    label: str
    episode_in_day: int | None
    lifetime_episode: int | None
    formula: str
    note: str
    raw_units: int


def floor_units(value: Decimal) -> int:
    return int(value.to_integral_value(rounding=ROUND_DOWN))


def make_events() -> list[Event]:
    """Build every possible settlement row, including zero-value crown checks.

    Keeping candidate crown rows for each episode makes the workbook formula
    backed: edits to thresholds, crown awards or multipliers recalculate without
    changing the row structure. The event order mirrors the Python model.
    """
    events: list[Event] = []
    events.append(Event(1, "welcome", "Welcome reward", None, None, "=Inputs!$C$8", "One-time welcome reward", model.WELCOME_UNITS))

    earned_crowns: set[str] = set()
    crown_points = 0
    lifetime_episode = 0
    pending_episode_claims: list[tuple[int, int, int, str]] = []
    active_points_per_day = model.CROWN_ACTIVE_BOOSTS_PER_DAY * model.CROWN_REWARDED_AD_PROGRESS_POINTS

    crown_row_map = {code: 5 + index for index, (code, _, _, _) in enumerate(model.CROWN_LEVELS)}
    for day in range(1, model.DAYS + 1):
        events.append(
            Event(
                day,
                "checkin",
                f"Daily check-in · day {day}",
                None,
                None,
                f"=INDEX(Inputs!$C$17:$C$19,$B{{row}})",
                "Daily sign-in reward",
                model.CHECKIN_UNITS[day - 1],
            )
        )
        # The service processes the ten voluntary Crown Road ad actions before
        # episode viewing. They contribute progression only, but that ordering
        # changes which multiplier applies to subsequent episode rewards.
        for index in range(1, model.CROWN_ACTIVE_BOOSTS_PER_DAY + 1):
            crown_points += model.CROWN_REWARDED_AD_PROGRESS_POINTS
            events.append(
                Event(
                    day,
                    "crown_active_ad",
                    f"Crown active ad · {index}",
                    None,
                    None,
                    "=0",
                    "Adds Crown Road progress only; no direct wallet reward",
                    0,
                )
            )
        for episode_in_day in range(1, model.EPISODES_PER_DAY + 1):
            lifetime_episode += 1
            # Matches RewardsService ordering: this episode's cash is calculated
            # before the Crown milestone crossed by this episode is credited.
            episode_multiplier = model.current_episode_multiplier(earned_crowns)
            claim_raw = floor_units(Decimal(model.BASE_EPISODE_REWARD) * episode_multiplier)
            pending_episode_claims.append((day, episode_in_day, lifetime_episode, str(episode_multiplier)))
            prior_points = crown_points
            crown_points += model.CROWN_EPISODE_PROGRESS_POINTS
            current_points = crown_points
            for code, threshold, reward_units, _ in model.CROWN_LEVELS:
                input_row = crown_row_map[code]
                raw = reward_units if code not in earned_crowns and current_points >= threshold and prior_points < threshold else 0
                if raw:
                    earned_crowns.add(code)
                formula = (
                    f"=IF(AND($F{{row}}*Inputs!$C$12+$B{{row}}*{active_points_per_day}>=Inputs!$L${input_row},"
                    f"($F{{row}}-1)*Inputs!$C$12+$B{{row}}*{active_points_per_day}<Inputs!$L${input_row}),Inputs!$M${input_row},0)"
                )
                events.append(
                    Event(day, "crown", f"Crown check · {code}", episode_in_day, lifetime_episode, formula, "Awards when the crown threshold is crossed", raw)
                )
            task_raw = model.TASK_REWARDS.get(episode_in_day, 0)
            events.append(
                Event(
                    day,
                    "watch_task",
                    f"Watch task · episode {episode_in_day}",
                    episode_in_day,
                    lifetime_episode,
                    "=SUMIF(Inputs!$K$15:$K$28,$E{row},Inputs!$L$15:$L$28)",
                    "Task milestone reward; zero outside milestones",
                    task_raw,
                )
            )
            # Claims settle at the end of the day after milestones are created.
            events.append(
                Event(
                    day,
                    "episode_claim_template",
                    f"Deferred episode claim template · {episode_in_day}",
                    episode_in_day,
                    lifetime_episode,
                    "",
                    "Template row only; removed before workbook output",
                    claim_raw,
                )
            )

        for event in [item for item in events if item.group == "episode_claim_template" and item.day == day]:
            events.remove(event)
            multiplier_terms = ",".join(
                f"IF(($F{{row}}-1)*Inputs!$C$12+$B{{row}}*{active_points_per_day}>=Inputs!$L${crown_row_map[code]},Inputs!$N${crown_row_map[code]},1)"
                for code, _, _, _ in model.CROWN_LEVELS
            )
            events.append(
                Event(
                    day,
                    "episode_claim",
                    f"Episode claim · episode {event.episode_in_day}",
                    event.episode_in_day,
                    event.lifetime_episode,
                    f"=ROUNDDOWN(Inputs!$C$11*MAX(1,{multiplier_terms}),0)",
                    "Daily aggregated episode reward claim",
                    event.raw_units,
                )
            )
        for boost_index in range(1, model.WALLET_BOOSTS_PER_DAY + 1):
            events.append(
                Event(
                    day,
                    "wallet_boost",
                    f"Wallet ad boost · {boost_index}",
                    None,
                    None,
                    "=Inputs!$C$13",
                    "One of ten wallet boosts per active day",
                    model.WALLET_BOOST_UNITS,
                )
            )
    return events


def source_group(source: str) -> str:
    return source.split(":", maxsplit=1)[0]


def settle_events(events: list[Event]) -> list[dict[str, object]]:
    """Calculate snapshot values using the same ordering as the service model."""
    total = 0
    rows: list[dict[str, object]] = []
    for event in events:
        multiplier = model.multiplier_for(total)
        potential = floor_units(Decimal(event.raw_units) * multiplier)
        cap_remaining = max(0, model.CAP_UNITS - total)
        credited = min(potential, cap_remaining)
        cumulative = total + credited
        rows.append(
            {
                "day": event.day,
                "group": event.group,
                "label": event.label,
                "episode_in_day": event.episode_in_day,
                "lifetime_episode": event.lifetime_episode,
                "raw_units": event.raw_units,
                "prior_units": total,
                "damping_multiplier": float(multiplier),
                "potential_units": potential,
                "cap_remaining": cap_remaining,
                "credited_units": credited,
                "cumulative_units": cumulative,
                "credited_usd": float(Decimal(cumulative) / model.UNITS_PER_USD),
                "note": event.note,
            }
        )
        total = cumulative
    if total != 2_900_000:
        raise RuntimeError(f"Workbook schedule has drifted: expected 2,900,000, got {total:,}")
    return rows


def style_header(ws, row: int, start: int, end: int) -> None:
    for column in range(start, end + 1):
        cell = ws.cell(row, column)
        cell.fill = PatternFill("solid", fgColor=NAVY)
        cell.font = Font(color=WHITE, bold=True)
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
        cell.border = Border(bottom=THIN_GREY)


def style_subheader(ws, row: int, start: int, end: int) -> None:
    for column in range(start, end + 1):
        cell = ws.cell(row, column)
        cell.fill = PatternFill("solid", fgColor=BLUE)
        cell.font = Font(color=WHITE, bold=True)
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)


def set_column_widths(ws, widths: dict[str, float]) -> None:
    for letter, width in widths.items():
        ws.column_dimensions[letter].width = width


def add_title(ws, title: str, subtitle: str, width: int) -> None:
    ws.merge_cells(start_row=1, start_column=1, end_row=1, end_column=width)
    ws["A1"] = title
    ws["A1"].font = Font(size=18, bold=True, color=WHITE)
    ws["A1"].fill = PatternFill("solid", fgColor=NAVY)
    ws["A1"].alignment = Alignment(vertical="center")
    ws.row_dimensions[1].height = 30
    ws.merge_cells(start_row=2, start_column=1, end_row=2, end_column=width)
    ws["A2"] = subtitle
    ws["A2"].font = Font(size=10, color="5B6573", italic=True)
    ws["A2"].alignment = Alignment(wrap_text=True, vertical="center")
    ws.row_dimensions[2].height = 34


def build_workbook(output: Path) -> None:
    events = make_events()
    values = settle_events(events)
    wb = Workbook()
    wb.calculation.fullCalcOnLoad = True
    wb.calculation.forceFullCalc = True
    wb.calculation.calcMode = "auto"
    wb.properties.title = "JoyeDrama US$300 Three-Day Reward Model"
    wb.properties.subject = "Formula-backed qualification path under the v1.7.0.0 reward policy"
    wb.properties.creator = "JoyeDrama"

    readme = wb.active
    readme.title = "Read Me"
    readme.sheet_properties.tabColor = NAVY
    add_title(readme, "JoyeDrama · US$300 / 3-day reward model", "Editable review workbook. The model is a high-engagement qualification path, not an expected or promised user earning.", 8)
    readme.merge_cells("A4:H4")
    readme["A4"] = "How to use"
    readme["A4"].font = Font(size=13, bold=True, color=NAVY)
    readme["A5"] = "1"
    readme["B5"] = "Edit yellow cells only on the Inputs sheet."
    readme["A6"] = "2"
    readme["B6"] = "Review the formula-backed settlement order and damping in the Settlement sheet."
    readme["A7"] = "3"
    readme["B7"] = "Use Daily Summary and Source Mix for management review; both sheets calculate from Settlement."
    readme["A8"] = "4"
    readme["B8"] = "For changes to crown threshold timing or task counts, rerun the paired Python exporter to rebuild event rows."
    for row in range(5, 9):
        readme[f"A{row}"].font = Font(bold=True, color=BLUE)
        readme[f"B{row}"].alignment = Alignment(wrap_text=True)
    readme.merge_cells("A10:H10")
    readme["A10"] = "Model boundary"
    readme["A10"].font = Font(size=13, bold=True, color=NAVY)
    readme.merge_cells("A11:H14")
    readme["A11"] = (
        "This workbook mirrors the v1.7.0.4 server settlement order: reward events use the cumulative credited "
        "balance immediately before settlement to determine the damping multiplier, floor the credited units, and "
        "apply the 3,000,000-unit hard cap. The path assumes three consecutive days, 80 completed episodes per day, "
        "all configured watch tasks, daily check-in, ten Crown Road accelerations and ten wallet boosts each day."
    )
    readme["A11"].alignment = Alignment(wrap_text=True, vertical="top")
    readme["A11"].fill = PatternFill("solid", fgColor=LIGHT_BLUE)
    readme.merge_cells("A16:H16")
    readme["A16"] = "Expected default result"
    readme["A16"].font = Font(size=13, bold=True, color=NAVY)
    readme["A17"] = "Final credited amount"
    readme["B17"] = "US$290.00"
    readme["A18"] = "Lifetime cap"
    readme["B18"] = "US$300.00"
    readme["A19"] = "Headroom"
    readme["B19"] = "US$10.00"
    for cell in ("B17", "B18", "B19"):
        readme[cell].font = Font(bold=True, color=NAVY)
        readme[cell].fill = PatternFill("solid", fgColor=OUTPUT_GREEN)
    set_column_widths(readme, {"A": 18, "B": 34, "C": 18, "D": 18, "E": 18, "F": 18, "G": 18, "H": 18})
    readme.freeze_panes = "A4"

    inputs = wb.create_sheet("Inputs")
    inputs.sheet_properties.tabColor = "FFD966"
    add_title(inputs, "Inputs · yellow cells are editable", "All values mirror services/rewards/sql/029_v1700_300usd_three_day_curve.sql as of 2026-09-29.", 14)
    inputs["A4"] = "Core policy input"
    inputs["C4"] = "Value"
    style_subheader(inputs, 4, 1, 3)
    input_rows = [
        ("Internal units per US$", model.UNITS_PER_USD, "10,000 internal units = US$1.00"),
        ("Hard cap (units)", model.CAP_UNITS, "All reward sources share this lifetime cap"),
        ("Qualification target (units)", 2_900_000, "Expected high-engagement result on day 3"),
        ("Welcome reward (units)", model.WELCOME_UNITS, "One-time reward; displayed value derives from the current market rate"),
        ("Model days", model.DAYS, "Three consecutive active days"),
        ("Episodes per day", model.EPISODES_PER_DAY, "Qualification path assumption"),
        ("Base episode reward (units)", model.BASE_EPISODE_REWARD, "Before Crown Road multiplier"),
        ("Crown points per episode", model.CROWN_EPISODE_PROGRESS_POINTS, "Used to detect Crown Road thresholds"),
        ("Wallet boost reward (units)", model.WALLET_BOOST_UNITS, "One rewarded wallet boost"),
        ("Wallet boosts per day", model.WALLET_BOOSTS_PER_DAY, "Policy default = one"),
    ]
    for row, (label, value, note) in enumerate(input_rows, start=5):
        inputs.cell(row, 1, label)
        inputs.cell(row, 3, value)
        inputs.cell(row, 3).fill = PatternFill("solid", fgColor=INPUT_YELLOW)
        inputs.cell(row, 4, note)
        inputs.cell(row, 3).number_format = "#,##0"
    inputs["A15"] = "Daily check-in rewards"
    inputs["B15"] = "Day"
    inputs["C15"] = "Units"
    inputs["D15"] = "USD equivalent"
    style_subheader(inputs, 15, 1, 4)
    for index, reward in enumerate(model.CHECKIN_UNITS, start=1):
        row = 16 + index
        inputs.cell(row, 2, index)
        inputs.cell(row, 3, reward)
        inputs.cell(row, 3).fill = PatternFill("solid", fgColor=INPUT_YELLOW)
        inputs.cell(row, 4, f"=C{row}/$C$5")
        inputs.cell(row, 4).number_format = 'US$#,##0.00'
    inputs["G4"] = "Damping curve"
    inputs["H4"] = "Threshold (units)"
    inputs["I4"] = "Multiplier"
    style_subheader(inputs, 4, 7, 9)
    for index, (threshold, multiplier) in enumerate(model.DAMPING_TIERS, start=5):
        inputs.cell(index, 7, f"Tier {index - 4}")
        inputs.cell(index, 8, threshold)
        inputs.cell(index, 9, float(multiplier))
        inputs.cell(index, 8).fill = PatternFill("solid", fgColor=INPUT_YELLOW)
        inputs.cell(index, 9).fill = PatternFill("solid", fgColor=INPUT_YELLOW)
        inputs.cell(index, 8).number_format = "#,##0"
        inputs.cell(index, 9).number_format = "0.00x"
    inputs["K4"] = "Crown Road"
    inputs["L4"] = "Trigger points"
    inputs["M4"] = "Reward (units)"
    inputs["N4"] = "Episode multiplier"
    style_subheader(inputs, 4, 11, 14)
    for row, (code, threshold, reward, multiplier) in enumerate(model.CROWN_LEVELS, start=5):
        inputs.cell(row, 11, code)
        inputs.cell(row, 12, threshold)
        inputs.cell(row, 13, reward)
        inputs.cell(row, 14, float(multiplier))
        for column in (12, 13, 14):
            inputs.cell(row, column).fill = PatternFill("solid", fgColor=INPUT_YELLOW)
        inputs.cell(row, 12).number_format = "#,##0"
        inputs.cell(row, 13).number_format = "#,##0"
        inputs.cell(row, 14).number_format = "0.00x"
    inputs["K14"] = "Watch-task episode"
    inputs["L14"] = "Reward (units)"
    style_subheader(inputs, 14, 11, 12)
    for row, (episode, reward) in enumerate(model.TASK_REWARDS.items(), start=15):
        inputs.cell(row, 11, episode)
        inputs.cell(row, 12, reward)
        inputs.cell(row, 11).fill = PatternFill("solid", fgColor=INPUT_YELLOW)
        inputs.cell(row, 12).fill = PatternFill("solid", fgColor=INPUT_YELLOW)
        inputs.cell(row, 11).number_format = "#,##0"
        inputs.cell(row, 12).number_format = "#,##0"
    inputs.freeze_panes = "A5"
    set_column_widths(inputs, {"A": 31, "B": 12, "C": 18, "D": 42, "E": 4, "F": 4, "G": 16, "H": 20, "I": 15, "J": 4, "K": 22, "L": 18, "M": 18, "N": 19})

    settlement = wb.create_sheet("Settlement")
    settlement.sheet_properties.tabColor = BLUE
    add_title(settlement, "Settlement schedule", "Formula-backed event-level settlement; blue headers are outputs and yellow inputs live on the Inputs tab.", 15)
    headers = ["Row", "Day", "Source group", "Event", "Episode in day", "Lifetime episode", "Raw units", "Prior credited units", "Damping", "Potential units", "Cap remaining", "Credited units", "Cumulative units", "Cumulative USD", "Notes"]
    for column, header in enumerate(headers, start=1):
        settlement.cell(4, column, header)
    style_header(settlement, 4, 1, len(headers))

    for index, (event, value) in enumerate(zip(events, values), start=5):
        settlement.cell(index, 1, index - 4)
        settlement.cell(index, 2, event.day)
        settlement.cell(index, 3, event.group)
        settlement.cell(index, 4, event.label)
        settlement.cell(index, 5, event.episode_in_day)
        settlement.cell(index, 6, event.lifetime_episode)
        settlement.cell(index, 7, event.formula.replace("{row}", str(index)))
        settlement.cell(index, 8, 0 if index == 5 else f"=M{index - 1}")
        settlement.cell(index, 9, f"=IF(H{index}<Inputs!$H$5,Inputs!$I$5,IF(H{index}<Inputs!$H$6,Inputs!$I$6,IF(H{index}<Inputs!$H$7,Inputs!$I$7,IF(H{index}<Inputs!$H$8,Inputs!$I$8,IF(H{index}<Inputs!$H$9,Inputs!$I$9,Inputs!$I$10)))))")
        settlement.cell(index, 10, f"=ROUNDDOWN(G{index}*I{index},0)")
        settlement.cell(index, 11, f"=MAX(0,Inputs!$C$6-H{index})")
        settlement.cell(index, 12, f"=MIN(J{index},K{index})")
        settlement.cell(index, 13, f"=H{index}+L{index}")
        settlement.cell(index, 14, f"=M{index}/Inputs!$C$5")
        settlement.cell(index, 15, event.note)
        # Stored source-of-truth snapshot values make the workbook reviewable even before a spreadsheet engine recalculates.
        for column, key in ((7, "raw_units"), (8, "prior_units"), (9, "damping_multiplier"), (10, "potential_units"), (11, "cap_remaining"), (12, "credited_units"), (13, "cumulative_units"), (14, "credited_usd")):
            settlement.cell(index, column).comment = None
        settlement.cell(index, 15).comment = None
        for column in range(1, 16):
            settlement.cell(index, column).alignment = Alignment(vertical="top", wrap_text=column in (4, 15))
            if index % 2:
                settlement.cell(index, column).fill = PatternFill("solid", fgColor="F8FBFE")
        for column in (7, 8, 10, 11, 12, 13):
            settlement.cell(index, column).number_format = "#,##0"
        settlement.cell(index, 9).number_format = "0.00x"
        settlement.cell(index, 14).number_format = 'US$#,##0.00'
        # Keep a hidden numerical snapshot immediately next to the formulas, making both model traceability and Excel recalculation clear.
    snapshot_col = 17
    settlement.cell(4, snapshot_col, "Static verification snapshot")
    settlement.cell(4, snapshot_col).fill = PatternFill("solid", fgColor=LIGHT_GREY)
    settlement.cell(4, snapshot_col).font = Font(bold=True, color=DARK)
    snapshot_headers = ["Raw", "Prior", "Damping", "Potential", "Cap remaining", "Credited", "Cumulative", "USD"]
    for offset, header in enumerate(snapshot_headers, start=0):
        cell = settlement.cell(4, snapshot_col + offset)
        cell.value = header
        cell.fill = PatternFill("solid", fgColor=LIGHT_GREY)
        cell.font = Font(bold=True, color=DARK)
    for index, value in enumerate(values, start=5):
        static = [value["raw_units"], value["prior_units"], value["damping_multiplier"], value["potential_units"], value["cap_remaining"], value["credited_units"], value["cumulative_units"], value["credited_usd"]]
        for offset, number in enumerate(static):
            settlement.cell(index, snapshot_col + offset, number)
            settlement.cell(index, snapshot_col + offset).number_format = 'US$#,##0.00' if offset == 7 else ("0.00x" if offset == 2 else "#,##0")
    settlement.column_dimensions["Q"].hidden = True
    for col in range(18, 25):
        settlement.column_dimensions[get_column_letter(col)].hidden = True
    set_column_widths(settlement, {"A": 7, "B": 8, "C": 18, "D": 31, "E": 15, "F": 17, "G": 14, "H": 18, "I": 11, "J": 15, "K": 15, "L": 15, "M": 17, "N": 16, "O": 36})
    settlement.freeze_panes = "G5"
    settlement.auto_filter.ref = f"A4:O{4 + len(events)}"
    settlement.conditional_formatting.add(f"N5:N{4 + len(events)}", DataBarRule(start_type="min", end_type="max", color=BLUE, showValue=True))

    daily = wb.create_sheet("Daily Summary")
    daily.sheet_properties.tabColor = "70AD47"
    add_title(daily, "Daily outcome", "The summary uses Settlement formulas and therefore updates when editable Inputs change.", 9)
    daily_headers = ["Day", "Raw units", "Credited units", "End-of-day units", "End-of-day USD", "Target completion", "Daily credited USD", "Required actions", "Result note"]
    for column, header in enumerate(daily_headers, start=1):
        daily.cell(4, column, header)
    style_header(daily, 4, 1, len(daily_headers))
    last = 4 + len(events)
    action_note = "80 episodes · 14 task milestones · 1 check-in · 10 wallet boosts"
    for row, day in enumerate(range(1, model.DAYS + 1), start=5):
        daily.cell(row, 1, day)
        daily.cell(row, 2, f'=SUMIF(Settlement!$B$5:$B${last},A{row},Settlement!$G$5:$G${last})')
        daily.cell(row, 3, f'=SUMIF(Settlement!$B$5:$B${last},A{row},Settlement!$L$5:$L${last})')
        daily.cell(row, 4, f'=SUMIFS(Settlement!$L$5:$L${last},Settlement!$B$5:$B${last},"<="&A{row})')
        daily.cell(row, 5, f'=D{row}/Inputs!$C$5')
        daily.cell(row, 6, f'=D{row}/Inputs!$C$6')
        daily.cell(row, 7, f'=C{row}/Inputs!$C$5')
        daily.cell(row, 8, action_note)
        daily.cell(row, 9, "Un-damped" if day < 3 else "Damping begins after 2,900,000 units")
        for column in (2, 3, 4):
            daily.cell(row, column).number_format = "#,##0"
        daily.cell(row, 5).number_format = 'US$#,##0.00'
        daily.cell(row, 6).number_format = "0.0%"
        daily.cell(row, 7).number_format = 'US$#,##0.00'
    daily["A10"] = "Final credited units"
    daily["B10"] = "=D7"
    daily["A11"] = "Final credited USD"
    daily["B11"] = "=E7"
    daily["A12"] = "US$300 cap headroom"
    daily["B12"] = "=Inputs!$C$6-B10"
    daily["A13"] = "Headroom in USD"
    daily["B13"] = "=B12/Inputs!$C$5"
    for row in range(10, 14):
        daily[f"A{row}"].font = Font(bold=True, color=NAVY)
        daily[f"B{row}"].fill = PatternFill("solid", fgColor=OUTPUT_GREEN)
    daily["B10"].number_format = "#,##0"
    daily["B11"].number_format = 'US$#,##0.00'
    daily["B12"].number_format = "#,##0"
    daily["B13"].number_format = 'US$#,##0.00'
    daily["G10"] = "Validation"
    daily["H10"] = "Formula"
    style_subheader(daily, 10, 7, 8)
    checks = [
        ("Target result", '=IF(B10=Inputs!$C$7,"PASS","CHECK")'),
        ("Hard cap", '=IF(B10<=Inputs!$C$6,"PASS","CHECK")'),
        ("Result below cap", '=IF(B12>0,"PASS","CHECK")'),
    ]
    for row, (label, formula) in enumerate(checks, start=11):
        daily.cell(row, 7, label)
        daily.cell(row, 8, formula)
        daily.cell(row, 8).fill = PatternFill("solid", fgColor=OUTPUT_GREEN)
        daily.cell(row, 8).font = Font(bold=True, color=NAVY)
    chart = LineChart()
    chart.title = "Cumulative USD settlement"
    chart.y_axis.title = "US$"
    chart.x_axis.title = "Day"
    chart.height = 7.5
    chart.width = 13
    chart.add_data(Reference(daily, min_col=5, min_row=4, max_row=7), titles_from_data=True)
    chart.set_categories(Reference(daily, min_col=1, min_row=5, max_row=7))
    daily.add_chart(chart, "A16")
    set_column_widths(daily, {"A": 12, "B": 18, "C": 18, "D": 18, "E": 18, "F": 19, "G": 19, "H": 62, "I": 36})
    daily.freeze_panes = "A5"

    source = wb.create_sheet("Source Mix")
    source.sheet_properties.tabColor = ORANGE
    add_title(source, "Reward source mix", "Credited source contribution is calculated from the event-level settlement schedule.", 6)
    headers = ["Source group", "Raw units", "Credited units", "Credited USD", "Share of credited units", "Interpretation"]
    for column, header in enumerate(headers, start=1):
        source.cell(4, column, header)
    style_header(source, 4, 1, len(headers))
    groups = [
        ("welcome", "One-time onboarding reward"),
        ("checkin", "Daily active-day reward"),
        ("crown", "Natural Crown Road milestones; no optional Crown ads"),
        ("watch_task", "Daily episode task milestones"),
        ("episode_claim", "Aggregated episode watching reward"),
        ("wallet_boost", "One optional wallet acceleration per active day"),
    ]
    for row, (group, note) in enumerate(groups, start=5):
        source.cell(row, 1, group)
        source.cell(row, 2, f'=SUMIF(Settlement!$C$5:$C${last},A{row},Settlement!$G$5:$G${last})')
        source.cell(row, 3, f'=SUMIF(Settlement!$C$5:$C${last},A{row},Settlement!$L$5:$L${last})')
        source.cell(row, 4, f'=C{row}/Inputs!$C$5')
        source.cell(row, 5, f'=C{row}/\'Daily Summary\'!$B$10')
        source.cell(row, 6, note)
        for column in (2, 3):
            source.cell(row, column).number_format = "#,##0"
        source.cell(row, 4).number_format = 'US$#,##0.00'
        source.cell(row, 5).number_format = "0.0%"
    source["A12"] = "Total"
    source["B12"] = "=SUM(B5:B10)"
    source["C12"] = "=SUM(C5:C10)"
    source["D12"] = "=SUM(D5:D10)"
    source["E12"] = "=SUM(E5:E10)"
    for column in range(1, 6):
        source.cell(12, column).font = Font(bold=True, color=NAVY)
        source.cell(12, column).fill = PatternFill("solid", fgColor=OUTPUT_GREEN)
    source["B12"].number_format = "#,##0"
    source["C12"].number_format = "#,##0"
    source["D12"].number_format = 'US$#,##0.00'
    source["E12"].number_format = "0.0%"
    chart = BarChart()
    chart.type = "bar"
    chart.style = 10
    chart.title = "Credited USD by reward source"
    chart.x_axis.title = "US$"
    chart.y_axis.title = "Reward source"
    chart.height = 7.5
    chart.width = 13
    chart.add_data(Reference(source, min_col=4, min_row=4, max_row=10), titles_from_data=True)
    chart.set_categories(Reference(source, min_col=1, min_row=5, max_row=10))
    source.add_chart(chart, "A15")
    set_column_widths(source, {"A": 20, "B": 17, "C": 19, "D": 17, "E": 23, "F": 55})
    source.freeze_panes = "A5"

    audit = wb.create_sheet("Audit")
    audit.sheet_properties.tabColor = "A5A5A5"
    add_title(audit, "Model audit", "Formula checks and direct reproduction checks. Green values must read PASS under the default inputs.", 6)
    audit["A4"] = "Control"
    audit["B4"] = "Formula result"
    audit["C4"] = "Expected static result"
    audit["D4"] = "Purpose"
    style_header(audit, 4, 1, 4)
    controls = [
        ("Final credited units", "='Daily Summary'!$B$10", 2_900_000, "Matches the agreed high-engagement qualification result"),
        ("Final USD", "='Daily Summary'!$B$11", 290.00, "Uses 10,000 internal units per US$1.00"),
        ("Cap units", "=Inputs!$C$6", 3_000_000, "Hard lifetime reward ceiling"),
        ("Headroom units", "='Daily Summary'!$B$12", 100_000, "Room below the US$300 cap"),
        ("Target test", "='Daily Summary'!$H$11", "PASS", "Model target reconciliation"),
        ("Hard-cap test", "='Daily Summary'!$H$12", "PASS", "No settlement exceeds the configured cap"),
    ]
    for row, (label, formula, expected, purpose) in enumerate(controls, start=5):
        audit.cell(row, 1, label)
        audit.cell(row, 2, formula)
        audit.cell(row, 3, expected)
        audit.cell(row, 4, purpose)
        audit.cell(row, 2).fill = PatternFill("solid", fgColor=OUTPUT_GREEN)
        audit.cell(row, 3).fill = PatternFill("solid", fgColor=LIGHT_GREY)
        if isinstance(expected, (int, float)):
            audit.cell(row, 2).number_format = 'US$#,##0.00' if label == "Final USD" else "#,##0"
            audit.cell(row, 3).number_format = 'US$#,##0.00' if label == "Final USD" else "#,##0"
    audit.merge_cells("A13:D13")
    audit["A13"] = "Workbook/formula scope"
    audit["A13"].font = Font(size=13, bold=True, color=NAVY)
    audit.merge_cells("A14:D17")
    audit["A14"] = (
        "Settlement formulas are fully auditable for cap, damping, input amounts, check-ins, tasks and episode multipliers. "
        "Crown crossing rows are included for every episode, so changing Crown thresholds or values recalculates in-place. "
        "If the number of days, episodes per day or task milestones changes, rerun export_300usd_three_day_xlsx.py to rebuild the schedule."
    )
    audit["A14"].alignment = Alignment(wrap_text=True, vertical="top")
    audit["A14"].fill = PatternFill("solid", fgColor=LIGHT_BLUE)
    set_column_widths(audit, {"A": 27, "B": 22, "C": 24, "D": 64, "E": 4, "F": 4})
    audit.freeze_panes = "A5"

    for sheet in wb.worksheets:
        sheet.sheet_view.showGridLines = False
        sheet.sheet_properties.pageSetUpPr.fitToPage = True
        sheet.page_setup.fitToWidth = 1
        sheet.page_setup.fitToHeight = 0
        sheet.page_margins.left = 0.25
        sheet.page_margins.right = 0.25
        sheet.page_margins.top = 0.45
        sheet.page_margins.bottom = 0.45

    output.parent.mkdir(parents=True, exist_ok=True)
    wb.save(output)


def _chinese_font(size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", size, index=0)


def _draw_chinese_snapshot_charts() -> tuple[Path, Path]:
    """Create predictable Chinese chart snapshots for broad Excel/Calc compatibility.

    LibreOffice drops native chart ranges when formula-bearing sheets are renamed.
    The underlying calculation tables remain editable; these embedded visuals are
    deliberately labelled as default-parameter snapshots.
    """
    output_dir = Path("/tmp/joyedrama_excel_chart_snapshots")
    output_dir.mkdir(parents=True, exist_ok=True)
    line_path = output_dir / "daily_summary_cn.png"
    source_path = output_dir / "source_mix_cn.png"
    navy = "#172B4D"
    blue = "#2F75B5"
    coral = "#E9836D"
    grey = "#6B7280"
    light = "#EAF2F8"
    title_font = _chinese_font(28)
    label_font = _chinese_font(19)
    small_font = _chinese_font(16)

    canvas = PILImage.new("RGB", (1080, 560), "white")
    draw = ImageDraw.Draw(canvas)
    draw.text((35, 24), "累计美元结算曲线", font=title_font, fill=navy)
    left, top, right, bottom = 95, 100, 1000, 430
    for value in (0, 100, 200, 300):
        y = bottom - (value / 300) * (bottom - top)
        draw.line((left, y, right, y), fill="#D9E1F2", width=2)
        draw.text((15, y - 12), f"${value}", font=small_font, fill=grey)
    draw.line((left, top, left, bottom), fill=navy, width=3)
    draw.line((left, bottom, right, bottom), fill=navy, width=3)
    day_values = [129.60, 208.15, 290.00]
    points: list[tuple[int, int]] = []
    for index, amount in enumerate(day_values, start=1):
        x = left + (index - 1) * (right - left) // 2
        y = int(bottom - (amount / 300) * (bottom - top))
        points.append((x, y))
        draw.text((x - 26, bottom + 18), f"第 {index} 天", font=label_font, fill=grey)
        draw.text((x - 46, y - 35), f"${amount:.2f}", font=small_font, fill=navy)
    draw.line(points, fill=blue, width=6)
    for x, y in points:
        draw.ellipse((x - 9, y - 9, x + 9, y + 9), fill=coral, outline="white", width=3)
    draw.text((35, 488), "默认参数快照；修改黄色参数后请以“每日汇总”表格公式结果为准。", font=small_font, fill=grey)
    canvas.save(line_path)

    canvas = PILImage.new("RGB", (1080, 620), "white")
    draw = ImageDraw.Draw(canvas)
    draw.text((35, 24), "按奖励来源的入账美元", font=title_font, fill=navy)
    source_values = [
        ("单集奖励", 66.45, blue),
        ("追剧任务", 133.05, coral),
        ("新人奖励", 60.00, "#70AD47"),
        ("皇冠奖励", 18.50, "#8064A2"),
        ("签到", 6.00, "#FFC000"),
        ("钱包加速", 6.00, "#A5A5A5"),
    ]
    left, top, right, bottom = 210, 105, 1000, 520
    maximum = 140.0
    for tick in range(0, 141, 20):
        x = left + int((tick / maximum) * (right - left))
        draw.line((x, top, x, bottom), fill="#E7EDF4", width=1)
        draw.text((x - 12, bottom + 15), f"${tick}", font=small_font, fill=grey)
    row_height = 61
    for index, (label, amount, color) in enumerate(source_values):
        y = top + index * row_height
        draw.text((35, y + 8), label, font=label_font, fill=navy)
        width = max(2, int((amount / maximum) * (right - left)))
        draw.rounded_rectangle((left, y + 4, left + width, y + 38), radius=8, fill=color)
        draw.text((left + width + 14, y + 7), f"${amount:.2f}", font=small_font, fill=grey)
    draw.text((35, 565), "默认参数快照；来源构成和金额以“来源构成”表中的公式汇总为准。", font=small_font, fill=grey)
    canvas.save(source_path)
    return line_path, source_path


def localize_chinese(output: Path) -> None:
    """Translate labels, notes, charts and formula sheet references to Simplified Chinese."""
    wb = load_workbook(output, data_only=False)
    sheet_names = {
        "Read Me": "使用说明",
        "Inputs": "参数输入",
        "Settlement": "结算明细",
        "Daily Summary": "每日汇总",
        "Source Mix": "来源构成",
        "Audit": "审计检查",
    }
    formula_replacements = {
        "Inputs!": "'参数输入'!",
        "Settlement!": "'结算明细'!",
        "'Daily Summary'!": "'每日汇总'!",
        '"PASS"': '"通过"',
        '"CHECK"': '"需检查"',
    }
    direct = {
        "JoyeDrama · US$300 / 3-day reward model": "JoyeDrama · 300 美元 / 三日奖励模型",
        "Editable review workbook. The model is a high-engagement qualification path, not an expected or promised user earning.": "可编辑复核工作簿。本模型为高参与度资格路径，不代表用户平均收益或任何收益承诺。",
        "How to use": "使用方法",
        "1": "1",
        "2": "2",
        "3": "3",
        "4": "4",
        "Edit yellow cells only on the Inputs sheet.": "仅编辑“参数输入”页的黄色单元格。",
        "Review the formula-backed settlement order and damping in the Settlement sheet.": "在“结算明细”页复核按公式计算的结算顺序与阻尼。",
        "Use Daily Summary and Source Mix for management review; both sheets calculate from Settlement.": "管理复核请使用“每日汇总”和“来源构成”；两者均由“结算明细”计算。",
        "For changes to crown threshold timing or task counts, rerun the paired Python exporter to rebuild event rows.": "如修改皇冠触发时点或任务数量，请重新运行配套 Python 导出器以重建事件行。",
        "Review the formula-backed settlement\norder and damping in the Settlement\nsheet.": "在“结算明细”页复核按公式计算的结算顺序与阻尼。",
        "Use Daily Summary and Source Mix for\nmanagement review; both sheets\ncalculate from Settlement.": "管理复核请使用“每日汇总”和“来源构成”；两者均由“结算明细”计算。",
        "For changes to crown threshold timing\nor task counts, rerun the paired Python\nexporter to rebuild event rows.": "如修改皇冠触发时点或任务数量，请重新运行配套 Python 导出器以重建事件行。",
        "Model boundary": "模型边界",
        "This workbook mirrors the v1.7.0.4 server settlement order: reward events use the cumulative credited balance immediately before settlement to determine the damping multiplier, floor the credited units, and apply the 3,000,000-unit hard cap. The path assumes three consecutive days, 80 completed episodes per day, all configured watch tasks, daily check-in, ten Crown Road accelerations and ten wallet boosts each day.": "本工作簿复刻 v1.7.0.4 服务端结算顺序：每笔奖励在结算前按累计已入账余额确定阻尼系数，对结果向下取整后再应用 3,000,000 内部单位硬上限。该路径假设连续活跃三天、每日完成 80 集、完成全部配置的追剧任务、每日签到，并每天完成 10 次皇冠之路加速和 10 次钱包加速。",
        "Expected default result": "默认预期结果",
        "Final credited amount": "最终入账金额",
        "Lifetime cap": "生命周期上限",
        "Headroom": "上限余量",
        "Inputs · yellow cells are editable": "参数输入 · 黄色单元格可编辑",
        "All values mirror services/rewards/sql/029_v1700_300usd_three_day_curve.sql as of 2026-09-29.": "所有数值与 services/rewards/sql/029_v1700_300usd_three_day_curve.sql（截至 2026-09-29）保持一致。",
        "Core policy input": "核心策略参数",
        "Value": "数值",
        "Internal units per US$": "每 1 美元对应内部单位",
        "Hard cap (units)": "硬上限（内部单位）",
        "Qualification target (units)": "资格路径目标（内部单位）",
        "Welcome reward (units)": "新人奖励（内部单位）",
        "Model days": "模型天数",
        "Episodes per day": "每日剧集数",
        "Base episode reward (units)": "单集基础奖励（内部单位）",
        "Crown points per episode": "每集皇冠积分",
        "Wallet boost reward (units)": "钱包加速奖励（内部单位）",
        "Wallet boosts per day": "每日钱包加速次数",
        "10,000 internal units = US$1.00": "10,000 内部单位 = 1.00 美元",
        "All reward sources share this lifetime cap": "所有奖励来源共享此生命周期上限",
        "Expected high-engagement result on day 3": "高参与度路径在第 3 天的预期结果",
        "One-time reward; displayed value derives from the current market rate": "一次性奖励；展示金额按当前市场兑换率计算",
        "Three consecutive active days": "连续活跃三天",
        "Qualification path assumption": "资格路径假设",
        "Before Crown Road multiplier": "皇冠之路倍率生效前",
        "Used to detect Crown Road thresholds": "用于判断皇冠之路触发阈值",
        "One rewarded wallet boost": "一次激励广告钱包加速",
        "Policy default = one": "策略默认值为 1 次",
        "Daily check-in rewards": "每日签到奖励",
        "Day": "天数",
        "Units": "内部单位",
        "USD equivalent": "美元等值",
        "Damping curve": "阻尼曲线",
        "Threshold (units)": "阈值（内部单位）",
        "Multiplier": "倍率",
        "Crown Road": "皇冠之路",
        "Trigger points": "触发积分",
        "Reward (units)": "奖励（内部单位）",
        "Episode multiplier": "单集倍率",
        "Watch-task episode": "追剧任务集数",
        "Settlement schedule": "结算明细",
        "Formula-backed event-level settlement; blue headers are outputs and yellow inputs live on the Inputs tab.": "按公式计算的逐事件结算表；蓝色为输出字段，黄色可编辑参数位于“参数输入”页。",
        "Row": "行号",
        "Source group": "奖励来源",
        "Event": "事件",
        "Episode in day": "当日剧集",
        "Lifetime episode": "累计剧集",
        "Raw units": "原始单位",
        "Prior credited units": "结算前累计",
        "Damping": "阻尼",
        "Potential units": "阻尼后金额",
        "Cap remaining": "剩余上限",
        "Credited units": "实际入账",
        "Cumulative units": "累计入账",
        "Cumulative USD": "累计美元",
        "Notes": "说明",
        "Static verification snapshot": "静态核对快照",
        "Raw": "原始",
        "Prior": "结算前",
        "Potential": "阻尼后",
        "Cap remaining": "剩余上限",
        "Credited": "入账",
        "Cumulative": "累计",
        "USD": "美元",
        "Daily outcome": "每日结果",
        "The summary uses Settlement formulas and therefore updates when editable Inputs change.": "本页由“结算明细”公式汇总；修改可编辑参数后会随之更新。",
        "Raw units": "原始单位",
        "Credited units": "实际入账",
        "End-of-day units": "日末累计",
        "End-of-day USD": "日末累计美元",
        "Target completion": "目标完成度",
        "Daily credited USD": "当日入账美元",
        "Required actions": "所需动作",
        "Result note": "结果说明",
        "Final credited units": "最终入账单位",
        "Final credited USD": "最终入账美元",
        "US$300 cap headroom": "300 美元上限余量",
        "Headroom in USD": "美元余量",
        "Validation": "校验",
        "Formula": "公式结果",
        "Target result": "目标结果",
        "Hard cap": "硬上限",
        "Result below cap": "结果低于上限",
        "Cumulative USD settlement": "累计美元结算曲线",
        "US$": "美元",
        "Reward source mix": "奖励来源构成",
        "Credited source contribution is calculated from the event-level settlement schedule.": "各奖励来源的实际贡献由逐事件结算明细汇总计算。",
        "Credited USD": "入账美元",
        "Share of credited units": "实际入账占比",
        "Interpretation": "说明",
        "Total": "合计",
        "Credited USD by reward source": "按奖励来源的入账美元",
        "Reward source": "奖励来源",
        "Welcome reward": "新人奖励",
        "One-time welcome reward": "一次性新人奖励",
        "Daily sign-in reward": "每日签到奖励",
        "Model audit": "模型审计",
        "Formula checks and direct reproduction checks. Green values must read PASS under the default inputs.": "公式检查与直接复刻检查。默认参数下绿色结果必须显示“通过”。",
        "Control": "检查项",
        "Formula result": "公式结果",
        "Expected static result": "静态预期结果",
        "Purpose": "用途",
        "Final USD": "最终美元",
        "Cap units": "上限单位",
        "Headroom units": "余量单位",
        "Target test": "目标测试",
        "Hard-cap test": "硬上限测试",
        "Workbook/formula scope": "工作簿 / 公式范围",
        "Settlement formulas are fully auditable for cap, damping, input amounts, check-ins, tasks and episode multipliers. Crown crossing rows are included for every episode, so changing Crown thresholds or values recalculates in-place. If the number of days, episodes per day or task milestones changes, rerun export_300usd_three_day_xlsx.py to rebuild the schedule.": "结算公式可完整审计硬上限、阻尼、输入金额、签到、追剧任务和单集倍率。每一集均包含皇冠条件检查行，因此修改皇冠阈值或奖励值后可在表内重新计算。如需调整模型天数、每日剧集数或任务里程碑，请重新运行 export_300usd_three_day_xlsx.py 重建结算明细。",
        "welcome": "新人奖励",
        "checkin": "签到",
        "crown": "皇冠奖励",
        "watch_task": "追剧任务",
        "episode_claim": "单集奖励",
        "wallet_boost": "钱包加速",
        "Tier 1": "第 1 档",
        "Tier 2": "第 2 档",
        "Tier 3": "第 3 档",
        "Tier 4": "第 4 档",
        "Tier 5": "第 5 档",
        "Tier 6": "第 6 档",
        "BRONZE": "青铜",
        "SILVER": "白银",
        "GOLD": "黄金",
        "DIAMOND": "钻石",
        "CROWN": "王冠",
        "One-time onboarding reward": "一次性新人引导奖励",
        "Daily active-day reward": "每日活跃奖励",
        "Natural Crown Road milestones; no optional Crown ads": "自然达成的皇冠之路里程碑；不含可选皇冠广告",
        "Daily episode task milestones": "每日剧集任务里程碑",
        "Aggregated episode watching reward": "聚合领取的剧集观看奖励",
        "One optional wallet acceleration per active day": "每个活跃日 1 次可选钱包加速",
        "Matches the agreed high-engagement qualification result": "与已确认的高参与度资格路径结果一致",
        "Uses 10,000 internal units per US$1.00": "按 10,000 内部单位 = 1.00 美元换算",
        "Hard lifetime reward ceiling": "生命周期奖励硬上限",
        "Room below the US$300 cap": "距离 300 美元上限的余量",
        "Model target reconciliation": "模型目标对账",
        "No settlement exceeds the configured cap": "所有结算均未超过配置上限",
        "PASS": "通过",
        "CHECK": "需检查",
        "Un-damped": "未进入阻尼",
        "Damping begins after 2,900,000 units": "达到 2,900,000 单位后的下一笔奖励开始进入阻尼",
        "80 episodes · 14 task milestones · 1 check-in · 10 wallet boosts": "80 集 · 14 个任务里程碑 · 1 次签到 · 10 次钱包加速",
    }
    crown_names = {
        "BRONZE": "青铜",
        "SILVER": "白银",
        "GOLD": "黄金",
        "DIAMOND": "钻石",
        "CROWN": "王冠",
    }
    for worksheet in wb.worksheets:
        for row in worksheet.iter_rows():
            for cell in row:
                if isinstance(cell.value, str):
                    if cell.value.startswith("="):
                        formula = cell.value
                        for source, target in formula_replacements.items():
                            formula = formula.replace(source, target)
                        cell.value = formula
                    else:
                        value = direct.get(cell.value, cell.value)
                        if value.startswith("Daily check-in · day "):
                            value = "每日签到 · 第 " + value.rsplit(" ", 1)[-1] + " 天"
                        elif value.startswith("Crown check · "):
                            code = value.rsplit(" · ", 1)[-1]
                            value = "皇冠条件检查 · " + crown_names.get(code, code)
                        elif value.startswith("Crown active ad · "):
                            value = "皇冠主动加速 · 第 " + value.rsplit(" · ", 1)[-1] + " 次"
                        elif value.startswith("Watch task · episode "):
                            value = "追剧任务 · 第 " + value.rsplit(" ", 1)[-1] + " 集"
                        elif value.startswith("Episode claim · episode "):
                            value = "单集奖励 · 第 " + value.rsplit(" ", 1)[-1] + " 集"
                        elif value.startswith("Wallet ad boost · "):
                            value = "钱包广告加速 · 第 " + value.rsplit(" · ", 1)[-1] + " 次"
                        elif value == "Awards when the crown threshold is crossed":
                            value = "达到皇冠积分阈值时发放奖励"
                        elif value == "Adds Crown Road progress only; no direct wallet reward":
                            value = "仅增加皇冠之路进度，不直接发放现金奖励"
                        elif value == "Task milestone reward; zero outside milestones":
                            value = "追剧任务里程碑奖励；非里程碑时为零"
                        elif value == "Daily aggregated episode reward claim":
                            value = "每日聚合领取的单集观看奖励"
                        elif value == "One of ten wallet boosts per active day":
                            value = "每日 10 次钱包加速中的一次"
                        cell.value = value
                if cell.font and cell.font.name != "Noto Sans CJK SC":
                    font = copy(cell.font)
                    font.name = "Noto Sans CJK SC"
                    cell.font = font
    for old, new in sheet_names.items():
        wb[old].title = new
    for worksheet in wb.worksheets:
        for chart in getattr(worksheet, "_charts", []):
            if chart.title and chart.title.tx and chart.title.tx.rich:
                for paragraph in chart.title.tx.rich.p:
                    for run in paragraph.r:
                        if run.t in direct:
                            run.t = direct[run.t]
            if chart.x_axis.title and chart.x_axis.title.tx and chart.x_axis.title.tx.rich:
                for paragraph in chart.x_axis.title.tx.rich.p:
                    for run in paragraph.r:
                        if run.t in direct:
                            run.t = direct[run.t]
            if chart.y_axis.title and chart.y_axis.title.tx and chart.y_axis.title.tx.rich:
                for paragraph in chart.y_axis.title.tx.rich.p:
                    for run in paragraph.r:
                        if run.t in direct:
                            run.t = direct[run.t]
    daily_snapshot, source_snapshot = _draw_chinese_snapshot_charts()
    daily = wb["每日汇总"]
    source = wb["来源构成"]
    daily._charts = []
    source._charts = []
    daily_image = ExcelImage(str(daily_snapshot))
    daily_image.width = 520
    daily_image.height = 270
    daily.add_image(daily_image, "A16")
    source_image = ExcelImage(str(source_snapshot))
    source_image.width = 520
    source_image.height = 300
    source.add_image(source_image, "A15")
    wb.calculation.fullCalcOnLoad = True
    wb.calculation.forceFullCalc = True
    wb.calculation.calcMode = "auto"
    wb.save(output)


def validate_output(output: Path, locale: str) -> None:
    wb = load_workbook(output, data_only=False)
    expected_sheets = (
        ["使用说明", "参数输入", "结算明细", "每日汇总", "来源构成", "审计检查"]
        if locale == "zh-CN"
        else ["Read Me", "Inputs", "Settlement", "Daily Summary", "Source Mix", "Audit"]
    )
    if wb.sheetnames != expected_sheets:
        raise RuntimeError(f"Unexpected sheets: {wb.sheetnames}")
    settlement = wb["结算明细" if locale == "zh-CN" else "Settlement"]
    if settlement.max_row < 1_000:
        raise RuntimeError("Settlement schedule is unexpectedly short")
    if settlement["M1271"].value is None:
        raise RuntimeError("Settlement cumulative formula is missing")
    summary = wb["每日汇总" if locale == "zh-CN" else "Daily Summary"]
    if summary["B10"].value != "=D7":
        raise RuntimeError("Daily Summary final-unit formula is missing")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=OUTPUT_DEFAULT)
    parser.add_argument("--locale", choices=("en", "zh-CN"), default="zh-CN")
    args = parser.parse_args()
    build_workbook(args.output)
    if args.locale == "zh-CN":
        localize_chinese(args.output)
    validate_output(args.output, args.locale)
    print(args.output.resolve())


if __name__ == "__main__":
    main()
