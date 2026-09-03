# DramaFlow 独立 PostgreSQL 积分服务：Windows 部署与联调手册

**文档版本：v1.0.0**
**适用环境：Windows 10/11、Docker Desktop、Android 真机或模拟器**

本服务负责广告事件、宝石账本、现金钱包换算、签到、每日宝箱、集间广告、十轮领奖、皇冠成就和插屏频控。它与自有短剧内容服务是**两个独立进程、两个独立端口、两套数据边界**：内容服务只负责目录、分集、短时播放 URL 与媒体文件；本服务只负责奖励与权益结算。

> 不要把积分服务地址写成短剧内容服务的 `:8000`。默认情况下，内容服务使用 **8000**，独立 PostgreSQL 积分服务使用 **8100**。

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

将交付的 `DramaFlow-PostgreSQL-Rewards-Service.zip` 解压到例如 `C:\DramaFlow\dramaflow_rewards_service`。用 PowerShell 进入目录并复制配置模板：

```powershell
cd C:\DramaFlow\dramaflow_rewards_service
Copy-Item .env.example .env
notepad .env
```

在 `.env` 中至少设置如下项目。请使用长随机密码；`POSTGRES_PASSWORD` 与未来的 `PANGLE_CALLBACK_SECRET` 都属于敏感值，不能提交 Git、不能发送到群聊、不能写入 Android 包。

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
CORS_ORIGINS=http://localhost
```

## 3. 启动 PostgreSQL 与 Rewards API

在同一 PowerShell 窗口执行：

```powershell
docker compose up -d --build
docker compose ps
docker compose logs -f rewards-api
```

初次启动会拉取 PostgreSQL 镜像、构建 API 镜像并创建名为 `rewards_postgres_data` 的 Docker 持久卷。PostgreSQL 官方镜像会在**首次初始化空数据目录**时执行挂载到 `/docker-entrypoint-initdb.d` 的 SQL 脚本；已有数据卷不会重复运行初始化脚本。[3]

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

配置成功后，进入 **奖励** 页面应能看到宝石余额、现金钱包、签到进度、每日宝箱、十轮领奖和皇冠之路。开发测试中，完整观看 Pangle 激励广告后，Android 会提交开发广告证明；数据库服务以幂等广告事件 ID 结算，重复回调不会重复加宝石。

| 验收路径 | 预期结果 |
|---|---|
| 第 1–3 集 | 免费播放，不触发集间激励广告记账。 |
| 第 4 集及以后 | 完整激励广告结束并关闭后，才记录集间通行并向内容服务申请解锁。 |
| 有效播放完成 | 服务端写入一轮有效观看；达到第 10 轮后显示待领取奖励。 |
| 十轮领奖 | 观看 `rv_claim_reward` 广告后结算基础宝石；随后可观看一次 `rv_double_reward` 翻倍。 |
| 每日签到 | 基础签到写入数据库；签到后可观看一次 `rv_signin_double` 获得翻倍。 |
| 每日宝箱 | 完成签到后可观看 `rv_chest_task` 广告领取一次。 |
| 插屏 | 第 4 集起按服务端冷却时间触发；广告全屏时原视频暂停，广告关闭后按进入广告前的播放状态恢复。 |

## 6. 日常运维与数据备份

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

## 7. 开发模式与生产切换

`ALLOW_DEBUG_AD_PROOF=true` 只用于当前 Android Debug 包和 Pangle 测试环境。正式上架前必须把它改为 `false`，配置 `PANGLE_CALLBACK_SECRET`，并将真实广告完成回调/验签接入服务端；否则客户端传入的开发证明不能作为生产奖励依据。

提现在当前版本仅提供**透明的钱包换算和资格预检**，不会伪造到账或自动打款。开放真实提现前，应独立补充身份校验、反作弊、支付/转账通道、人工审核、限额、争议处理、对账及法务合规流程。

## 8. 常见问题

| 现象 | 优先排查 |
|---|---|
| App 显示“积分服务尚未连接” | 在 App 的“我的”中确认填写了 `http://电脑IPv4:8100`，而非 `:8000`。 |
| 手机浏览器访问 `:8100/health` 失败 | Docker Desktop 是否运行、`docker compose ps` 是否健康、Windows 防火墙是否放行 Private 网络、手机和电脑是否同网。 |
| 端口 8100 被占用 | 修改 `.env` 的 `REWARDS_PORT`，例如 `18100`，执行 `docker compose up -d`，并在 App 填写同一新端口。 |
| PostgreSQL 初始化规则未更新 | SQL 初始化脚本只在空数据卷首次启动时执行。测试环境可在确认不保留数据后执行 `docker compose down -v` 再启动。 |
| 广告看完后没有宝石 | 确认 API 地址为 `:8100`、日志没有数据库错误、开发阶段 `ALLOW_DEBUG_AD_PROOF=true`。正式环境必须使用服务端广告验签。 |
| 宝石/现金钱包与预期不一致 | 查看 `docker compose logs -f rewards-api`；以 PostgreSQL 账本与 API 返回为准，不以客户端缓存为准。 |

## References

[1] [Docker Desktop for Windows 官方安装文档](https://docs.docker.com/desktop/setup/install/windows-install/)

[2] [Docker Compose 环境变量官方文档](https://docs.docker.com/compose/how-tos/environment-variables/set-environment-variables/)

[3] [PostgreSQL 官方 Docker 镜像文档](https://hub.docker.com/_/postgres)
