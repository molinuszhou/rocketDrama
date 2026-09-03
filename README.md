# JoyeDrama / DramaFlow

本仓库包含 Android 短剧客户端、局域网自有短剧内容服务和独立 PostgreSQL 积分服务。三者分别部署，并且不提交任何广告凭据、Firebase 配置、License、媒体文件或运行时账本。

| 目录 | 内容 | 默认端口 |
|---|---|---:|
| `android/` | Kotlin 原生 Android 客户端，包名 `com.dramaflow.app` | — |
| `services/content/` | 自有短剧目录、封面、分集和播放会话服务 | 8000 |
| `services/rewards/` | PostgreSQL 广告、宝石、签到、宝箱、轮次和权益结算服务 | 8100 |

## Windows 本地联调

先按 `services/content/docs/WINDOWS_LAN_DEPLOYMENT_AND_TEST.md` 启动内容服务，再按 `services/rewards/docs/WINDOWS_POSTGRES_REWARDS_DEPLOYMENT.md` 用 Docker Desktop 启动奖励服务。Android 真机应分别填写 `http://电脑IPv4:8000` 和 `http://电脑IPv4:8100`；不要使用 `localhost` 或 `127.0.0.1`。

Android 的敏感配置仅通过本地 `local.properties` 注入。可从 `android/partner.properties.example` 复制并填写受控值；不要将真实值提交到 Git。

详细客户端验收步骤见 `android/DRAMAFLOW_POSTGRES_REWARDS_PLAYER_TEST_RUNBOOK.md`。
