from __future__ import annotations

from fastapi.responses import HTMLResponse

from app.admin_dashboard import USER_TIMELINE_HTML
from app.main import app as _all_routes_app
from app.service_roles import configure_admin_routes

# The management process deliberately does not expose mobile reward, wallet, or
# advertising-write routes. Nginx sends only /admin and /v2/admin traffic here.
app = configure_admin_routes(_all_routes_app)


@app.get("/admin/timeline", response_class=HTMLResponse, include_in_schema=False)
def admin_user_behavior_timeline_page() -> HTMLResponse:
    """Admin-only focused page; data is fetched through the existing admin analytics API."""

    return HTMLResponse(
        USER_TIMELINE_HTML,
        headers={
            "Content-Security-Policy": "default-src 'self'; base-uri 'none'; frame-ancestors 'none'; object-src 'none'; form-action 'self'; connect-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'",
            "Referrer-Policy": "no-referrer",
            "X-Content-Type-Options": "nosniff",
        },
    )
