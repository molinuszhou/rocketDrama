from __future__ import annotations

import sys
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from app.service import RewardsService


def test_early_video_cohort_metrics_do_not_divide_independent_window_counts() -> None:
    observation_until = datetime(2026, 10, 17, 4, 0, tzinfo=UTC)
    rows = RewardsService._build_early_video_metrics(
        [{
            "early_video_rank": 1,
            "started_event_count": 1,
            "completed_event_count": 3,
            "started_users": 1,
            "completed_users": 3,
            "failure_events": 2,
        }],
        [{
            "early_video_rank": 1,
            "cohort_size": 2,
            # 模拟一个重复/异常聚合值：对外 cohort 完成数必须永不超过开始 cohort。
            "cohort_completed_users": 3,
        }],
        observation_until,
    )

    first = rows[0]
    assert first["date_window_started_event_count"] == 1
    assert first["date_window_completed_event_count"] == 3
    assert first["cohort_size"] == 2
    assert first["cohort_completed_users"] == 2
    assert first["cohort_completion_rate"] == 1.0
    assert first["cohort_dropoff_users"] == 0
    assert first["observation_until"] == observation_until.isoformat()
    assert rows[19]["cohort_size"] == 0
    assert rows[19]["cohort_completion_rate"] == 0.0


def test_revenue_by_currency_has_no_mixed_currency_scalar() -> None:
    mixed = RewardsService._revenue_by_currency([
        {"currency": "USD", "revenue_value": Decimal("1.25")},
        {"currency": "JPY", "revenue_value": Decimal("100")},
    ])
    assert mixed["revenue_value"] is None
    assert mixed["revenue_currency"] is None
    assert mixed["revenue_by_currency"] == [
        {"currency": "JPY", "revenue_value": "100"},
        {"currency": "USD", "revenue_value": "1.25"},
    ]

    usd_only = RewardsService._revenue_by_currency([
        {"currency": "USD", "revenue_value": Decimal("1.25")},
        {"currency": "usd", "revenue_value": Decimal("0.75")},
    ])
    assert usd_only["revenue_value"] == "2.00"
    assert usd_only["revenue_currency"] == "USD"


def run() -> None:
    test_early_video_cohort_metrics_do_not_divide_independent_window_counts()
    test_revenue_by_currency_has_no_mixed_currency_scalar()
    print("metrics pure-function tests passed")


if __name__ == "__main__":
    run()
