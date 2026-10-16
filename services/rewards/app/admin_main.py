from __future__ import annotations

from app.main import app as _all_routes_app
from app.service_roles import configure_admin_routes

# The management process deliberately does not expose mobile reward, wallet, or
# advertising-write routes. Nginx sends only /admin and /v2/admin traffic here.
app = configure_admin_routes(_all_routes_app)
