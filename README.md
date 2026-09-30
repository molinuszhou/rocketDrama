# JoyeDrama — Formal Production Release

This branch is the production-release line for the Android package **`com.feather.joyelledrama`**.

| Component | Production responsibility |
|---|---|
| `android/` | Native Android client. The Release variant uses the Pangle/PSSDK short-drama catalogue and player. |
| `services/rewards/` | PostgreSQL rewards ledger, ad-event audit, wallet/task settlement, administration and production Docker deployment. |
| `services/README.md` | Service deployment scope and entry points. |

## Production content and ads

The formal Release variant does **not** use a self-hosted local content API or local video files. Catalogue discovery and playback come from the authorised Pangle/PSSDK integration. The Android Release build also enforces the AppsFlyer monetisation split: Organic users receive surface A, while attributed non-organic users receive surface B.

Rewards remain a separate HTTPS backend. Before a formal build, configure the production TopOn, AppsFlyer, Firebase, Pangle/PSSDK and Rewards API values in ignored `android/local.properties` or protected CI secrets. Start from [`android/partner.properties.example`](android/partner.properties.example).

## Deploying the rewards backend

For a server that already runs Nginx and owns ports 80/443, follow the Nginx coexistence workflow in [`services/rewards/docs/AWS_PRODUCTION_DEPLOYMENT.md`](services/rewards/docs/AWS_PRODUCTION_DEPLOYMENT.md). It runs the Rewards API only on host loopback and publishes it through an isolated HTTPS path, without changing existing site routes.

For a dedicated host without an existing reverse proxy, the same guide includes the Caddy-based deployment option.

## Branch discipline

- `main` retains the previous development/test history.
- `release/formal-production` is the formal release line.
- Production credentials, Firebase configuration, PSSDK license files, signing keys and `.env.production` must never be committed.
