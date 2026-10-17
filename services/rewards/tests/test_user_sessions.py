from dataclasses import replace
from datetime import UTC, datetime, timedelta

import pytest

from app.config import Settings
from app.service import RewardsService, ServiceError


@pytest.fixture(autouse=True)
def isolated_session_environment(monkeypatch):
    monkeypatch.setenv("DATABASE_URL", "postgresql://unit:unit@127.0.0.1:1/unit")
    monkeypatch.setenv("ENVIRONMENT", "development")
    monkeypatch.setenv("SESSION_SIGNING_SECRET", "unit-session-secret-" + "s" * 40)
    monkeypatch.delenv("USER_SESSION_TTL_DAYS", raising=False)


def settings_for_test() -> Settings:
    return Settings.from_environment()


def test_user_session_defaults_to_180_days_without_changing_admin_ttl(monkeypatch):
    monkeypatch.delenv("USER_SESSION_TTL_DAYS", raising=False)
    settings = settings_for_test()
    assert settings.user_session_ttl_days == 180
    service = RewardsService(settings)
    fixed = datetime(2026, 10, 17, tzinfo=UTC)
    monkeypatch.setattr(service, "_now", lambda: fixed)
    token = service.issue_user_session("test-user")
    assert int(token.split(".")[2]) == int((fixed + timedelta(days=180)).timestamp())
    service.verify_user_session("test-user", "Bearer " + token)


def test_expired_and_rotated_sessions_reject_then_fresh_signature_works(monkeypatch):
    settings = settings_for_test()
    service = RewardsService(settings)
    fixed = datetime(2026, 10, 17, tzinfo=UTC)
    monkeypatch.setattr(service, "_now", lambda: fixed)
    expired = service.issue_user_session("test-user", ttl_seconds=-1)
    with pytest.raises(ServiceError) as error:
        service.verify_user_session("test-user", "Bearer " + expired)
    assert error.value.status_code == 401
    old = service.issue_user_session("test-user")
    rotated = RewardsService(replace(settings, session_signing_secret="rotated-test-secret-" + "z" * 32))
    monkeypatch.setattr(rotated, "_now", lambda: fixed)
    with pytest.raises(ServiceError):
        rotated.verify_user_session("test-user", "Bearer " + old)
    renewed = rotated.issue_user_session("test-user")
    rotated.verify_user_session("test-user", "Bearer " + renewed)
    with pytest.raises(ServiceError):
        rotated.verify_user_session("different-user", "Bearer " + renewed)


def test_configurable_user_session_lifetime_is_bounded(monkeypatch):
    monkeypatch.setenv("USER_SESSION_TTL_DAYS", "365")
    assert settings_for_test().user_session_ttl_days == 365
    monkeypatch.setenv("USER_SESSION_TTL_DAYS", "0")
    with pytest.raises(RuntimeError):
        settings_for_test()
