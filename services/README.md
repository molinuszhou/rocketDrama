# JoyeDrama 正式服务范围

`release/formal-production` 分支只部署 **Rewards 服务**，不包含本地 Content 服务、测试片源或本地视频容器。

| 模块 | 正式环境职责 |
|---|---|
| Android Release | 通过 Pangle/PSSDK 获取短剧目录和播放器内容。 |
| `services/rewards/` | PostgreSQL 奖励账本、广告事件审计、钱包/任务结算、运营后台及 Docker 部署。 |
| 宿主机原生 Nginx | 继续负责既有站点及 HTTPS；将 `/joyedrama-rewards/` 反向代理到本机 Rewards API。 |

服务器已安装并配置 Nginx 时，仅使用 [`rewards/docker-compose.production.yml`](rewards/docker-compose.production.yml)。该编排只启动 PostgreSQL 和 Rewards API，Rewards API 仅监听 `127.0.0.1`，不占用 `80`、`443`、`3000` 或公网端口。

完整部署步骤见：[Rewards 正式部署说明](rewards/docs/AWS_PRODUCTION_DEPLOYMENT.md)。
