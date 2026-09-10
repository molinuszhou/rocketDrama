# JoyeDrama

本仓库包含 Android 短剧客户端、局域网自有短剧内容服务和独立 PostgreSQL 积分服务。Android 客户端、服务端与测试资源的职责保持独立；所有敏感配置、Firebase 文件、广告参数、License、媒体文件和运行时账本均不提交。

| 目录 | 内容 | 默认端口 |
|---|---|---:|
| `android/` | Kotlin 原生 Android 客户端，包名 `com.dramaflow.app` | — |
| `services/content/` | 自有短剧目录、封面、分集和短时播放会话服务 | 8000 |
| `services/rewards/` | PostgreSQL 广告、宝石、钱包、签到、随机宝箱、皇冠和内容解锁结算服务 | 8100 |
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

不要在真机中使用 `localhost`、`127.0.0.1` 或 `10.0.2.2`。完整的 Windows 服务、媒体路径、数据库升级、v8 奖励规则和验收说明分别见：

- `services/content/docs/WINDOWS_LAN_DEPLOYMENT_AND_TEST.md`
- `services/rewards/docs/WINDOWS_POSTGRES_REWARDS_DEPLOYMENT.md`
- `services/rewards/docs/ARCHITECTURE.md`（v8 随机宝箱、广告规则与运营后台）

## 停止与重置

以下命令同样需要在 `services/` 目录执行：

```powershell
cd C:\JoyeDrama\services

# 停止容器，但保留 PostgreSQL 账本。
docker compose down

# 仅测试重置：删除 PostgreSQL 账本数据卷；下次启动会重新执行 001–007 初始化脚本。
docker compose down -v
docker compose up -d --build
```

Android 的敏感配置仅通过本地 `local.properties` 注入。可从 `android/partner.properties.example` 复制并填写受控值；不要将真实值提交到 Git。

## v8 随机宝箱、激励广告与运营后台

当前规则固定为**前三集免费**；从第 4 集起，完播或点选锁集时先显示确认，用户选择后必须完整观看**激励广告**才会解锁并自动播放下一集。欢迎奖励、每日签到、随机宝箱开启、悬浮球全部领取、当前分集翻倍和钱包广告加速均属于用户主动操作，统一使用激励广告结算。本集翻倍可在播放过程中预先开启，完播后待领取宝石会自动按双倍累计；同一集最多双倍一次。

随机宝箱替代十集加奖：用户每日自动获得后台配置数量的宝箱，每累计 3 集有效观看默认增加 1 只。获得宝箱后会提示“立即开启”或“继续观看”；每只宝箱都需完整观看激励广告，服务端会在可配置最小/最大区间内随机发放宝石，库存可连续开启。签到完成后再次打开签到奖励会引导用户看广告尝试开启宝箱。

播放器每集默认播放至第 30 秒尝试一次剧中插屏；每次拖拽进度条和每次调整播放速度也各自独立尝试一次插屏。三者不共享冷却；广告关闭、跳过或无填充后都会恢复原播放进度。皇冠成长包含青铜、白银、黄金、钻石、皇冠五级，头像等级会提升未来每集的宝石倍率；默认新手 `1.00x`、皇冠 `2.00x`。完整观看 **2 段激励广告**可完成一次成长加速，该数量可在后台调整。

钱包页面只展示“宝石、钱包余额、可提现余额”、预计明日可转入金额和广告加速预计额外转入金额。广告加速默认按当日基础可提现转入的 `20%` 计算；完成一次加速后用户可继续选择下一次，比例可在后台设置，默认不限制每日次数。

在 Windows 电脑浏览器打开 `http://localhost:8100/admin`，输入 `.env` 内的 `ADMIN_DASHBOARD_TOKEN`，即可调整随机宝箱区间、每日/追剧产箱数量、剧中/拖拽/倍速插屏、皇冠成长广告数量、钱包广告加速比例与每日上限，以及五级皇冠未来单集宝石倍率。已产生的宝箱、奖励和账本不会被回溯改写。

详细客户端验收步骤见 `android/DRAMAFLOW_POSTGRES_REWARDS_PLAYER_TEST_RUNBOOK.md`。
