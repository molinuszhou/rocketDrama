from app.admin_auth import (
    csv_safe_cell,
    has_cross_origin_browser_metadata,
    is_same_origin_browser_request,
    request_public_origin,
    select_admin_authorization,
)


def test_cookie_authorization_is_used_only_when_bearer_is_absent() -> None:
    assert select_admin_authorization(None, "cookie-token") == ("Bearer cookie-token", True)
    assert select_admin_authorization("Bearer api-token", "cookie-token") == ("Bearer api-token", False)


def test_same_origin_check_accepts_matching_origin_or_referer() -> None:
    expected = "https://rewards.example.test"
    assert is_same_origin_browser_request({"origin": expected}, expected)
    assert is_same_origin_browser_request({"referer": expected + "/admin?page=users"}, expected)
    assert not is_same_origin_browser_request({"origin": "https://attacker.example"}, expected)
    assert not is_same_origin_browser_request({"sec-fetch-site": "cross-site", "origin": expected}, expected)
    assert not is_same_origin_browser_request({}, expected)


def test_login_cross_origin_metadata_is_rejected_but_headless_bearer_clients_are_not_classified() -> None:
    expected = "https://rewards.example.test"
    assert has_cross_origin_browser_metadata({"origin": "https://attacker.example"}, expected)
    assert not has_cross_origin_browser_metadata({}, expected)


def test_public_origin_uses_sanitised_nginx_forwarding_headers() -> None:
    assert request_public_origin(
        {"x-forwarded-proto": "https", "x-forwarded-host": "rewards.example.test"},
        "http",
        "127.0.0.1:8000",
    ) == "https://rewards.example.test"


def test_csv_formula_prefixes_are_neutralised_without_changing_numbers() -> None:
    assert csv_safe_cell("=SUM(A1:A2)") == "'=SUM(A1:A2)"
    assert csv_safe_cell(" \t-10+20") == "' \t-10+20"
    assert csv_safe_cell("@cmd") == "'@cmd"
    assert csv_safe_cell("ordinary-text") == "ordinary-text"
    assert csv_safe_cell(-42) == -42
    assert csv_safe_cell(12.5) == 12.5
