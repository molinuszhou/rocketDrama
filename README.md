# JoyeDrama

本仓库包含 Android 短剧客户端、局域网自有短剧内容服务和独立 PostgreSQL 积分服务。Android 客户端、服务端与测试资源的职责保持独立；所有敏感配置、Firebase 文件、广告参数、License、媒体文件和运行时账本均不提交。

| 目录 | 内容 | 默认端口 |
|---|---|---:|
| `android/` | Kotlin 原生 Android 客户端，包名 `com.dramaflow.app` | — |
| `services/content/` | 自有短剧目录、封面、分集和短时播放会话服务 | 8000 |
| `services/rewards/` | PostgreSQL 广告、宝石、三层钱包、签到、领奖和皇冠结算服务 | 8100 |
| `services/docker-compose.yml` | 统一启动内容服务、奖励 API 和 PostgreSQL 的 Windows 本地测试环境 | 8000、8100、5432 |
| `services/.env.example` | Docker Compose 的本地环境变量模板 | — |

## Windows 一键启动测试环境

Docker 相关文件全部位于 `services/`，与 Android 客户端代码解耦。两个服务的 Dockerfile 分别位于 `services/content/Dockerfile` 和 `services/rewards/Dockerfile`；统一 Compose 文件为 `services/docker-compose.yml`。

在 Windows PowerShell 中进入仓库的 `services` 目录后，首次配置并启动只需执行：

```powershell
cd C:\JoyeDrama\services
Copy-Item .env.example .env
notepad .env
# 至少替换 POSTGRES_PASSWORD、PLAYBACK_SIGNING_KEY 和 ADMIN_DASHBOARD_TOKEN。

docker compose up -d --build
docker compose ps
```

Docker 会把 `.env` 中的 Windows 主机路径映射到容器内部固定路径：`LOCAL_MEDIA_ROOT` 映射到 `/app/media`，`OWNED_CATALOG_PATH` 映射到 `/app/content/owned_catalog.json`。请确保这两个 Windows 路径真实存在，且 MP4 文件名与片单中的 `videoAssetId` 一致。示例：

```dotenv
LOCAL_MEDIA_ROOT=D:/gworkspace/JoyeDrama/services/content/media
OWNED_CATALOG_PATH=D:/gworkspace/JoyeDrama/services/content/content/owned_catalog.local-media.json
```

启动成功后，使用电脑的局域网 IPv4 地址在 Android App 中分别设置：

```text
内容服务：http://电脑IPv4:8000
积分与奖励服务：http://电脑IPv4:8100
```

不要在真机中使用 `localhost`、`127.0.0.1` 或 `10.0.2.2`。完整的 Windows 服务、媒体路径、数据库升级、v4 奖励规则和验收说明分别见：

- `services/content/docs/WINDOWS_LAN_DEPLOYMENT_AND_TEST.md`
- `services/rewards/docs/WINDOWS_POSTGRES_REWARDS_DEPLOYMENT.md`
- `services/rewards/docs/ARCHITECTURE.md`（v4 奖励规则与运营后台）

## 停止与重置

以下命令同样需要在 `services/` 目录执行：

```powershell
cd C:\JoyeDrama\services

# 停止容器，但保留 PostgreSQL 账本。
docker compose down

# 仅测试重置：删除 PostgreSQL 账本数据卷；下次启动会重新执行 001、002 与 003 初始化脚本。
docker compose down -v
docker compose up -d --build
```

Android 的敏感配置仅通过本地 `local.properties` 注入。可从 `android/partner.properties.example` 复制并填写受控值；不要将真实值提交到 Git。

## v4 奖励与运营后台

当前规则固定为**前三集免费**；从第 4 集起，播放结束后由用户主动确认是否看广告解锁下一集，广告完成后自动续播。每集有效观看会生成一笔**待领取宝石**，悬浮宝石球会累加显示；用户点击领取后基础宝石才进入钱包，随后可自主选择广告翻倍。每累计 10 集有效观看还会形成一笔额外的十集追剧加奖。

皇冠之路采用青铜、白银、黄金、钻石、皇冠五级严格累计条件，达成各级时由服务端自动发放宝石。钱包显示为“宝石 → 现金余额 → 可提现余额”。

在 Windows 电脑浏览器打开 `http://localhost:8100/admin`，输入 `.env` 内的 `ADMIN_DASHBOARD_TOKEN`，即可调整十集追剧加奖的阻尼系数。该设置仅影响之后新生成的十集加奖，不追溯改写任何既有账本。

详细客户端验收步骤见 `android/DRAMAFLOW_POSTGRES_REWARDS_PLAYER_TEST_RUNBOOK.md`。
