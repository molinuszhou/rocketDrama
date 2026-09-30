# JoyeDrama Rewards Service — AWS Production Deployment

This runbook deploys the PostgreSQL rewards ledger and FastAPI service behind HTTPS. It is intended for the formal Android package `com.feather.joyelledrama`.

> **Required before Play review:** the public rewards API must be online, HTTPS-valid, and reachable from the reviewer’s network. The Android Release build uses Pangle SDK content and this rewards API; neither should point to LAN or emulator addresses.

## 1. AWS prerequisites

1. Create an EC2 instance with Docker Engine and the Docker Compose plugin installed.
2. Assign a public IP (or load balancer) and create an A/AAAA DNS record for `REWARDS_DOMAIN`.
3. Allow inbound **TCP 80** and **TCP 443** only. Do **not** expose PostgreSQL (5432) or the internal API port (8100).
4. Clone the release branch onto the host.
5. Copy `.env.production.example` to `.env.production`, set long random passwords/tokens, and restrict file permissions:

```bash
cd services/rewards
cp .env.production.example .env.production
chmod 600 .env.production
```

## 2. Production-only verification requirement

Set `PANGLE_CALLBACK_SECRET` from the production TopOn/Pangle server-to-server reward callback or equivalent provider signature verifier.

- `ENVIRONMENT=production` refuses `ALLOW_DEBUG_AD_PROOF=true`.
- The service now refuses to start in production without `PANGLE_CALLBACK_SECRET`.
- Never place this secret in Android `local.properties`, Gradle properties, an APK, or a client HTTP request.
- Android test proofs such as `TEST_PANGLE_REWARD` and `TEST_TOPON_REWARD` are development-only and production rejects them.

Before enabling cash-out-connected rewarded actions on B, complete the provider’s callback configuration so that a real rewarded completion can be verified by the server. The client must **not** create a cash reward solely from its SDK callback.

## 3. Start the stack

```bash
cd services/rewards
docker compose --env-file .env.production -f docker-compose.production.yml up -d --build
docker compose --env-file .env.production -f docker-compose.production.yml ps
curl -fsS https://YOUR_REWARDS_DOMAIN/health
```

Caddy automatically obtains and renews the TLS certificate after DNS propagation. The FastAPI service applies incremental PostgreSQL migrations when it starts, so existing database volumes receive the current schema safely.

## 3A. Existing Nginx host (recommended when 80/443 are already in use)

If the server already has Nginx serving another product, **do not start the Caddy compose stack above**: it would compete for ports 80 and 443. Use the Nginx-specific stack instead. It publishes the API only on `127.0.0.1:18100`; Nginx remains the only internet-facing TLS service.

```bash
cd services/rewards
cp .env.nginx.example .env.production
chmod 600 .env.production
# Set the real passwords, PANGLE_CALLBACK_SECRET and ADMIN_DASHBOARD_TOKEN.
ss -ltnp | grep ':18100' || true  # must show no existing listener before using the default port
docker compose -p joyedrama-rewards --env-file .env.production -f docker-compose.nginx.yml up -d --build
curl -fsS http://127.0.0.1:18100/health
```

The explicit `-p joyedrama-rewards` project name isolates the new container names, Docker network and PostgreSQL volume from other Compose applications on the same host. If `18100` is already used, select a free high port, set `REWARDS_BIND_PORT` to that value, and change the Nginx `proxy_pass` port to the same value.

Copy [`deploy/nginx/joyedrama-rewards.location.conf`](../deploy/nginx/joyedrama-rewards.location.conf) **inside the existing HTTPS `server {}` block** for `www.joyelleasia.com` (not directly inside the top-level `http {}` block). Then validate and reload the existing Nginx process:

```bash
nginx -t
nginx -s reload
curl -fsS https://www.joyelleasia.com/joyedrama-rewards/health
```

For this path-based route, the Android setting must be:

```properties
REWARDS_API_BASE_URL=https://www.joyelleasia.com/joyedrama-rewards
```

The Nginx `proxy_pass` rule removes `/joyedrama-rewards/` before proxying, so FastAPI still receives its normal `/health`, `/v2/...`, and `/admin` paths. This route does not change the existing `/`, `/api`, `/images`, or `/h5` proxy behavior.

## 4. Android Release configuration

Keep final Android production values outside Git, for example in CI secrets or the signer’s local `android/local.properties`:

```properties
TOPON_DEMO_MODE=false
TOPON_APP_ID=...
TOPON_APP_KEY=...
TOPON_REWARDED_PLACEMENT_ID=...
TOPON_INTERSTITIAL_PLACEMENT_ID=...
TOPON_SPLASH_PLACEMENT_ID=...
ADMOB_APP_ID=...
APPSFLYER_DEV_KEY=...
SHORT_DRAMA_APP_ID=...
SHORT_DRAMA_VOD_APP_ID=...
SHORT_DRAMA_LICENSE_ASSET=your_license_file_in_assets
SHORT_DRAMA_SECURITY_KEY=...
REWARDS_API_BASE_URL=https://YOUR_REWARDS_DOMAIN
EXTERNAL_REPORTING_ENABLED=true
RELEASE_AB_ROUTING_ENABLED=true
TOPON_REWARDED_LOAD_TIMEOUT_MS=10000
```

The Release variant enforces formal TopOn/Pangle configuration and `com.feather.joyelledrama`. It forces `SHORT_DRAMA_SDK_ENABLED=true` and `RELEASE_AB_ROUTING_ENABLED=true`; it no longer loads the LAN/local Content API.

## 5. Acceptance checklist

- `https://YOUR_REWARDS_DOMAIN/health` returns `status: ok` and `environment: production`.
- A fresh **Organic** AppsFlyer install enters **A**: episode unlock may request rewarded video; non-unlock cash/task/wallet/Crown actions use an actual interstitial, then settle exactly once.
- A fresh **Non-organic** AppsFlyer install enters **B**: existing rewarded logic applies.
- The local reporting-audit export shows `AppsFlyer,attribution_surface` with `surface=A` or `surface=B`.
- An A incentive is never granted when the interstitial fails or is skipped.
- A real B rewarded completion is accepted only after provider-backed server verification.
- The Pangle SDK loads catalog and player content; the app does not call `LOCAL_CONTENT_API_BASE_URL` in Release.
- Verify AppsFlyer, Firebase and Xuanyu events against the existing in-app reporting audit export.

## 6. Operational commands

```bash
# Live API logs
docker compose --env-file .env.production -f docker-compose.production.yml logs -f rewards-api

# Caddy certificate / reverse-proxy logs
docker compose --env-file .env.production -f docker-compose.production.yml logs -f caddy

# Controlled upgrade after pulling a newer release commit
git pull --ff-only
docker compose --env-file .env.production -f docker-compose.production.yml up -d --build
```

Back up the PostgreSQL named volume before database-wide policy changes. Do not use production database rebuilds as a release migration strategy.
