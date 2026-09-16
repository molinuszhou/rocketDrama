# JoyeDrama 独立 PostgreSQL 奖励服务：Windows 部署与联调手册

**文档版本：v1.6.3**
**适用环境：Windows 10/11、Docker Desktop、Android 真机或模拟器**

本服务负责广告事件、宝石账本、钱包余额、可提现余额、签到、随机宝箱、逐集待领取宝石、皇冠成长、内容解锁权益与插屏频控。它和自有短剧内容服务是**独立的两个服务**：content service 负责目录、分集、短时播放 URL 和媒体文件；Rewards API 只负责奖励与权益结算。

> 不要把奖励服务地址写成内容服务的 `:8000`。默认内容服务为 **8000**，Rewards API 为 **8100**。所有 Docker 操作都必须从仓库的 `services/` 目录执行。

## 1. 部署前准备

请在 Windows 安装并启动 Docker Desktop。Docker Desktop 的 Windows 安装与系统要求以官方文档为准。[1] 本手册使用 Docker Compose 同时启动 PostgreSQL、content service 和 Rewards API；Compose 的 `.env` 文件用于向编排提供变量值。[2]

| 项目 | 最低要求 | 用途 |
|---|---|---|
| Windows | Windows 10 或 Windows 11 | 运行 Docker Desktop 与 PowerShell。 |
| Docker Desktop | 当前稳定版 | 启动 PostgreSQL、内容服务与 Rewards API。 |
| 网络 | 手机与电脑处于同一局域网 | Android 真机请求 `:8000`、`:8100` 接口。 |
| 端口 | TCP 8100 可被手机访问 | Rewards API。 |
| 磁盘 | 至少 2 GB 可用空间 | Docker 镜像与 PostgreSQL 持久卷。 |

## 2. 配置环境变量

将仓库放在例如 `C:\JoyeDrama`，进入 `services/` 后创建本地环境文件：

```powershell
cd C:\JoyeDrama\services
Copy-Item .env.example .env
notepad .env
```

至少设置以下项目。`POSTGRES_PASSWORD`、`PLAYBACK_SIGNING_KEY`、`ADMIN_DASHBOARD_TOKEN` 与未来 `PANGLE_CALLBACK_SECRET` 都属于敏感值，不能提交 Git、不能发送到公开位置、不能写入 Android 包。

```dotenv
POSTGRES_DB=dramaflow_rewards
POSTGRES_USER=dramaflow
POSTGRES_PASSWORD=请替换为高强度数据库密码
POSTGRES_PORT=5432
REWARDS_PORT=8100
ENVIRONMENT=development
TIMEZONE=Asia/Shanghai

# 仅 Android Debug 的 Pangle/TopOn 测试环境允许；生产环境必须关闭。
# Debug 客户端可使用 TEST_PANGLE_REWARD 或 TEST_TOPON_REWARD；真实广告完成仍以 SDK 回调为前提。
ALLOW_DEBUG_AD_PROOF=true
PANGLE_CALLBACK_SECRET=

# 奖励运营后台：http://localhost:8100/admin
ADMIN_DASHBOARD_TOKEN=请替换为高强度后台令牌
CORS_ORIGINS=http://localhost
```

## 3. 启动与升级

在同一 PowerShell 窗口执行：

```powershell
docker compose up -d --build
docker compose ps
docker compose logs -f rewards-api
```

空的 PostgreSQL 数据卷会依次执行 `001_init.sql` 至 `012_v163_round_gem_values_v2.sql`。[3] 已存在的数据库卷由 Rewards API 启动时根据 `schema_migrations` **仅执行缺失版本**，因此升级至 v1.6.3.2 通常不需要清空账本。`010` 会新增区域钱包策略、四档阻尼字段与 2,000,000 Gems 上限；`011` 和 `012` 会将未来签到、皇冠及十轮兼容奖励调整为最终整百数值。迁移不会回溯修改用户已入账的 Gems 或现金金额。

> 不要使用 `docker compose down -v` 来升级。该命令会删除 PostgreSQL 持久卷。只有明确要从零重建测试数据时才可以使用。

出现 API 监听日志后，使用以下命令检查：

```powershell
Invoke-RestMethod http://localhost:8100/health
```

若失败，先查看：

```powershell
docker compose ps
docker compose logs --tail=200 postgres
docker compose logs --tail=200 rewards-api
```

## 4. Windows 防火墙与局域网地址

以管理员身份打开 PowerShell，放行 Android 真机访问 Rewards API：

```powershell
New-NetFirewallRule `
  -DisplayName "JoyeDrama Rewards API TCP 8100" `
  -Direction Inbound `
  -Action Allow `
  -Protocol TCP `
  -LocalPort 8100 `
  -Profile Private
```

运行 `ipconfig`，选择正在使用的 Wi-Fi 或以太网适配器中的 IPv4 地址，不要使用 `127.0.0.1`、虚拟网卡或 VPN 地址。若电脑 IP 为 `192.168.1.25`，先在电脑和手机浏览器验证：

```text
http://192.168.1.25:8100/health
```

## 5. Android 联调与验收

在 Android 应用进入：

> **我的 → 积分与奖励服务 → 保存并同步**

真机填写 `http://电脑局域网IPv4:8100`，例如：

```text
http://192.168.1.25:8100
```

模拟器通常使用 `http://10.0.2.2:8100`。不要填内容服务的 `:8000` 地址。

| 验收路径 | 预期结果 |
|---|---|
| 第 1–3 集 | 免费观看；不触发集间解锁广告。 |
| 第 4 集及以后 | 选中锁集或完播后先显示“免费集数已用完”确认；用户点击“看激励广告解锁”并完整观看后，服务端和内容服务解锁并自动播放目标集。 |
| 每集剧中广告 | 每次进入分集，在后台配置的默认第 **45 秒**尝试展示一次插屏；广告关闭、跳过或无填充后恢复原进度。 |
| 拖拽进度条与倍速 | 每次用户完成拖拽、每次确认调整播放速度时均按独立策略尝试展示插屏；广告关闭、跳过或无填充后执行目标操作并恢复播放。 |
| 每集有效完播 | 生成一笔待领取宝石，悬浮球累计展示；不自动写入宝石余额。当前皇冠等级会影响未来单集奖励倍率。 |
| 悬浮球领取 | 用户点击后完整观看一次 `rv_episode_rewards_claim` 激励广告，再消费该广告授权通过服务端一次性领取全部积累宝石。已开启翻倍的分集按双倍结算。 |
| 本集翻倍 | 用户在分集播放期间即可完整观看一次 `rv_episode_reward_boost` 预先开启翻倍；分集完播后待领取宝石自动按双倍生成，同一集不能重复翻倍。 |
| 随机宝箱 | 每日自动获得后台配置数量的宝箱；每累计 3 集有效观看默认再获得 1 个。每只宝箱完整观看 `rv_random_chest_open` 后由服务端在配置区间内随机发放宝石，可连续开启。 |
| 欢迎奖励与签到 | 首次欢迎奖励必须完整观看 `rv_welcome_bonus` 激励广告后到账；当日签到必须完整观看 `rv_checkin_complete` 激励广告后完成。已签到后再次打开连续签到奖励，点击“看广告开宝箱”；广告完成后若没有库存，应用提示“当前没有可开启的宝箱”。 |
| 皇冠成长 | 青铜、白银、黄金、钻石、皇冠五级。当前头像等级显示单集宝石倍率；完整观看 2 段激励广告可完成一次成长加速，后台可调整广告数量；达成下一等级后，后续新生成的单集奖励采用该倍率。 |
| 钱包 | 页面显示宝石、钱包余额、可提现余额、预计明日可转入金额和广告加速预计额外转入金额。默认 `10,000 Gems = US$1.00`；巴西默认 `10,000 Gems = R$3.00`，印尼默认 `10,000 Gems = Rp10,000`，本币页面同时显示美元参考值。广告加速默认按当日基础转入的 20% 计算，完成后显示最新预计明日可转入金额并始终可选择继续加速。 |
| 暂停广告 | 仅在启用独立 Native 广告位时请求；应用始终提供“返回播放”，关闭后保持暂停并显示半透明“继续播放”按钮；未配置或无填充时同样显示该暂停按钮。 |

## 6. 运营后台

在运行 Rewards API 的 Windows 电脑浏览器打开：

```text
http://localhost:8100/admin
```

输入 `.env` 中的 `ADMIN_DASHBOARD_TOKEN`。后台可调整以下**未来规则**，每项保存都会写入 `reward_policy_admin_audit`：

| 配置区域 | 可调项目 |
|---|---|
| 随机宝箱 | 最小/最大宝石、每日自动宝箱数量、每多少集产生一个追剧宝箱。 |
| 插屏 | 剧中、拖拽、倍速广告开关；剧中触发秒数与小时上限（设置 `0` 表示不设置后台小时上限）。 |
| 皇冠与钱包 | 皇冠成长广告数量；钱包广告加速比例和每日次数上限（`0` 表示不限制）。 |
| 全渠道阻尼 | 用户累计 Gems 上限、第一至第三档最大累计 Gems、第一至第四档倍率。欢迎、签到、分集/翻倍、随机宝箱与皇冠奖励均在结算时适用同一档位。默认上限为 2,000,000；边界为 500,000、900,000、1,200,000；倍率为 1.00、0.70、0.45、0.25。 |
| 整百奖励 | 按最终运营确认值执行：签到第 1 天 600、签到第 2 天 1,200、签到第 5 天 3,000、签到第 6 天 5,000；皇冠青铜 12,000、白银 26,000、黄金 66,000；十轮兼容基数 10,000。历史已入账奖励不回溯调整。 |
| 区域钱包 | DEFAULT/USD、BR/BRL、ID/IDR 的每 10,000 Gems 本币金额、美元参考、提现门槛、欢迎 Gems 与每日转入额度。金额以“当地货币 × 1,000,000”的微单位填写；广告加速仍统一按全局“基础转入 × 加速比例”计算。 |
| 皇冠倍率 | 青铜、白银、黄金、钻石、皇冠五级未来单集宝石倍率。 |

已生成的宝箱、待领取分集奖励、已领取金额和已经达成的皇冠奖励不会被运营后台回溯修改。不要将 `:8100/admin` 直接暴露到公网；生产环境必须部署在 HTTPS、反向代理、访问控制和审计体系之后。

## 7. 日常运维与备份

查看运行状态和日志：

```powershell
docker compose ps
docker compose logs -f rewards-api
docker compose logs -f postgres
```

停止服务但保留数据：

```powershell
docker compose down
```

创建备份：

```powershell
New-Item -ItemType Directory -Force .\backups | Out-Null
docker compose exec -T postgres pg_dump -U dramaflow dramaflow_rewards > .\backups\dramaflow_rewards_$(Get-Date -Format yyyyMMdd_HHmmss).sql
```

恢复前先停止 API 并确认目标无误：

```powershell
docker compose stop rewards-api
Get-Content .\backups\dramaflow_rewards_YYYYMMDD_HHMMSS.sql | docker compose exec -T postgres psql -U dramaflow -d dramaflow_rewards
docker compose start rewards-api
```

## 8. 常见问题

| 现象 | 优先排查 |
|---|---|
| App 显示“积分服务尚未连接” | 确认填写 `http://电脑IPv4:8100`，而不是内容服务 `:8000`。 |
| 手机浏览器访问 `:8100/health` 失败 | 检查 Docker Desktop、`docker compose ps`、Windows Private 防火墙规则和同一 Wi-Fi。 |
| PostgreSQL 升级失败 | 执行 `docker compose up -d --build` 后查看 `docker compose logs --tail=200 rewards-api`；不要先执行 `down -v`。 |
| 看完广告没有奖励 | 检查 API 地址、Rewards API 日志、Debug 环境的 `ALLOW_DEBUG_AD_PROOF=true`；正式环境必须使用广告服务端验签。 |
| 宝箱广告完成后提示没有库存 | 这是正常保护：广告完成时没有任何未开启宝箱。继续签到或有效观看到下一个产箱节点后再试。 |
| 钱包金额与预期不一致 | 以 PostgreSQL 账本和 API 快照为准，不以 Android 本地缓存为准。 |

## 9. 开发与生产边界

`ALLOW_DEBUG_AD_PROOF=true` 只用于 Android Debug 的 Pangle/TopOn 测试环境。正式上架前必须改为 `false`，并接入聚合广告或广告平台的服务端回调/验签；不能使用客户端测试证明。提现当前只提供透明的余额与资格预检；开放真实支付前应独立建设身份校验、反作弊、支付通道、限额、人工审核、争议处理、对账及合规流程。

## References

[1] [Docker Desktop for Windows 官方安装文档](https://docs.docker.com/desktop/setup/install/windows-install/)

[2] [Docker Compose 环境变量官方文档](https://docs.docker.com/compose/how-tos/environment-variables/set-environment-variables/)

[3] [PostgreSQL 官方 Docker 镜像文档](https://hub.docker.com/_/postgres)


## 10. Bootstrap 返回 HTTP 500 时：重建 Rewards 数据库

当 Android 日志已显示实际请求 `http://电脑局域网IPv4:8100`，但“奖励服务同步失败”包含 `HTTP 500` 时，表示手机、局域网地址和端口已正常连通，异常发生在 Rewards API 的数据库业务处理阶段。v8.1 的插屏奖励阈值新增了 `reward_policy.action_interstitial_reward_threshold_gems` 字段；本版本已将 `008_v81_ad_fatigue_and_crown_curve.sql` 同时加入 Docker 新库初始化与 API 运行时迁移，避免新旧数据库遗漏该字段。

测试阶段允许清空积分数据时，可只重建 Rewards PostgreSQL 卷。此操作会删除测试用户、宝石、钱包、签到、宝箱、解锁和皇冠账本；不会删除 `content/media` 中的本地短剧视频与封面文件。

```powershell
cd C:\JoyeDrama

git pull
cd services

# 先停止 Rewards API 与 PostgreSQL；Content API 可继续运行。
docker compose stop rewards-api postgres

# 查询并确认当前项目的 Rewards 数据卷名称。
docker volume ls

# Compose 项目名通常为 services；仅删除以 rewards_postgres_data 结尾的奖励数据库卷。
docker volume rm services_rewards_postgres_data

# 用 001–012 规则重新创建数据库并启动 Rewards API。
docker compose up -d --build postgres rewards-api

docker compose logs --tail=200 rewards-api
Invoke-RestMethod http://127.0.0.1:8100/health
```

若 `services_rewards_postgres_data` 不存在，请以 `docker volume ls` 输出为准，删除名称以 `_rewards_postgres_data` 结尾且属于当前 JoyeDrama 项目的卷。`/health` 返回 JSON 中包含 `"status":"ok"` 后，在 Android Debug 包中彻底关闭并重新打开应用；新用户会自动创建，随后应正常返回新人奖励、签到、宝箱、皇冠和插屏策略快照。
