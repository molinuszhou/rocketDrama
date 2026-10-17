from __future__ import annotations

from collections.abc import Mapping
from typing import Any
from urllib.parse import urlsplit


ADMIN_SESSION_COOKIE = "joyedrama_admin_session"
CSV_FORMULA_PREFIXES = ("=", "+", "-", "@")


def _header(headers: Mapping[str, str], name: str) -> str:
    """Read a HTTP header from FastAPI's case-insensitive or plain test mappings."""

    return headers.get(name, headers.get(name.title(), ""))


def _first_forwarded_value(value: str) -> str:
    return value.split(",", 1)[0].strip()


def request_public_origin(headers: Mapping[str, str], scheme: str, host: str) -> str:
    """Build the browser-visible origin from Nginx-sanitised forwarding headers."""

    forwarded_scheme = _first_forwarded_value(_header(headers, "x-forwarded-proto"))
    forwarded_host = _first_forwarded_value(_header(headers, "x-forwarded-host"))
    actual_scheme = forwarded_scheme if forwarded_scheme in {"http", "https"} else scheme
    actual_host = forwarded_host or host
    return f"{actual_scheme}://{actual_host}"


def is_https_request(headers: Mapping[str, str], scheme: str) -> bool:
    """Return whether the browser-visible request arrived over HTTPS."""

    return request_public_origin(headers, scheme, "localhost").startswith("https://")


def _normalised_origin(value: str) -> str | None:
    try:
        parsed = urlsplit(value)
    except ValueError:
        return None
    if parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.username or parsed.password:
        return None
    return f"{parsed.scheme.lower()}://{parsed.netloc.lower()}"


def is_same_origin_browser_request(headers: Mapping[str, str], expected_origin: str) -> bool:
    """Require an Origin or Referer matching the current public origin for cookie writes."""

    if _header(headers, "sec-fetch-site").lower() == "cross-site":
        return False
    expected = _normalised_origin(expected_origin)
    if expected is None:
        return False
    origin = _header(headers, "origin")
    if origin:
        return _normalised_origin(origin) == expected
    referer = _header(headers, "referer")
    if referer:
        return _normalised_origin(referer) == expected
    return False


def has_cross_origin_browser_metadata(headers: Mapping[str, str], expected_origin: str) -> bool:
    """Detect a browser-originated cross-origin request without blocking Bearer API clients."""

    if _header(headers, "sec-fetch-site").lower() == "cross-site":
        return True
    if _header(headers, "origin") or _header(headers, "referer"):
        return not is_same_origin_browser_request(headers, expected_origin)
    return False


def select_admin_authorization(
    authorization: str | None, cookie_value: str | None
) -> tuple[str | None, bool]:
    """Prefer an explicit Bearer client header; otherwise adapt the HttpOnly cookie."""

    if authorization is not None:
        return authorization, False
    if cookie_value:
        return f"Bearer {cookie_value}", True
    return None, False


def csv_safe_cell(value: Any) -> Any:
    """Force potentially formula-like string cells to remain literal spreadsheet text."""

    if not isinstance(value, str):
        return value
    stripped = value.lstrip(" \t\r\n")
    if stripped.startswith(CSV_FORMULA_PREFIXES):
        return "'" + value
    return value
