#!/usr/bin/env python3
"""Export the v1.7.0.5 US$300 reward policy in the v1.6.5.1 ladder-model format.

This workbook follows the established JoyeDrama modelling pattern:
blue editable Inputs -> hidden event-level Engine sheets -> Daily scenario sheets
-> Summary, Milestones and Ad_Points. It is an auditable qualification-path
model and never modifies production policy.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from openpyxl import Workbook, load_workbook
from openpyxl.formatting.rule import DataBarRule
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

sys.path.insert(0, str(Path(__file__).resolve().parent))
import model_300usd_three_day as model  # noqa: E402


OUTPUT_DEFAULT = Path("docs/reward-model-300usd-3days/JoyeDrama_三日300美元_阶梯充能广告测算模型.xlsx")
MAX_DAYS = 30
MAX_EPISODES_PER_DAY = 100
MAX_CROWN_ACTIVE_ADS_PER_DAY = 10
MAX_WALLET_BOOSTS_PER_DAY = 10
# welcome + check-in + active Crown ads + crown/task and claim per episode + wallet boosts
EVENTS_PER_DAY = 2 + MAX_CROWN_ACTIVE_ADS_PER_DAY + MAX_EPISODES_PER_DAY * 2 + MAX_WALLET_BOOSTS_PER_DAY
FIRST_ENGINE_ROW = 10

NAVY = "16324F"
TEXT = "1B2A3A"
INPUT_BLUE = "DDEBF7"
FORMULA_GREEN = "008000"
PALE_GREEN = "DFF3EA"
PALE_BLUE = "EAF3FB"
PALE_YELLOW = "FFF4D8"
PALE_ORANGE = "FCE4D6"
WHITE = "FFFFFF"
GRID = "D9E2F3"
THIN = Side(style="thin", color=GRID)

# Inputs sheet row positions. Keeping addresses explicit makes formula audit simple.
R = {
    "summary_days": 9,
    "milestone_days": 10,
    "max_days": 11,
    "max_episodes": 12,
    "episode_minutes": 13,
    "hours_1": 14,
    "hours_15": 15,
    "hours_2": 16,
    "rv_completion": 17,
    "units_per_usd": 21,
    "cap": 22,
    "target": 23,
    "welcome": 28,
    "welcome_rv": 29,
    "base_episode": 30,
    "wallet_boost": 31,
    "wallet_limit": 32,
    "free_episodes": 36,
    "unlock_bundle": 37,
    "task_rv": 38,
    "claim_rv": 39,
    "checkin_rv": 40,
    "checkin_rate": 41,
    "pack_interstitial": 42,
    "crown_active_rv": 43,
    "wallet_active_rv": 44,
    "checkin_start": 49,
    "task_start": 59,
    "crown_start": 76,
    "crown_episode_points": 73,
    "crown_active_points": 74,
    "damping_start": 85,
}

SCENARIOS = (
    ("1.0小时", "Daily_scenario_1_hours", "Engine_scenario_1_hours", R["hours_1"]),
    ("1.5小时", "Daily_scenario_2_hours", "Engine_scenario_2_hours", R["hours_15"]),
    ("2.0小时", "Daily_scenario_3_hours", "Engine_scenario_3_hours", R["hours_2"]),
)


def fill(color: str) -> PatternFill:
    return PatternFill("solid", fgColor=color)


def formula_font() -> Font:
    return Font(name="Noto Sans CJK SC", color=FORMULA_GREEN)


def text_font(bold: bool = False, color: str = TEXT, size: int | None = None) -> Font:
    return Font(name="Noto Sans CJK SC", bold=bold, color=color, size=size)


def apply_grid(ws, start_row: int, end_row: int, start_col: int, end_col: int) -> None:
    for row in ws.iter_rows(min_row=start_row, max_row=end_row, min_col=start_col, max_col=end_col):
        for cell in row:
            cell.border = Border(bottom=THIN)
            cell.alignment = Alignment(vertical="center", wrap_text=True)


def title(ws, text: str, subtitle: str, end_col: int) -> None:
    ws.merge_cells(start_row=3, start_column=3, end_row=3, end_column=end_col)
    ws.cell(3, 3, text)
    ws.cell(3, 3).fill = fill(NAVY)
    ws.cell(3, 3).font = text_font(bold=True, color=WHITE, size=15)
    ws.cell(3, 3).alignment = Alignment(vertical="center")
    ws.row_dimensions[3].height = 28
    ws.merge_cells(start_row=4, start_column=3, end_row=4, end_column=end_col)
    ws.cell(4, 3, subtitle)
    ws.cell(4, 3).font = text_font(color="52616B", size=10)
    ws.cell(4, 3).alignment = Alignment(vertical="center", wrap_text=True)
    ws.row_dimensions[4].height = 30


def section(ws, row: int, text: str, end_col: int) -> None:
    ws.merge_cells(start_row=row, start_column=3, end_row=row, end_column=end_col)
    cell = ws.cell(row, 3, text)
    cell.fill = fill(PALE_GREEN)
    cell.font = text_font(bold=True)
    cell.alignment = Alignment(vertical="center")


def headers(ws, row: int, labels: list[str], start_col: int = 3) -> None:
    for index, label in enumerate(labels, start=start_col):
        cell = ws.cell(row, index, label)
        cell.fill = fill(NAVY)
        cell.font = text_font(bold=True, color=WHITE)
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
        cell.border = Border(bottom=THIN)
    ws.row_dimensions[row].height = 26


def set_widths(ws, widths: dict[str, float]) -> None:
    for column, width in widths.items():
        ws.column_dimensions[column].width = width


def input_row(ws, row: int, label: str, value, unit: str, category: str, note: str, number_format: str = "#,##0") -> None:
    ws.cell(row, 3, label).font = text_font(bold=True)
    target = ws.cell(row, 4, value)
    target.fill = fill(INPUT_BLUE)
    target.font = text_font(bold=True)
    target.number_format = number_format
    ws.cell(row, 5, unit).font = text_font()
    ws.cell(row, 6, category).font = text_font()
    ws.cell(row, 7, note).font = text_font()


def formulas_for_engine(row: int, phase: str) -> tuple[str, ...]:
    """Return H:S formulas for one server-ordered settlement event."""
    # H raw units, I prior, J damping, K potential, L cap remaining, M credited,
    # N cumulative, O USD, P/RV content, Q/RV tasks, R/RV reward center, S/interstitial.
    prior = "=0" if row == FIRST_ENGINE_ROW else f"=$N${row - 1}"
    damping = (
        f'=IF($I{row}<Inputs!$D${R["damping_start"]},Inputs!$E${R["damping_start"]},'
        f'IF($I{row}<Inputs!$D${R["damping_start"] + 1},Inputs!$E${R["damping_start"] + 1},'
        f'IF($I{row}<Inputs!$D${R["damping_start"] + 2},Inputs!$E${R["damping_start"] + 2},'
        f'IF($I{row}<Inputs!$D${R["damping_start"] + 3},Inputs!$E${R["damping_start"] + 3},'
        f'IF($I{row}<Inputs!$D${R["damping_start"] + 4},Inputs!$E${R["damping_start"] + 4},Inputs!$E${R["damping_start"] + 5})))))'
    )
    potential = f"=ROUNDDOWN($H{row}*$J{row},0)"
    cap_remaining = f"=MAX(0,Inputs!$D${R['cap']}-$I{row})"
    credited = f"=MIN($K{row},$L{row})"
    cumulative = f"=$I{row}+$M{row}"
    usd = f"=$N{row}/Inputs!$D${R['units_per_usd']}"
    crown_active_count = f'COUNTIF($E${FIRST_ENGINE_ROW}:$E{row},"皇冠主动加速")'
    crown_active_today_count = f'COUNTIFS($D${FIRST_ENGINE_ROW}:$D{row},$D{row},$E${FIRST_ENGINE_ROW}:$E{row},"皇冠主动加速")'
    wallet_boost_count = f'COUNTIFS($D${FIRST_ENGINE_ROW}:$D{row},$D{row},$E${FIRST_ENGINE_ROW}:$E{row},"钱包加速")'
    points_before = f'(($G{row}-1)*Inputs!$I${R["crown_episode_points"]}+{crown_active_count}*Inputs!$I${R["crown_active_points"]})'
    points_after = f'($G{row}*Inputs!$I${R["crown_episode_points"]}+{crown_active_count}*Inputs!$I${R["crown_active_points"]})'
    if phase == "欢迎奖励":
        raw = f'=IF($D{row}=1,Inputs!$D${R["welcome"]},0)'
        content_rv = f'=IF($T{row}>0,ROUNDUP(MAX(0,$T{row}-Inputs!$D${R["free_episodes"]})/Inputs!$D${R["unlock_bundle"]},0)+IF($D{row}=1,Inputs!$D${R["welcome_rv"]},0),0)'
        task_rv = "=0"
        reward_center_rv = "=0"
        interstitial = f'=ROUND(ROUNDUP(MAX(0,$T{row}-Inputs!$D${R["free_episodes"]})/Inputs!$D${R["unlock_bundle"]},0)*Inputs!$D${R["pack_interstitial"]},0)'
    elif phase == "日初签到":
        raw = f'=IFERROR(ROUNDDOWN(INDEX(Inputs!$D${R["checkin_start"]}:$D${R["checkin_start"] + 6},MOD($D{row}-1,7)+1)*Inputs!$D${R["checkin_rate"]},0),0)'
        content_rv = f'=IF($H{row}>0,Inputs!$D${R["checkin_rv"]},0)'
        task_rv = "=0"
        reward_center_rv = "=0"
        interstitial = "=0"
    elif phase == "皇冠主动加速":
        raw = "=0"
        content_rv = "=0"
        task_rv = "=0"
        reward_center_rv = f'=IF({crown_active_today_count}<=Inputs!$D${R["crown_active_rv"]},1,0)'
        interstitial = "=0"
    elif phase == "皇冠/任务":
        raw = (
            f'=IF($F{row}<=$T{row},'
            f'SUMPRODUCT(--(({points_before})<Inputs!$D${R["crown_start"]}:$D${R["crown_start"] + 4}),'
            f'--(({points_after})>=Inputs!$D${R["crown_start"]}:$D${R["crown_start"] + 4}),Inputs!$E${R["crown_start"]}:$E${R["crown_start"] + 4})+'
            f'SUMIF(Inputs!$C${R["task_start"]}:$C${R["task_start"] + 13},$F{row},Inputs!$D${R["task_start"]}:$D${R["task_start"] + 13}),0)'
        )
        task_rv = f'=IF(AND($F{row}<=$T{row},SUMIF(Inputs!$C${R["task_start"]}:$C${R["task_start"] + 13},$F{row},Inputs!$D${R["task_start"]}:$D${R["task_start"] + 13})>0),Inputs!$D${R["task_rv"]},0)'
        content_rv = task_rv
        reward_center_rv = "=0"
        interstitial = "=0"
    elif phase == "分集领取":
        multiplier = (
            f'IF(({points_before})>=Inputs!$D${R["crown_start"] + 4},Inputs!$F${R["crown_start"] + 4},'
            f'IF(({points_before})>=Inputs!$D${R["crown_start"] + 3},Inputs!$F${R["crown_start"] + 3},'
            f'IF(({points_before})>=Inputs!$D${R["crown_start"] + 2},Inputs!$F${R["crown_start"] + 2},'
            f'IF(({points_before})>=Inputs!$D${R["crown_start"] + 1},Inputs!$F${R["crown_start"] + 1},'
            f'IF(({points_before})>=Inputs!$D${R["crown_start"]},Inputs!$F${R["crown_start"]},1)))))'
        )
        raw = f'=IF($F{row}<=$T{row},ROUNDDOWN(Inputs!$D${R["base_episode"]}*{multiplier},0),0)'
        content_rv = f'=IF(AND($F{row}=1,$T{row}>0),Inputs!$D${R["claim_rv"]},0)'
        task_rv = "=0"
        reward_center_rv = "=0"
        interstitial = "=0"
    elif phase == "钱包加速":
        raw = f'=IF({wallet_boost_count}<=Inputs!$D${R["wallet_limit"]},Inputs!$D${R["wallet_boost"]},0)'
        content_rv = "=0"
        task_rv = "=0"
        reward_center_rv = f'=IF($H{row}>0,1,0)'
        interstitial = "=0"
    else:
        raise ValueError(phase)
    return raw, prior, damping, potential, cap_remaining, credited, cumulative, usd, content_rv, task_rv, reward_center_rv, interstitial


def build_readme(ws) -> None:
    title(ws, "JoyeDrama v1.7.0.5 — 三日300美元阶梯充能广告测算模型", "公式驱动模型：仅修改 Inputs 蓝色单元格；隐藏 Engine 逐笔结算层和展示表会自动重算。", 7)
    section(ws, 6, "使用方法", 7)
    notes = [
        "1. 仅修改 Inputs 中蓝色单元格。当前模型将服务端 300 美元上限、三日 290 美元高参与度资格路径、皇冠之路与阻尼参数集中在该页。",
        "2. 在 Excel 中按 Ctrl+Alt+F9，或在 LibreOffice 中按 Ctrl+Shift+F9 / 保存后重新打开，确保公式重新计算。工作簿已设置为自动计算。",
        f"3. 模型预生成 {MAX_DAYS} 天 × 3 个时长情景的逐笔计算行；每日最多支持 {MAX_EPISODES_PER_DAY} 集。不要将 Inputs 的范围参数设置得更高。",
        "4. Summary 显示前 N 天累计表现；Milestones 显示达到 290 万资格目标和 300 万硬上限的时间、现金与广告数量；Ad_Points 显示广告频率；三张 Daily 表展示逐日明细。",
        "5. Engine_* 隐藏表按“新人奖励 → 七日循环签到 → 皇冠主动加速 → 每集皇冠/任务 → 日末逐集领取 → 钱包加速”顺序逐笔应用六档阻尼。它是必要计算层，请勿删除。",
        "6. 本模型是高参与度资格路径测算工具，不代表平均用户收益，也不构成任何对外收益承诺。服务端账本、广告回调、异常中断和地区合规限制仍以实际产品实现为准。",
    ]
    for row, note in enumerate(notes, start=7):
        ws.cell(row, 3, note).font = text_font()
        ws.merge_cells(start_row=row, start_column=3, end_row=row, end_column=7)
        ws.cell(row, 3).alignment = Alignment(wrap_text=True, vertical="top")
        ws.row_dimensions[row].height = 32
    section(ws, 15, "当前默认规则", 7)
    headers(ws, 16, ["模块", "默认规则", "与服务端口径"], 3)
    rules = [
        ("目标", "三日高参与度路径结算显示 US$290.00；生命周期硬上限 3,000,000 单位（US$300）。", "迁移 034_v1705_high_episode_wallet_rewards.sql"),
        ("新人奖励", "首日 600,000 内部单位，并统计 1 条内容流激励广告。", "welcome_rewards / market_wallet_policy"),
        ("追剧任务", "每日 1、3、6、9、12、15、18、24、30、36、42、48、54、60 集为固定金额节点；每个节点 1 条激励广告。", "reward_policy 的四档固定任务金额"),
        ("皇冠与钱包", "皇冠主动加速与钱包加速均为每天 10 次；每条皇冠广告增加 1 点成长积分，钱包每条奖励 5,500 单位。", "Crown Road / wallet redpacket policy"),
        ("阻尼", "US$290（2,900,000 单位）前全额按 1.00x 结算；达到后从下一笔奖励起依次采用 0.50x / 0.25x / 0.12x / 0.06x / 0.01x，直至 3,000,000 上限。", "reward_policy gems_earned_cap 与 damping tiers"),
        ("广告", "前 3 集免费；每条解锁激励连续解锁 2 集；集包插屏系数 0.5；不在本模型中叠加播放时长计时插屏。", "用于运营频率估算，实际展示以客户端频控为准"),
    ]
    for row, values in enumerate(rules, start=17):
        for col, value in enumerate(values, start=3):
            ws.cell(row, col, value).font = text_font(bold=col == 3)
        ws.row_dimensions[row].height = 42
    apply_grid(ws, 16, 22, 3, 5)
    set_widths(ws, {"A": 3, "B": 3, "C": 18, "D": 70, "E": 42, "F": 4, "G": 4})


def build_inputs(ws) -> None:
    title(ws, "JoyeDrama v1.7.0.5 — 三日300美元阶梯充能广告参数", "蓝色 = 可调输入；绿色 = 公式结果。修改蓝色单元格后请重新计算工作簿。", 7)
    section(ws, 6, "模型范围与用户情景", 7)
    headers(ws, 8, ["参数", "当前值", "单位", "分类", "说明"], 3)
    input_row(ws, R["summary_days"], "汇总统计天数", 3, "天", "模型范围", "Summary 统计前 N 天累计表现；三日资格目标默认使用 3。")
    input_row(ws, R["milestone_days"], "里程碑测算范围", MAX_DAYS, "天", "模型范围", f"Daily 与 Engine 表预生成 {MAX_DAYS} 天；不得设得更高。")
    input_row(ws, R["max_days"], "预生成最大天数", MAX_DAYS, "天", "模型范围", "工作簿容量说明；不要修改。")
    input_row(ws, R["max_episodes"], "单日最大测算集数", MAX_EPISODES_PER_DAY, "集/日", "模型范围", "逐笔 Engine 每日预留 100 集；不要设置超过 100。")
    input_row(ws, R["episode_minutes"], "平均单集时长", 1.5, "分钟/集", "用户情景", "决定三种时长情景对应的有效观看集数。", "0.0")
    input_row(ws, R["hours_1"], "情景 A 日观看时长", 1.0, "小时/日", "用户情景", "轻度观看情景。", "0.0")
    input_row(ws, R["hours_15"], "情景 B 日观看时长", 1.5, "小时/日", "用户情景", "中度观看情景。", "0.0")
    input_row(ws, R["hours_2"], "情景 C 日观看时长", 2.0, "小时/日", "用户情景", "目标资格路径：80 集/日。", "0.0")
    input_row(ws, R["rv_completion"], "激励广告完成率", 1.0, "%", "用户情景", "用于由完整观看 RV 换算请求量；奖励结算按完整观看计算。", "0.0%")

    section(ws, 19, "内部单位、上限与目标", 7)
    headers(ws, 20, ["参数", "当前值", "单位", "分类", "说明"], 3)
    input_row(ws, R["units_per_usd"], "每 10,000 内部单位美元参考", 1.0, "美元", "钱包换算", "当前 DEFAULT / USD 市场换算口径。", '$#,##0.00')
    input_row(ws, R["cap"], "生命周期硬上限", model.CAP_UNITS, "内部单位", "奖励上限", "US$300 = 3,000,000 内部单位。")
    input_row(ws, R["target"], "三日资格路径目标", 2_900_000, "内部单位", "奖励目标", "高参与度路径默认第 3 日达到 US$290。")

    section(ws, 26, "基础现金奖励与领取行为", 7)
    headers(ws, 27, ["参数", "当前值", "单位", "分类", "说明"], 3)
    input_row(ws, R["welcome"], "新人欢迎奖励", model.WELCOME_UNITS, "内部单位", "现金奖励", "默认首日完整领取。")
    input_row(ws, R["welcome_rv"], "新人欢迎奖励激励广告", 1, "条/首日", "广告统计", "用于统计首日内容流 RV。")
    input_row(ws, R["base_episode"], "单集基础现金奖励", model.BASE_EPISODE_REWARD, "内部单位/集", "现金奖励", "皇冠等级会影响每集原始奖励。")
    input_row(ws, R["wallet_boost"], "钱包加速单次奖励", model.WALLET_BOOST_UNITS, "内部单位/条", "现金奖励", "每日可见的钱包广告加速奖励。")
    input_row(ws, R["wallet_limit"], "每日钱包加速上限", model.WALLET_BOOSTS_PER_DAY, "条/日", "现金奖励", "资格路径默认每天 10 次。")

    section(ws, 34, "解锁、每日追剧任务与广告频率", 7)
    headers(ws, 35, ["参数", "当前值", "单位", "分类", "说明"], 3)
    input_row(ws, R["free_episodes"], "前置免费集数", 3, "集", "内容解锁", "前 3 集无需激励广告解锁。")
    input_row(ws, R["unlock_bundle"], "每条解锁激励连续解锁集数", 2, "集/集包", "内容解锁", "每条完整激励广告连续解锁 2 集。")
    input_row(ws, R["task_rv"], "每个追剧任务领取激励广告", 1, "条/节点", "追剧任务", "默认每个任务节点领取 1 条激励广告。")
    input_row(ws, R["claim_rv"], "每日追剧奖励领取激励广告", 1, "条/日", "现金奖励", "有观看集数时日末聚合领取 1 条广告。")
    input_row(ws, R["checkin_rv"], "每日签到激励广告", 1, "条/日", "现金奖励", "完成签到奖励统计 1 条内容流 RV。")
    input_row(ws, R["checkin_rate"], "签到领取比例", 1.0, "%", "现金奖励", "100% 表示每天完整签到。", "0.0%")
    input_row(ws, R["pack_interstitial"], "每个集包的插屏展示系数", 0.5, "条/集包", "插屏广告", "0.5 表示累计两个集包展示一条完整插屏。", "0.0")
    input_row(ws, R["crown_active_rv"], "皇冠主动激励广告", model.CROWN_ACTIVE_BOOSTS_PER_DAY, "条/日", "奖励中心", "资格路径每天完成 10 次，每条增加 1 点皇冠成长积分。")
    input_row(ws, R["wallet_active_rv"], "钱包主动激励广告", model.WALLET_BOOSTS_PER_DAY, "条/日", "奖励中心", "默认每天 10 次钱包加速广告。")

    section(ws, 47, "七日签到周期", 7)
    headers(ws, 48, ["天数", "奖励（内部单位）", "美元参考", "说明"], 3)
    for offset, amount in enumerate(model.CHECKIN_CYCLE_UNITS):
        row = R["checkin_start"] + offset
        ws.cell(row, 3, offset + 1).font = text_font()
        cell = ws.cell(row, 4, amount)
        cell.fill = fill(INPUT_BLUE)
        cell.font = text_font(bold=True)
        cell.number_format = "#,##0"
        ws.cell(row, 5, f"=D{row}/10000").font = formula_font()
        ws.cell(row, 5).number_format = '$#,##0.00'
        ws.cell(row, 6, "连续签到第 7 天后回到第 1 天；前三日进入资格路径测算。").font = text_font()

    section(ws, 57, "每日追剧任务阶梯", 7)
    headers(ws, 58, ["节点集数", "奖励（内部单位）", "奖励档", "领取广告", "说明"], 3)
    for index, (episode, reward) in enumerate(model.TASK_REWARDS.items()):
        row = R["task_start"] + index
        tier = "第1档" if episode <= 3 else "第2档" if episode <= 9 else "第3档" if episode <= 15 else "第4档"
        for col, value in ((3, episode), (4, reward), (5, tier), (6, f'=Inputs!$D${R["task_rv"]}'), (7, "每日按业务日重置；固定值便于对账。")):
            c = ws.cell(row, col, value)
            c.font = formula_font() if isinstance(value, str) and value.startswith("=") else text_font(bold=col in (3, 4))
            if col == 4:
                c.fill = fill(INPUT_BLUE)
                c.number_format = "#,##0"

    section(ws, 74, "皇冠之路等级与主动加速权益", 7)
    headers(ws, 75, ["等级", "触发积分", "奖励（内部单位）", "单集倍率", "说明"], 3)
    labels = {"BRONZE": "青铜", "SILVER": "白银", "GOLD": "黄金", "DIAMOND": "钻石", "CROWN": "王冠"}
    for index, (code, points, reward, multiplier) in enumerate(model.CROWN_LEVELS):
        row = R["crown_start"] + index
        ws.cell(row, 3, labels[code]).font = text_font(bold=True)
        for col, value, fmt in ((4, points, "#,##0"), (5, reward, "#,##0"), (6, float(multiplier), "0.00x")):
            c = ws.cell(row, col, value)
            c.fill = fill(INPUT_BLUE)
            c.font = text_font(bold=True)
            c.number_format = fmt
        ws.cell(row, 7, "每集 +2 皇冠积分；每日可额外完成 10 次主动加速广告，每条 +1 积分。").font = text_font()
    # Points per episode sits next to the table for clear audit address used by the engine.
    ws.cell(R["crown_episode_points"], 8, "每集皇冠积分").font = text_font(bold=True)
    ws.cell(R["crown_episode_points"], 9, model.CROWN_EPISODE_PROGRESS_POINTS).fill = fill(INPUT_BLUE)
    ws.cell(R["crown_episode_points"], 9).font = text_font(bold=True)
    ws.cell(R["crown_active_points"], 8, "每条皇冠主动广告积分").font = text_font(bold=True)
    ws.cell(R["crown_active_points"], 9, model.CROWN_REWARDED_AD_PROGRESS_POINTS).fill = fill(INPUT_BLUE)
    ws.cell(R["crown_active_points"], 9).font = text_font(bold=True)

    section(ws, 83, "六档阻尼与上限", 7)
    headers(ws, 84, ["档位", "起始累计内部单位", "阻尼倍率", "说明"], 3)
    for index, (threshold, multiplier) in enumerate(model.DAMPING_TIERS):
        row = R["damping_start"] + index
        ws.cell(row, 3, f"第{index + 1}档").font = text_font(bold=True)
        for col, value, fmt in ((4, threshold, "#,##0"), (5, float(multiplier), "0.00x")):
            c = ws.cell(row, col, value)
            c.fill = fill(INPUT_BLUE)
            c.font = text_font(bold=True)
            c.number_format = fmt
        ws.cell(row, 6, "按结算前累计进度选择倍率；最后一档延续至硬上限。" if index == 5 else "达到该累计进度后，后续奖励按本档倍率结算。").font = text_font()

    apply_grid(ws, 8, 90, 3, 7)
    ws.freeze_panes = "C9"
    set_widths(ws, {"A": 3, "B": 3, "C": 31, "D": 18, "E": 17, "F": 17, "G": 67, "H": 16, "I": 16})


def build_engine(ws, scenario_label: str, hours_input_row: int) -> None:
    title(ws, f"计算引擎 — {scenario_label}/日", "隐藏的逐笔结算层。每一行以结算前累计余额选择阻尼倍率，向下取整后应用 300 万硬上限。", 20)
    headers(ws, 9, ["序号", "天数", "事件阶段", "当日集数", "累计集数", "原始奖励", "结算前累计", "阻尼倍率", "阻尼后奖励", "剩余上限", "实际入账", "结算后累计", "累计美元", "内容流RV", "任务RV", "奖励中心RV", "集包插屏", "日观看集数"], 3)
    row = FIRST_ENGINE_ROW
    index = 1
    for day in range(1, MAX_DAYS + 1):
        phase_rows: list[tuple[str, int]] = [("欢迎奖励", 0), ("日初签到", 0)]
        phase_rows.extend(("皇冠主动加速", 0) for _ in range(MAX_CROWN_ACTIVE_ADS_PER_DAY))
        phase_rows.extend(("皇冠/任务", episode) for episode in range(1, MAX_EPISODES_PER_DAY + 1))
        phase_rows.extend(("分集领取", episode) for episode in range(1, MAX_EPISODES_PER_DAY + 1))
        phase_rows.extend(("钱包加速", 0) for _ in range(MAX_WALLET_BOOSTS_PER_DAY))
        for phase, episode in phase_rows:
            ws.cell(row, 3, index).font = formula_font()
            ws.cell(row, 4, day).font = formula_font()
            phase_cell = ws.cell(row, 5, phase)
            phase_cell.font = text_font()
            phase_cell.fill = fill(PALE_YELLOW if phase in ("欢迎奖励", "日初签到", "皇冠主动加速", "钱包加速") else PALE_BLUE)
            ws.cell(row, 6, episode).font = formula_font()
            # daily episodes: fixed time scenario / average episode time, limited to model capacity and model range.
            ws.cell(row, 20, f'=IF($B{row}<=Inputs!$D${R["milestone_days"]},MIN(Inputs!$D${R["max_episodes"]},ROUNDDOWN(Inputs!$D${hours_input_row}*60/Inputs!$D${R["episode_minutes"]},0)),0)').font = formula_font()
            ws.cell(row, 7, f'=IF($F{row}=0,0,($D{row}-1)*$T{row}+$F{row})').font = formula_font()
            formulas = formulas_for_engine(row, phase)
            for col, formula in enumerate(formulas, start=8):
                ws.cell(row, col, formula).font = formula_font()
            for col in (8, 9, 11, 12, 13, 14, 16, 17, 18, 19, 20):
                ws.cell(row, col).number_format = "#,##0"
            ws.cell(row, 10).number_format = "0.00x"
            ws.cell(row, 15).number_format = '$#,##0.00'
            row += 1
            index += 1
    ws.freeze_panes = "C10"
    ws.sheet_state = "hidden"
    set_widths(ws, {"A": 3, "B": 3, "C": 8, "D": 8, "E": 16, "F": 11, "G": 13, "H": 15, "I": 16, "J": 11, "K": 15, "L": 15, "M": 15, "N": 14, "O": 12, "P": 10, "Q": 14, "R": 13, "S": 13, "T": 13})
    apply_grid(ws, 9, row - 1, 3, 20)


def daily_formula(engine: str, column: str, day_cell: str, operation: str = "sum") -> str:
    last = FIRST_ENGINE_ROW + MAX_DAYS * EVENTS_PER_DAY - 1
    if operation == "sum":
        return f'=SUMIF({engine}!$D${FIRST_ENGINE_ROW}:$D${last},{day_cell},{engine}!${column}${FIRST_ENGINE_ROW}:${column}${last})'
    raise ValueError(operation)


def build_daily(ws, scenario_label: str, engine: str, hours_input_row: int) -> None:
    title(ws, f"日度明细 — {scenario_label}/日", "所有结果由隐藏的逐笔 Engine 公式计算。每一行显示日末累计、当日奖励、各类激励广告与集包插屏。", 22)
    headers(ws, 9, ["天数", "观看小时", "有效观看集数", "累计观看集数", "日初进度", "签到", "分集", "任务/皇冠", "钱包加速", "日末进度", "当日入账", "累计美元钱包", "内容流RV", "任务RV", "奖励中心RV", "全部RV", "集包插屏", "例外插屏", "全部插屏"], 3)
    first, last = FIRST_ENGINE_ROW, FIRST_ENGINE_ROW + MAX_DAYS * EVENTS_PER_DAY - 1
    for index, row in enumerate(range(10, 10 + MAX_DAYS), start=1):
        ws.cell(row, 3, f"={index}").font = formula_font()
        ws.cell(row, 4, f'=IF($C{row}<=Inputs!$D${R["milestone_days"]},Inputs!$D${hours_input_row},0)').font = formula_font()
        ws.cell(row, 5, f'=IF($D{row}=0,0,MIN(Inputs!$D${R["max_episodes"]},ROUNDDOWN($D{row}*60/Inputs!$D${R["episode_minutes"]},0)))').font = formula_font()
        ws.cell(row, 6, f'=SUM($E$10:$E{row})').font = formula_font()
        ws.cell(row, 7, "=0" if row == 10 else f"=$L{row - 1}").font = formula_font()
        ws.cell(row, 8, f'=SUMIFS({engine}!$M${first}:$M${last},{engine}!$D${first}:$D${last},$C{row},{engine}!$E${first}:$E${last},"日初签到")').font = formula_font()
        ws.cell(row, 9, f'=SUMIFS({engine}!$M${first}:$M${last},{engine}!$D${first}:$D${last},$C{row},{engine}!$E${first}:$E${last},"分集领取")').font = formula_font()
        ws.cell(row, 10, f'=SUMIFS({engine}!$M${first}:$M${last},{engine}!$D${first}:$D${last},$C{row},{engine}!$E${first}:$E${last},"皇冠/任务")').font = formula_font()
        ws.cell(row, 11, f'=SUMIFS({engine}!$M${first}:$M${last},{engine}!$D${first}:$D${last},$C{row},{engine}!$E${first}:$E${last},"钱包加速")').font = formula_font()
        ws.cell(row, 12, f'=$G{row}+SUMIFS({engine}!$M${first}:$M${last},{engine}!$D${first}:$D${last},$C{row})').font = formula_font()
        ws.cell(row, 13, f'=$L{row}-$G{row}').font = formula_font()
        ws.cell(row, 14, f'=$L{row}/10000').font = formula_font()
        ws.cell(row, 15, f'=SUMIF({engine}!$D${first}:$D${last},$C{row},{engine}!$P${first}:$P${last})').font = formula_font()
        ws.cell(row, 16, f'=SUMIF({engine}!$D${first}:$D${last},$C{row},{engine}!$Q${first}:$Q${last})').font = formula_font()
        ws.cell(row, 17, f'=SUMIF({engine}!$D${first}:$D${last},$C{row},{engine}!$R${first}:$R${last})').font = formula_font()
        ws.cell(row, 18, f'=$O{row}+$Q{row}').font = formula_font()
        ws.cell(row, 19, f'=SUMIF({engine}!$D${first}:$D${last},$C{row},{engine}!$S${first}:$S${last})').font = formula_font()
        ws.cell(row, 20, "=0").font = formula_font()
        ws.cell(row, 21, f'=$S{row}+$T{row}').font = formula_font()
        for col in list(range(3, 23)):
            ws.cell(row, col).alignment = Alignment(vertical="center")
        for col in (3, 5, 6, 8, 9, 10, 11, 12, 13, 15, 16, 17, 18, 19, 20, 21, 22):
            ws.cell(row, col).number_format = "#,##0"
        ws.cell(row, 4).number_format = "0.0"
        ws.cell(row, 14).number_format = '$#,##0.00'
    ws.freeze_panes = "C10"
    apply_grid(ws, 9, 9 + MAX_DAYS, 3, 21)
    set_widths(ws, {"A": 3, "B": 3, "C": 8, "D": 11, "E": 14, "F": 14, "G": 15, "H": 12, "I": 15, "J": 15, "K": 15, "L": 15, "M": 15, "N": 17, "O": 13, "P": 12, "Q": 15, "R": 12, "S": 13, "T": 13, "U": 13, "V": 13})


def summary_formula(daily: str, column: str) -> str:
    return f'=SUMIF({daily}!$C$10:$C${9 + MAX_DAYS},"<="&Inputs!$D${R["summary_days"]},{daily}!${column}$10:${column}${9 + MAX_DAYS})'


def end_formula(daily: str, column: str) -> str:
    return f'=IFERROR(INDEX({daily}!${column}$10:${column}${9 + MAX_DAYS},Inputs!$D${R["summary_days"]}),0)'


def build_summary(ws) -> None:
    title(ws, "汇总：动态 Inputs 联动的三日奖励、里程碑和广告统计", "Summary、Milestones、Ad_Points 和三张 Daily 表均引用 Inputs 蓝色参数；修改后自动重算。", 8)
    section(ws, 6, "前 N 天汇总（N = Inputs 的“汇总统计天数”）", 8)
    headers(ws, 8, ["指标", "1.0h 情景", "1.5h 情景", "2.0h 情景"], 3)
    metrics = [
        ("日观看时长", "hours", "0.0"),
        ("有效观看集数", "episodes", "#,##0"),
        ("累计内部奖励进度", "end_units", "#,##0"),
        ("累计美元钱包", "end_usd", '$#,##0.00'),
        ("累计内容流RV", "content_rv", "#,##0"),
        ("累计任务RV", "task_rv", "#,##0"),
        ("累计奖励中心RV", "center_rv", "#,##0"),
        ("累计全部RV", "all_rv", "#,##0"),
        ("内容流RV/观看小时", "content_per_hour", "0.00"),
        ("累计集包插屏", "interstitial", "#,##0"),
        ("累计例外插屏", "exception", "#,##0"),
        ("累计全部插屏", "all_interstitial", "#,##0"),
        ("全部插屏/观看小时", "interstitial_per_hour", "0.00"),
    ]
    daily_by_scenario = {label: daily for label, daily, _, _ in SCENARIOS}
    for row, (label, metric, fmt) in enumerate(metrics, start=9):
        ws.cell(row, 3, label).font = text_font(bold=metric in ("end_units", "end_usd", "all_rv"))
        for col, (scenario_label, daily, _, hours_row) in enumerate(SCENARIOS, start=4):
            if metric == "hours":
                formula = f'=Inputs!$D${hours_row}'
            elif metric == "episodes":
                formula = summary_formula(daily, "E")
            elif metric == "end_units":
                formula = end_formula(daily, "L")
            elif metric == "end_usd":
                formula = end_formula(daily, "N")
            elif metric == "content_rv":
                formula = summary_formula(daily, "O")
            elif metric == "task_rv":
                formula = summary_formula(daily, "P")
            elif metric == "center_rv":
                formula = summary_formula(daily, "Q")
            elif metric == "all_rv":
                formula = summary_formula(daily, "R")
            elif metric == "content_per_hour":
                formula = f'=IFERROR({summary_formula(daily, "O")[1:]}/({summary_formula(daily, "E")[1:]}*Inputs!$D${R["episode_minutes"]}/60),0)'
            elif metric == "interstitial":
                formula = summary_formula(daily, "S")
            elif metric == "exception":
                formula = summary_formula(daily, "T")
            elif metric == "all_interstitial":
                formula = summary_formula(daily, "U")
            elif metric == "interstitial_per_hour":
                formula = f'=IFERROR({summary_formula(daily, "U")[1:]}/({summary_formula(daily, "E")[1:]}*Inputs!$D${R["episode_minutes"]}/60),0)'
            else:
                raise ValueError(metric)
            cell = ws.cell(row, col, formula)
            cell.font = formula_font()
            cell.number_format = fmt
    section(ws, 24, "说明与模型边界", 8)
    notes = [
        "蓝色 Inputs 修改后，奖励、广告量、阻尼进度、美元钱包、任务线和里程碑会联动。逐笔计算层为隐藏工作表 Engine_*；如需审计，可在 Excel 中取消隐藏。",
        f"三种情景均预生成 {MAX_DAYS} 天、每天最多 {MAX_EPISODES_PER_DAY} 集。Inputs 中范围参数不得超过该上限。",
        "广告频率为运营测算：内容流 RV 包含新人、签到、任务、日末领取与解锁；奖励中心 RV 包含每日 10 次皇冠主动加速与 10 次钱包加速；实际广告填充和客户端频控以生产逻辑为准。",
        "2.0h 情景默认 80 集/日；完整路径含七日循环签到、每日各 10 次皇冠与钱包加速，三日结算显示 US$290.00；硬上限为 US$300。",
    ]
    for row, note in enumerate(notes, start=25):
        ws.merge_cells(start_row=row, start_column=3, end_row=row, end_column=6)
        ws.cell(row, 3, note).font = text_font()
        ws.cell(row, 3).alignment = Alignment(wrap_text=True, vertical="top")
        ws.row_dimensions[row].height = 30
    apply_grid(ws, 8, 21, 3, 6)
    set_widths(ws, {"A": 3, "B": 3, "C": 27, "D": 18, "E": 18, "F": 18, "G": 4, "H": 4})


def milestone_day_formula(daily: str, threshold_ref: str) -> str:
    return f'=IF(COUNTIF({daily}!$L$10:$L${9 + MAX_DAYS},">="&{threshold_ref})=0,"未达到",COUNTIF({daily}!$L$10:$L${9 + MAX_DAYS},"<"&{threshold_ref})+1)'


def build_milestones(ws) -> None:
    title(ws, "里程碑：达到 290 万资格目标、300 万硬上限时的广告量与钱包金额", "以 Inputs 中的“里程碑测算范围”作为最大计算范围。所有字段均为公式；未达到时显示“未达到”。", 8)
    section(ws, 6, "达到 290 万和 300 万时的累计结果", 8)
    headers(ws, 8, ["指标", "1.0h 情景", "1.5h 情景", "2.0h 情景"], 3)
    metrics = [
        ("达到290万资格目标的天数", "target_day"),
        ("达到300万硬上限的天数", "cap_day"),
        ("290万时美元钱包", "target_usd"),
        ("290万时累计内容流RV", "target_content"),
        ("290万时累计任务RV", "target_task"),
        ("290万时累计奖励中心RV", "target_center"),
        ("290万时累计全部RV", "target_all_rv"),
        ("290万时累计集包插屏", "target_interstitial"),
        ("300万时美元钱包", "cap_usd"),
    ]
    for row, (label, metric) in enumerate(metrics, start=9):
        ws.cell(row, 3, label).font = text_font(bold=metric in ("target_day", "cap_day"))
        for col, (_, daily, _, _) in enumerate(SCENARIOS, start=4):
            target_day = milestone_day_formula(daily, f"Inputs!$D${R['target']}")
            cap_day = milestone_day_formula(daily, f"Inputs!$D${R['cap']}")
            target_day_expr = target_day[1:]
            cap_day_expr = cap_day[1:]
            if metric == "target_day":
                formula, fmt = target_day, "0"
            elif metric == "cap_day":
                formula, fmt = cap_day, "0"
            elif metric == "target_usd":
                formula, fmt = f'=IF(ISNUMBER({target_day_expr}),INDEX({daily}!$N$10:$N${9 + MAX_DAYS},{target_day_expr}),"未达到")', '$#,##0.00'
            elif metric == "target_content":
                formula, fmt = f'=IF(ISNUMBER({target_day_expr}),SUMIF({daily}!$C$10:$C${9 + MAX_DAYS},"<="&{target_day_expr},{daily}!$O$10:$O${9 + MAX_DAYS}),"未达到")', "#,##0"
            elif metric == "target_task":
                formula, fmt = f'=IF(ISNUMBER({target_day_expr}),SUMIF({daily}!$C$10:$C${9 + MAX_DAYS},"<="&{target_day_expr},{daily}!$P$10:$P${9 + MAX_DAYS}),"未达到")', "#,##0"
            elif metric == "target_center":
                formula, fmt = f'=IF(ISNUMBER({target_day_expr}),SUMIF({daily}!$C$10:$C${9 + MAX_DAYS},"<="&{target_day_expr},{daily}!$Q$10:$Q${9 + MAX_DAYS}),"未达到")', "#,##0"
            elif metric == "target_all_rv":
                formula, fmt = f'=IF(ISNUMBER({target_day_expr}),SUMIF({daily}!$C$10:$C${9 + MAX_DAYS},"<="&{target_day_expr},{daily}!$R$10:$R${9 + MAX_DAYS}),"未达到")', "#,##0"
            elif metric == "target_interstitial":
                formula, fmt = f'=IF(ISNUMBER({target_day_expr}),SUMIF({daily}!$C$10:$C${9 + MAX_DAYS},"<="&{target_day_expr},{daily}!$S$10:$S${9 + MAX_DAYS}),"未达到")', "#,##0"
            elif metric == "cap_usd":
                formula, fmt = f'=IF(ISNUMBER({cap_day_expr}),INDEX({daily}!$N$10:$N${9 + MAX_DAYS},{cap_day_expr}),"未达到")', '$#,##0.00'
            else:
                raise ValueError(metric)
            cell = ws.cell(row, col, formula)
            cell.font = formula_font()
            cell.number_format = fmt
    section(ws, 20, "说明", 8)
    notes = [
        "目标与硬上限均按日末累计进度识别。隐藏 Engine 层在每笔事件上应用阻尼，避免按天统一套用阻尼倍率。",
        "“290万时累计内容流RV”包含新人、签到、解锁、任务和日末领取；“奖励中心RV”包含皇冠主动加速与钱包加速。未达到的情景会显示“未达到”。",
        "模型默认 2.0h 情景在第 3 天达到 290 万资格目标；达到目标后的下一笔奖励才进入阻尼区间。",
    ]
    for row, note in enumerate(notes, start=21):
        ws.merge_cells(start_row=row, start_column=3, end_row=row, end_column=6)
        ws.cell(row, 3, note).font = text_font()
        ws.cell(row, 3).alignment = Alignment(wrap_text=True, vertical="top")
        ws.row_dimensions[row].height = 30
    apply_grid(ws, 8, 17, 3, 6)
    set_widths(ws, {"A": 3, "B": 3, "C": 30, "D": 18, "E": 18, "F": 18, "G": 4, "H": 4})


def build_ad_points(ws) -> None:
    title(ws, "广告点与频率：由 Inputs 动态计算", "本表不是静态文案。修改单集时长、免费集数、解锁集数、任务领取广告、钱包额度或插屏系数后，频率和三种情景结果都会更新。", 9)
    section(ws, 6, "广告规则与优先级", 9)
    headers(ws, 8, ["事件", "是否展示", "触发 / 数量（Inputs 联动）", "优先级", "计入内容流RV", "说明"], 3)
    rules = [
        ("新人欢迎奖励广告", "是", f'=Inputs!$D${R["welcome_rv"]}', "—", "是", "首日领取新人奖励时计入 1 条内容流激励广告。"),
        ("解锁激励广告", "是", f'=Inputs!$D${R["free_episodes"]}&" 集免费；每条解锁 "&Inputs!$D${R["unlock_bundle"]}&" 集"', "—", "是", "完整观看后获得一个集包；基础插屏按集包系数累计。"),
        ("追剧任务领取广告", "是", f'=Inputs!$D${R["task_rv"]}&" 条 / 节点"', "—", "是", "每天任务重置；最高 60 集共 14 个固定金额节点。"),
        ("签到广告", "是", f'=Inputs!$D${R["checkin_rv"]}&" 条 / 日"', "—", "是", "按签到领取比例计入前三日资格路径。"),
        ("钱包主动广告", "是", f'=Inputs!$D${R["wallet_active_rv"]}&" 条 / 日"', "—", "否", "钱包加速广告，计入奖励中心 RV。"),
        ("皇冠主动广告", "是", f'=Inputs!$D${R["crown_active_rv"]}&" 条 / 日"', "—", "否", "每日最多 10 条；每条增加 1 点皇冠成长积分，按日初先完成的资格路径计入。"),
        ("集包插屏", "是", f'=Inputs!$D${R["pack_interstitial"]}&" 条 / 集包"', "P2", "否", "系数 0.5 表示累计两个集包展示一条完整插屏。"),
        ("激励加载失败 / 拒绝", "条件触发", "本模型默认 0", "P1", "否", "实际客户端按广告回调处理；本模型不叠加例外候选。"),
    ]
    for row, values in enumerate(rules, start=9):
        for col, value in enumerate(values, start=3):
            cell = ws.cell(row, col, value)
            cell.font = formula_font() if isinstance(value, str) and value.startswith("=") else text_font(bold=col == 3)
    section(ws, 19, "基础频率（含皇冠与钱包主动加速）", 9)
    headers(ws, 20, ["每日观看时长", "有效观看集/日", "内容流RV/小时", "集包插屏/小时", "奖励中心RV/小时", "全部插屏/小时"], 3)
    for row, (_, daily, _, hours_row) in enumerate(SCENARIOS, start=21):
        ws.cell(row, 3, f'=Inputs!$D${hours_row}').font = formula_font()
        ws.cell(row, 4, f'=INDEX({daily}!$E$10:$E${9 + MAX_DAYS},1)').font = formula_font()
        ws.cell(row, 5, f'=IFERROR(INDEX({daily}!$O$10:$O${9 + MAX_DAYS},1)/$C{row},0)').font = formula_font()
        ws.cell(row, 6, f'=IFERROR(INDEX({daily}!$S$10:$S${9 + MAX_DAYS},1)/$C{row},0)').font = formula_font()
        ws.cell(row, 7, f'=IFERROR(INDEX({daily}!$Q$10:$Q${9 + MAX_DAYS},1)/$C{row},0)').font = formula_font()
        ws.cell(row, 8, f'=IFERROR(INDEX({daily}!$U$10:$U${9 + MAX_DAYS},1)/$C{row},0)').font = formula_font()
        for col in range(3, 9):
            ws.cell(row, col).number_format = "0.00"
    section(ws, 26, "实现约束", 9)
    notes = [
        "同一转场的广告优先级遵循 P1（加载失败 / 拒绝）> P2（集包）> P3（横滑）原则；本工作簿只为 P2 集包插屏提供基线运营测算。",
        "插屏频率和请求 RV 仅用于运营估算。实际广告加载、库存、展示成功、关闭时间和客户端频控以线上 SDK 回调与代码为准。",
    ]
    for row, note in enumerate(notes, start=27):
        ws.merge_cells(start_row=row, start_column=3, end_row=row, end_column=8)
        ws.cell(row, 3, note).font = text_font()
        ws.cell(row, 3).alignment = Alignment(wrap_text=True)
        ws.row_dimensions[row].height = 30
    apply_grid(ws, 8, 16, 3, 8)
    apply_grid(ws, 20, 23, 3, 8)
    set_widths(ws, {"A": 3, "B": 3, "C": 22, "D": 14, "E": 32, "F": 12, "G": 15, "H": 53, "I": 4})


def build_workbook(output: Path) -> None:
    wb = Workbook()
    wb.calculation.calcMode = "auto"
    wb.calculation.fullCalcOnLoad = True
    wb.calculation.forceFullCalc = True
    wb.properties.title = "JoyeDrama 三日300美元阶梯充能广告测算模型"
    wb.properties.subject = "公式驱动的奖励、广告和阻尼资格路径模型"
    wb.properties.creator = "JoyeDrama"

    readme = wb.active
    readme.title = "README"
    inputs = wb.create_sheet("Inputs")
    summary = wb.create_sheet("Summary")
    milestones = wb.create_sheet("Milestones")
    ad_points = wb.create_sheet("Ad_Points")
    daily_sheets = []
    engine_sheets = []
    for _, daily_name, engine_name, _ in SCENARIOS:
        daily_sheets.append(wb.create_sheet(daily_name))
    for _, daily_name, engine_name, _ in SCENARIOS:
        engine_sheets.append(wb.create_sheet(engine_name))

    build_readme(readme)
    build_inputs(inputs)
    build_summary(summary)
    build_milestones(milestones)
    build_ad_points(ad_points)
    for (label, daily_name, engine_name, hours_row), daily_ws, engine_ws in zip(SCENARIOS, daily_sheets, engine_sheets):
        build_engine(engine_ws, label, hours_row)
        build_daily(daily_ws, label, engine_name, hours_row)

    # Establish the old model's visual convention: blue input cells, green formula cells,
    # navy headers, light category bands and hidden calculation engines.
    for ws in wb.worksheets:
        ws.sheet_view.showGridLines = False
        ws.page_margins.left = 0.25
        ws.page_margins.right = 0.25
        ws.page_margins.top = 0.45
        ws.page_margins.bottom = 0.45
        ws.sheet_properties.pageSetUpPr.fitToPage = True
        ws.page_setup.fitToWidth = 1
        ws.page_setup.fitToHeight = 0
    summary.sheet_properties.tabColor = "70AD47"
    inputs.sheet_properties.tabColor = "5B9BD5"
    milestones.sheet_properties.tabColor = "FFC000"
    ad_points.sheet_properties.tabColor = "ED7D31"
    for ws in engine_sheets:
        ws.sheet_state = "hidden"

    output.parent.mkdir(parents=True, exist_ok=True)
    wb.save(output)


def validate(output: Path) -> None:
    wb = load_workbook(output, data_only=False)
    expected = [
        "README", "Inputs", "Summary", "Milestones", "Ad_Points",
        "Daily_scenario_1_hours", "Daily_scenario_2_hours", "Daily_scenario_3_hours",
        "Engine_scenario_1_hours", "Engine_scenario_2_hours", "Engine_scenario_3_hours",
    ]
    if wb.sheetnames != expected:
        raise RuntimeError(f"Unexpected sheets: {wb.sheetnames}")
    for name in expected[-3:]:
        if wb[name].sheet_state != "hidden":
            raise RuntimeError(f"Engine sheet must be hidden: {name}")
        if wb[name].max_row != FIRST_ENGINE_ROW + MAX_DAYS * EVENTS_PER_DAY - 1:
            raise RuntimeError(f"Unexpected engine capacity: {name} has {wb[name].max_row} rows")
    if wb["Summary"]["E11"].value is None:
        raise RuntimeError("Summary formula missing")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=OUTPUT_DEFAULT)
    args = parser.parse_args()
    build_workbook(args.output)
    validate(args.output)
    print(args.output.resolve())


if __name__ == "__main__":
    main()
