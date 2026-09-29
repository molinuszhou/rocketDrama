#!/usr/bin/env python3
"""Export the v1.7.0.0 qualification path as an auditable Excel workbook.

The source of truth for the economics remains model_300usd_three_day.py and the
SQL migration. This exporter supplies an editable, formula-backed workbook for
product and operations review; it never writes production policy.
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from decimal import Decimal, ROUND_DOWN
from pathlib import Path
from typing import Callable

from openpyxl import Workbook, load_workbook
from openpyxl.chart import BarChart, LineChart, Reference
from openpyxl.formatting.rule import DataBarRule
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

sys.path.insert(0, str(Path(__file__).resolve().parent))
import model_300usd_three_day as model  # noqa: E402


OUTPUT_DEFAULT = Path("docs/reward-model-300usd-3days/JoyeDrama_Reward_Model_USD300_3Days.xlsx")

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
    lifetime_episode = 0
    pending_episode_claims: list[tuple[int, int, int, str]] = []

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
        for episode_in_day in range(1, model.EPISODES_PER_DAY + 1):
            lifetime_episode += 1
            # Matches RewardsService ordering: this episode's cash is calculated
            # before the Crown milestone crossed by this episode is credited.
            episode_multiplier = model.current_episode_multiplier(earned_crowns)
            claim_raw = floor_units(Decimal(model.BASE_EPISODE_REWARD) * episode_multiplier)
            pending_episode_claims.append((day, episode_in_day, lifetime_episode, str(episode_multiplier)))
            current_points = lifetime_episode * model.CROWN_EPISODE_PROGRESS_POINTS
            prior_points = (lifetime_episode - 1) * model.CROWN_EPISODE_PROGRESS_POINTS
            for code, threshold, reward_units, _ in model.CROWN_LEVELS:
                input_row = crown_row_map[code]
                raw = reward_units if code not in earned_crowns and current_points >= threshold and prior_points < threshold else 0
                if raw:
                    earned_crowns.add(code)
                formula = (
                    f"=IF(AND($F{{row}}*Inputs!$C$12>=Inputs!$L${input_row},"
                    f"($F{{row}}-1)*Inputs!$C$12<Inputs!$L${input_row}),Inputs!$M${input_row},0)"
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
                f"IF(($F{{row}}-1)*Inputs!$C$12>=Inputs!$L${crown_row_map[code]},Inputs!$N${crown_row_map[code]},1)"
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
                    "One wallet boost per active day",
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
        "This workbook mirrors the v1.7.0.0 server settlement order: reward events use the cumulative credited "
        "balance immediately before settlement to determine the damping multiplier, floor the credited units, and "
        "apply the 3,000,000-unit hard cap. The path assumes three consecutive days, 60 completed episodes per day, "
        "all configured watch tasks, daily check-in and one wallet boost each day. It excludes optional Crown Road ads."
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
    action_note = "60 episodes · 14 task milestones · 1 check-in · 1 wallet boost"
    for row, day in enumerate(range(1, model.DAYS + 1), start=5):
        daily.cell(row, 1, day)
        daily.cell(row, 2, f'=SUMIF(Settlement!$B$5:$B${last},A{row},Settlement!$G$5:$G${last})')
        daily.cell(row, 3, f'=SUMIF(Settlement!$B$5:$B${last},A{row},Settlement!$L$5:$L${last})')
        daily.cell(row, 4, f'=SUMIFS(Settlement!$L$5:$L${last},Settlement!$B$5:$B${last},"<="&A{row})')
        daily.cell(row, 5, f'=D{row}/Inputs!$C$5')
        daily.cell(row, 6, f'=D{row}/Inputs!$C$6')
        daily.cell(row, 7, f'=C{row}/Inputs!$C$5')
        daily.cell(row, 8, action_note)
        daily.cell(row, 9, "Un-damped" if day < 3 else "Damping begins above 2,650,000 units")
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


def validate_output(output: Path) -> None:
    wb = load_workbook(output, data_only=False)
    expected_sheets = ["Read Me", "Inputs", "Settlement", "Daily Summary", "Source Mix", "Audit"]
    if wb.sheetnames != expected_sheets:
        raise RuntimeError(f"Unexpected sheets: {wb.sheetnames}")
    settlement = wb["Settlement"]
    if settlement.max_row < 1_000:
        raise RuntimeError("Settlement schedule is unexpectedly short")
    if settlement["M1271"].value is None:
        raise RuntimeError("Settlement cumulative formula is missing")
    if wb["Daily Summary"]["B10"].value != "=D7":
        raise RuntimeError("Daily Summary final-unit formula is missing")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=OUTPUT_DEFAULT)
    args = parser.parse_args()
    build_workbook(args.output)
    validate_output(args.output)
    print(args.output.resolve())


if __name__ == "__main__":
    main()
