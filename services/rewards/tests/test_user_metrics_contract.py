from __future__ import annotations

from contextlib import contextmanager
from datetime import date
from types import SimpleNamespace
from typing import Any, Iterator

from test_metrics_pure_functions import PROJECT_ROOT

from app.service import RewardsService


class MetricContractService(RewardsService):
    """Database-free fixture that records the metric query contract."""

    def __init__(self) -> None:
        self.settings = SimpleNamespace(timezone="UTC")
        self.queries: list[str] = []

    def _verify_admin_token(self, authorization: str | None) -> None:
        assert authorization == "admin-fixture"

    @contextmanager
    def _transaction(self) -> Iterator[object]:
        yield object()

    def _one(self, connection: object, query: str, params: tuple[Any, ...]) -> dict[str, Any] | None:
        self.queries.append(query)
        if "WITH cohort AS" in query:
            return {}
        if "MIN(b.client_occurred_at)" in query:
            return {}
        if "COUNT(*) AS count FROM users" in query and "u.created_at >= %s" in query:
            return {"count": 54}
        if "COUNT(*) AS count FROM users" in query:
            return {"count": 87}
        raise AssertionError(f"unexpected scalar metric query: {query}")

    def _all(self, connection: object, query: str, params: tuple[Any, ...]) -> list[dict[str, Any]]:
        self.queries.append(query)
        if "registration_count" in query:
            return [{"metric_date": date(2026, 10, 15), "registration_count": 54}]
        if "FROM daily_counters" in query:
            return [{"metric_date": date(2026, 10, 15), "daily_active_users": 41}]
        if "FROM user_behavior_events" in query:
            return [{"metric_date": date(2026, 10, 15), "verified_behavior_active_users": 3}]
        raise AssertionError(f"unexpected row metric query: {query}")


def test_user_metrics_preserve_cumulative_and_legacy_activity_series() -> None:
    service = MetricContractService()
    metrics = service.admin_user_metrics(
        "admin-fixture",
        start_date=date(2026, 10, 15),
        end_date=date(2026, 10, 15),
    )

    assert metrics["total_users"] == 87
    assert metrics["matching_users_all_registration_dates"] == 87
    assert metrics["filtered_registration_users"] == 54
    assert metrics["daily"] == [{
        "metric_date": "2026-10-15",
        "registration_count": 54,
        "daily_active_users": 41,
        "verified_behavior_active_users": 3,
    }]
    assert metrics["data_coverage"]["daily_active_source"] == "daily_counters"
    assert metrics["data_coverage"]["daily_active_timezone"] == "UTC"
    assert any("FROM daily_counters" in query for query in service.queries)


def run() -> None:
    test_user_metrics_preserve_cumulative_and_legacy_activity_series()
    print("user metric contract tests passed")


if __name__ == "__main__":
    run()
