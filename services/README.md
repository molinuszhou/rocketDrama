# JoyeDrama Formal Release Services

This Release branch intentionally deploys **only the Rewards service**.

- Short-drama catalogue and playback are supplied by the integrated Pangle/PSSDK client path.
- The former local `services/content` test service, local media samples and aggregate development Compose file are not part of this branch.
- For a host that already runs Nginx on ports 80/443, use `rewards/docker-compose.nginx.yml` and the Nginx location fragment in `rewards/deploy/nginx/`.
- For a dedicated host with no existing TLS reverse proxy, use `rewards/docker-compose.production.yml`, which includes Caddy.

See [`rewards/docs/AWS_PRODUCTION_DEPLOYMENT.md`](rewards/docs/AWS_PRODUCTION_DEPLOYMENT.md) for the production runbook.
