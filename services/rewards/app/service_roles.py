from __future__ import annotations

from fastapi import FastAPI


ADMIN_PATH_PREFIX = "/v2/admin/"
ADMIN_DASHBOARD_PATH = "/admin"
HEALTH_PATH = "/health"


def _route_path(route: object) -> str:
    return str(getattr(route, "path", ""))


def configure_public_api_routes(app: FastAPI) -> FastAPI:
    """Expose only mobile-facing Rewards routes from the API process."""

    app.router.routes[:] = [
        route
        for route in app.router.routes
        if not (
            _route_path(route) == ADMIN_DASHBOARD_PATH
            or _route_path(route).startswith(ADMIN_PATH_PREFIX)
        )
    ]
    app.openapi_schema = None
    return app


def configure_admin_routes(app: FastAPI) -> FastAPI:
    """Expose only the management dashboard, management API, and health check."""

    app.router.routes[:] = [
        route
        for route in app.router.routes
        if (
            _route_path(route) == ADMIN_DASHBOARD_PATH
            or _route_path(route).startswith(ADMIN_PATH_PREFIX)
            or _route_path(route) == HEALTH_PATH
            or _route_path(route) in {"/openapi.json", "/docs", "/docs/oauth2-redirect", "/redoc"}
        )
    ]
    app.openapi_schema = None
    return app
