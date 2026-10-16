from __future__ import annotations

from app.main import app as _all_routes_app
from app.service_roles import configure_public_api_routes

# Keep app.main as the shared route declaration during the compatibility transition,
# but remove all /admin and /v2/admin routes from the mobile-facing process.
app = configure_public_api_routes(_all_routes_app)
