from __future__ import annotations

import ipaddress
import logging
from pathlib import Path
from threading import Lock

try:
    import geoip2.database
except ImportError:  # pragma: no cover - dependency is installed in production images
    geoip2 = None
else:
    geoip2 = geoip2.database


LOGGER = logging.getLogger(__name__)


class GeoCountryResolver:
    """Resolve an IP to an ISO-3166 alpha-2 country using a local MMDB file.

    The raw IP is used only during the request and is never persisted. Missing,
    private, malformed, or unresolvable addresses return an empty country code.
    """

    def __init__(self, database_path: str | None) -> None:
        self.database_path = Path(database_path).expanduser() if database_path else None
        self._reader = None
        self._lock = Lock()
        self._warned = False

    def country_for_ip(self, raw_ip: str | None) -> str:
        ip = self._normalise_public_ip(raw_ip)
        if not ip or not self.database_path or geoip2 is None:
            self._warn_once()
            return ""
        reader = self._get_reader()
        if reader is None:
            return ""
        try:
            country = reader.country(ip).country.iso_code or ""
            return country.strip().upper()[:2]
        except Exception as error:
            # Never log raw addresses; the exception category is sufficient for production diagnosis.
            LOGGER.warning("GeoIP country lookup failed: %s", type(error).__name__)
            return ""

    def diagnostics(self) -> dict[str, object]:
        """Return safe operational state without exposing request IPs or database contents."""
        return {
            "database_path_configured": bool(self.database_path),
            "database_exists": bool(self.database_path and self.database_path.is_file()),
            "reader_ready": self._reader is not None,
            "geoip_library_available": geoip2 is not None,
        }

    def _get_reader(self):
        if self._reader is not None:
            return self._reader
        with self._lock:
            if self._reader is not None:
                return self._reader
            if not self.database_path or not self.database_path.is_file():
                self._warn_once()
                return None
            try:
                self._reader = geoip2.Reader(str(self.database_path))
            except Exception:
                LOGGER.exception("Unable to open GeoIP country database: %s", self.database_path)
                self._warn_once()
                return None
            return self._reader

    def _warn_once(self) -> None:
        if not self._warned:
            self._warned = True
            LOGGER.warning("GeoIP country lookup unavailable; geo_country will remain empty")

    @staticmethod
    def _normalise_public_ip(raw_ip: str | None) -> str:
        try:
            address = ipaddress.ip_address((raw_ip or "").strip())
        except ValueError:
            return ""
        if address.is_private or address.is_loopback or address.is_link_local or address.is_reserved:
            return ""
        return str(address)
