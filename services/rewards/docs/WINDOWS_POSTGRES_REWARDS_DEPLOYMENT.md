# DramaFlow 独立 PostgreSQL 积分服务：Windows 部署与联调手册

**文档版本：v5.0.0**
**适用环境：Windows 10/11、Docker Desktop、Android 真机或模拟器**

本服务负责广告事件、宝石账本、新人欢迎奖励、三层钱包兑换与释放、签到、每日宝箱、追剧领奖、皇冠成就和插屏频控。它与自有短剧内容服务是**两个独立进程、两个独立端口、两套数据边界**：内容服务只负责目录、分集、短时播放 URL 与媒体文件；本服务只负责奖励与权益结算。

> 不要把积分服务地址写成短剧内容服务的 `:8000`。默认情况下，内容服务使用 **8000**，独立 PostgreSQL 积分服务使用 **8100**。
>
> **推荐：从 `services/` 目录统一启动。** `services/docker-compose.yml` 会同时启动内容服务、PostgreSQL 与本服务。请先进入仓库的 `services/` 目录，再执行 `docker compose up -d --build`，不需要分别启动内容服务。本手册保留数据库规则、奖励验收、备份和故障排查说明。

## 1. 部署前准备

请在 Windows 安装并启动 Docker Desktop。Docker Desktop 的 Windows 安装与系统要求以官方文档为准。[1] 本手册使用 Docker Compose 同时启动 PostgreSQL 16 和 Rewards API；Compose 的 `.env` 文件用于向编排文件提供变量值。[2]

| 项目 | 最低要求 | 用途 |
|---|---|---|
| Windows | Windows 10 或 Windows 11 | 运行 Docker Desktop 与 PowerShell |
| Docker Desktop | 当前稳定版 | 启动 PostgreSQL 与 Rewards API |
| 网络 | 手机与电脑处于同一局域网 | Android 真机请求 `:8100` 接口 |
| 端口 | TCP 8100 可被手机访问 | Rewards API |
| 磁盘 | 至少 2 GB 可用空间 | PostgreSQL 镜像与持久化数据卷 |

## 2. 解压与配置环境变量

使用统一 Compose 时，请将仓库解压到例如 `C:\DramaFlow\JoyeDrama`，并在仓库的 **`services/` 目录**复制配置模板：

```powershell
cd C:\DramaFlow\JoyeDrama\services
Copy-Item .env.example .env
notepad .env
```

在 `.env` 中至少设置如下项目。请使用长随机密码；`POSTGRES_PASSWORD`、`ADMIN_DASHBOARD_TOKEN` 与未来的 `PANGLE_CALLBACK_SECRET` 都属于敏感值，不能提交 Git、不能发送到群聊、不能写入 Android 包。

```dotenv
POSTGRES_DB=dramaflow_rewards
POSTGRES_USER=dramaflow
POSTGRES_PASSWORD=请替换为高强度数据库密码
POSTGRES_PORT=5432
REWARDS_PORT=8100
ENVIRONMENT=development
TIMEZONE=Asia/Shanghai

# 仅本地 Pangle 测试阶段允许 Android 传入 TEST_PANGLE_REWARD。
ALLOW_DEBUG_AD_PROOF=true

# 生产环境收到 Pangle 服务端回调/验签方案后再填写，并同时关闭上面的开发开关。
PANGLE_CALLBACK_SECRET=

# 奖励运营后台：http://localhost:8100/admin
# 必须改为独立随机字符串，且仅自己保存。
ADMIN_DASHBOARD_TOKEN=请替换为高强度后台令牌
CORS_ORIGINS=http://localhost
```

## 3. 启动 PostgreSQL 与 Rewards API

在同一 PowerShell 窗口执行：

```powershell
docker compose up -d --build
docker compose ps
docker compose logs -f rewards-api
```

初次启动会拉取 PostgreSQL 镜像、构建 API 镜像并创建名为 `rewards_postgres_data` 的 Docker 持久卷。空数据卷会顺序执行 `001_init.sql`、`002_monetization_v3.sql`、`003_rewards_v4.sql` 和 `004_ad_flow_v5.sql`。[3]

> **v5 升级说明：** Rewards API 启动时会自动、幂等地补跑 `003_rewards_v4.sql` 与 `004_ad_flow_v5.sql`，因此已有数据库卷在执行 `docker compose up -d --build` 后无需手工删除或导入数据库。不要使用 `docker compose down -v` 来“升级”，那会删除真实账本。

当日志显示 API 已监听端口后，按 `Ctrl+C` 退出日志跟随模式；这不会停止后台服务。随后进行健康检查：

```powershell
Invoke-RestMethod http://localhost:8100/health
```

成功时应返回 `code` 为 `0` 的 JSON 响应。若失败，先执行：

```powershell
docker compose ps
docker compose logs --tail=200 postgres
docker compose logs --tail=200 rewards-api
```

## 4. 放行 Windows 防火墙并获取局域网地址

以**管理员身份**启动 PowerShell，放行 Android 真机访问 API 的 TCP 8100 端口：

```powershell
New-NetFirewallRule `
  -DisplayName "DramaFlow Rewards API TCP 8100" `
  -Direction Inbound `
  -Action Allow `
  -Protocol TCP `
  -LocalPort 8100 `
  -Profile Private
```

使用下列命令查看电脑局域网 IPv4 地址。选择正在使用的 Wi-Fi 或以太网适配器中的 `IPv4 Address`，不要使用 `127.0.0.1`、`localhost`、虚拟网卡或 VPN 地址。

```powershell
ipconfig
```

若电脑 IP 为 `192.168.1.25`，先在电脑浏览器或 PowerShell 验证：

```powershell
Invoke-RestMethod http://192.168.1.25:8100/health
```

再用与电脑同一 Wi-Fi 下的手机浏览器打开 `http://192.168.1.25:8100/health`。手机能返回 JSON 后，再进入 Android 应用设置。

## 5. Android 应用联调

在 Android 应用中依次进入：

> **我的 → 积分与奖励服务 → 保存并同步**

填入：

```text
http://192.168.1.25:8100
```

不要填入内容服务地址 `:8000`。模拟器在同一开发电脑上运行时，默认地址是 `http://10.0.2.2:8100`；真机必须填写电脑的实际局域网 IPv4 地址。

配置成功后，进入 **奖励** 页面应能看到新人欢迎奖励、宝石余额、现金余额、可提现余额、签到、每日宝箱、逐集待领取宝石、十集追剧加奖和五级皇冠之路；悬浮宝石球会显示在首页和播放页。开发测试中，完整观看 Pangle 激励广告后，Android 会提交开发广告证明；数据库服务以幂等广告事件 ID 结算，重复回调不会重复加宝石。

| 验收路径 | 预期结果 |
|---|---|
| 第 1–3 集 | 免费播放，不触发集间激励广告记账。 |
| 第 4 集及以后 | 锁定下一集开始前自动请求插屏；广告关闭后自动请求内容解锁并开始播放目标集。默认每小时最多 24 次，避免短剧连续追更被误拦截。 |
| 每集有效播放完成 | 服务端生成一笔待领取宝石，悬浮宝石球数值增加；点击领取后才记入宝石余额。 |
| 单集奖励翻倍 | 用户领取基础宝石后，可自主观看 `rv_episode_reward_double` 广告，再获得同额宝石。 |
| 十集追剧加奖 | 每累计 10 集有效观看生成一笔额外宝石；基础宝石可直接领取，领取后可观看 `rv_double_reward` 广告翻倍。 |
| 皇冠之路 | 青铜、白银、黄金、钻石、皇冠五级均采用严格的累计观看、签到和激励广告条件；达成时自动发放对应宝石。 |
| 每日签到 | 基础签到写入数据库；签到后可观看一次 `rv_signin_double` 获得翻倍。 |
| 每日宝箱 | 完成签到后可观看 `rv_chest_task` 广告领取一次。 |
| 主动额外解锁 | 下一集插屏解锁成功后，用户可自主选择观看激励广告，额外提前解锁后一集；拒绝不影响当前播放。 |
| 暂停广告 | 仅在第 4 集后且已播放至少 90 秒时请求 Native 暂停广告；由应用提供“返回播放”按钮，关闭后从原进度续播。需要 Android 配置独立 `PANGLE_PAUSE_NATIVE_SLOT_ID`，未配置则自动跳过。 |

## 6. 奖励运营后台：调整十轮奖励阻尼系数

在运行 Rewards API 的 Windows 电脑浏览器打开：

```text
http://localhost:8100/admin
```

输入 `.env` 中的 `ADMIN_DASHBOARD_TOKEN` 后，页面会显示下一集插屏、主动额外解锁与暂停广告的当前只读策略，以及首轮额外奖励、最低保底、每轮集数、当前阻尼系数和第 1、2、3、5、10、20 轮的奖励预览。当前仅开放阻尼系数编辑；广告规则仍由数据库迁移种子统一管理。阻尼系数公式为：

> 十轮额外奖励 = `首轮额外奖励 ÷ 轮次^α`，且不会低于最低保底。

`α` 越大，奖励下降越快；`α = 0` 表示不随轮次下降。保存后仅影响**未来新生成的十集加奖**，不会追溯修改用户已完成、已领取或已生成的奖励，所有变更都会写入 PostgreSQL 审计表。请不要将该端口开放到公网；生产运营必须置于 HTTPS、反向代理、访问控制与审计体系之后。

## 7. 日常运维与数据备份

查看运行状态与日志：

```powershell
docker compose ps
docker compose logs -f rewards-api
docker compose logs -f postgres
```

停止服务但保留数据库：

```powershell
docker compose down
```

创建数据库备份。请先创建本地 `backups` 文件夹：

```powershell
New-Item -ItemType Directory -Force .\backups | Out-Null
docker compose exec -T postgres pg_dump -U dramaflow dramaflow_rewards > .\backups\dramaflow_rewards_$(Get-Date -Format yyyyMMdd_HHmmss).sql
```

恢复到同名数据库前，应先停止 API，并确认恢复目标无误：

```powershell
docker compose stop rewards-api
Get-Content .\backups\dramaflow_rewards_YYYYMMDD_HHMMSS.sql | docker compose exec -T postgres psql -U dramaflow -d dramaflow_rewards
docker compose start rewards-api
```

> `docker compose down -v` 会删除 PostgreSQL 持久卷并清空本地奖励账本。除非明确要重置测试数据，否则不要使用 `-v`。

## 8. 开发模式与生产切换

`ALLOW_DEBUG_AD_PROOF=true` 只用于当前 Android Debug 包和 Pangle 测试环境。正式上架前必须把它改为 `false`，配置 `PANGLE_CALLBACK_SECRET`，并将真实广告完成回调/验签接入服务端；否则客户端传入的开发证明不能作为生产奖励依据。

提现在当前版本仅提供**透明的钱包换算和资格预检**，不会伪造到账或自动打款。开放真实提现前，应独立补充身份校验、反作弊、支付/转账通道、人工审核、限额、争议处理、对账及法务合规流程。

## 9. 常见问题

| 现象 | 优先排查 |
|---|---|
| App 显示“积分服务尚未连接” | 在 App 的“我的”中确认填写了 `http://电脑IPv4:8100`，而非 `:8000`。 |
| 手机浏览器访问 `:8100/health` 失败 | Docker Desktop 是否运行、`docker compose ps` 是否健康、Windows 防火墙是否放行 Private 网络、手机和电脑是否同网。 |
| 端口 8100 被占用 | 修改 `.env` 的 `REWARDS_PORT`，例如 `18100`，执行 `docker compose up -d`，并在 App 填写同一新端口。 |
| PostgreSQL 初始化规则未更新 | 新数据卷会自动执行 `001`、`002`、`003` 与 `004`。已有数据卷重建 Rewards API 时会自动幂等升级 `003_rewards_v4.sql` 与 `004_ad_flow_v5.sql`；测试环境仅在确认不保留账本时才使用 `docker compose down -v`。 |
| 广告看完后没有宝石 | 确认 API 地址为 `:8100`、日志没有数据库错误、开发阶段 `ALLOW_DEBUG_AD_PROOF=true`。正式环境必须使用服务端广告验签。 |
| 宝石/现金钱包与预期不一致 | 查看 `docker compose logs -f rewards-api`；以 PostgreSQL 账本与 API 返回为准，不以客户端缓存为准。 |

## References

[1] [Docker Desktop for Windows 官方安装文档](https://docs.docker.com/desktop/setup/install/windows-install/)

[2] [Docker Compose 环境变量官方文档](https://docs.docker.com/compose/how-tos/environment-variables/set-environment-variables/)

[3] [PostgreSQL 官方 Docker 镜像文档](https://hub.docker.com/_/postgres)
