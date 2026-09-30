# JoyeDrama Rewards 正式部署说明（宿主机原生 Nginx）

本文适用于正式 Android 包 **`com.feather.joyelledrama`**，以及已经由宿主机原生 Nginx 管理 HTTPS 证书的服务器。部署后仅新增 Rewards 服务，不会改动现有网站、Nginx 证书、`80/443` 端口或已有的 `127.0.0.1:3000` 服务。

> **不使用 Caddy。** 当前服务器已有 Nginx 和有效 HTTPS 证书，再启动 Caddy 会争抢 `80/443` 端口，没有必要且会增加风险。

## 一、最终结构

```text
公网请求
  │
https://www.joyelleasia.com:443
  │
宿主机原生 Nginx
  ├── /、/api、/images、/h5        → 127.0.0.1:3000（既有服务，不改动）
  └── /joyedrama-rewards/          → 127.0.0.1:18100（新增 Rewards API）
                                           │
                                      Docker 容器：FastAPI + PostgreSQL
```

Rewards API 和 PostgreSQL 都不会直接暴露到公网。Nginx 是唯一处理公网 HTTPS 的组件。

## 二、首次部署

### 1. 获取正式发布分支

建议新建独立目录，避免混入旧测试文件：

```bash
cd /home/ubuntu
mkdir -p apps
cd apps
git clone --depth 1 --branch release/formal-production --single-branch \
  https://github.com/molinuszhou/JoyeDrama.git joyedrama-release
cd joyedrama-release/services/rewards
```

### 2. 配置环境变量

```bash
cp .env.production.example .env.production
chmod 600 .env.production
nano .env.production
```

必须替换以下三项：

| 配置项 | 用途 |
|---|---|
| `POSTGRES_PASSWORD` | PostgreSQL 数据库密码 |
| `ADMIN_DASHBOARD_TOKEN` | Rewards 后台管理接口访问令牌 |

保留以下默认值即可：

```properties
REWARDS_BIND_PORT=18100
CORS_ORIGINS=https://www.joyelleasia.com
TIMEZONE=UTC
```

### 3. 启动容器

先确认 `18100` 未被占用：

```bash
ss -ltnp | grep ':18100' || true
```

没有输出即可启动：

先检查服务器使用的是哪种 Compose 命令：

```bash
docker compose version || true
docker-compose --version || true
```

**Docker Compose v2（推荐）**使用：

```bash
docker compose -p joyedrama-rewards \
  --env-file .env.production \
  -f docker-compose.production.yml \
  up -d --build
```

**旧版 Docker Compose v1**（服务器存在 `docker-compose`、但没有 `docker compose`）使用下方命令。注意它是带连字符的一个命令，不能写成 `docker compose`：

```bash
docker-compose -p joyedrama-rewards \
  --env-file .env.production \
  -f docker-compose.production.yml \
  up -d --build
```

如果旧版 `docker-compose` 不支持 `--env-file`，先临时将环境变量导入当前终端再执行：

```bash
set -a
. ./.env.production
set +a
docker-compose -p joyedrama-rewards -f docker-compose.production.yml up -d --build
```

`-p joyedrama-rewards` 会隔离该项目的容器名、Docker 网络和 PostgreSQL 数据卷，不与服务器上其他 Compose 项目混用。

检查本机健康状态：

```bash
docker compose -p joyedrama-rewards \
  --env-file .env.production \
  -f docker-compose.production.yml ps

curl -fsS http://127.0.0.1:18100/health
```

如 `18100` 已被其他程序占用，请改 `.env.production` 中的 `REWARDS_BIND_PORT`，并在下一节的 Nginx 配置中改成相同端口。

## 三、修改现有 Nginx

你现有的 `nginx.conf` 中，`www.joyelleasia.com` 的 HTTPS `server {}` 从约第 74 行开始，结束大括号约在第 130 行。

将 [`deploy/nginx/joyedrama-rewards.location.conf`](../deploy/nginx/joyedrama-rewards.location.conf) 的内容粘贴到这个 `server {}` 内，建议放在既有 `/api|images|h5` 的 `location` 块之后、该 `server` 的结束 `}` 之前。

新增的路径仅为：

```text
/joyedrama-rewards/
```

它不会覆盖或改变原有的 `/`、`/api`、`/images`、`/h5` 路由。

配置完成后验证并平滑重载 Nginx：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

若服务器未通过 systemd 管理 Nginx，则使用：

```bash
nginx -t
nginx -s reload
```

验证公网 HTTPS 路径：

```bash
curl -fsS https://www.joyelleasia.com/joyedrama-rewards/health
```

## 四、Android 正式包配置

在签名电脑的 `android/local.properties` 或 CI Secret 中配置：

```properties
REWARDS_API_BASE_URL=https://www.joyelleasia.com/joyedrama-rewards
TOPON_DEMO_MODE=false
EXTERNAL_REPORTING_ENABLED=true
RELEASE_AB_ROUTING_ENABLED=true
TOPON_REWARDED_LOAD_TIMEOUT_MS=10000
```

正式 Release 固定使用 Pangle/PSSDK 短剧内容，不请求旧的本地 Content 服务；正式包名为 `com.feather.joyelledrama`。

### 用户归因保存

Rewards 服务从 Android 的 AppsFlyer 转化回调接收最小归因记录，并绑定至匿名 `user_id`：`af_status`、媒体来源、campaign、channel、AppsFlyer UID、服务端派生的 A/B 面，以及首次/最近同步时间。不会保存点击 ID、site ID 或完整原始回传载荷。

- `af_status=Non-organic` 时服务端派生并保存 B 面；其他有效状态保存 A 面。
- 回调同步失败时，客户端会在下一次 `/v2/bootstrap` 补发当前最小归因记录。
- 新增的 `036_v1707_user_attribution.sql` 由服务启动时自动迁移；既有数据库无需重建。

### 运营后台查询范围

控制台地址为：

```text
https://www.joyelleasia.com/joyedrama-rewards/admin
```

控制台在这个反向代理子路径下会自动把 API 请求发送至 `/joyedrama-rewards/v2/admin/...`，不会错误请求主站的 `/v2/...`。

- **用户与归因**：查询匿名用户 ID、市场、钱包、观看/广告参与计数，以及最小 AppsFlyer 归因记录和服务端推导的 A/B 面；不提供点击 ID、site ID 或原始归因载荷。
- **广告运营**：查询 TopOn 回调写入的原始广告运营事件（保留 90 天）和长期日汇总，含广告位、ADN、展示、paid-impression 和客户端 eCPM 估算。
- **上报记录**：查询 AppsFlyer、Firebase、炫羽的客户端投递状态镜像（保留 90 天）。这是客户端 SDK/HTTP 回调观察结果，不取代三个渠道后台的最终接收或结算数据。

新增的 `037_v1708_reporting_delivery_events.sql` 同样由服务启动时自动迁移；更新容器后无需重建数据库。

## 五、生产安全要求

生产环境会拒绝 `ALLOW_DEBUG_AD_PROOF=true`。激励广告成功后由客户端调用 Rewards 服务；服务端按 `ad_event_id` 记录广告奖励账本并做幂等去重，客户端重复请求不会重复入账。

请仅开放服务器防火墙的既有 `80/443`。不要开放 PostgreSQL `5432`，也不要开放 Rewards API 内部端口 `18100`。

## 六、常用维护命令

```bash
# 查看状态
docker compose -p joyedrama-rewards \
  --env-file .env.production \
  -f docker-compose.production.yml ps

# 查看 Rewards API 日志
docker compose -p joyedrama-rewards \
  --env-file .env.production \
  -f docker-compose.production.yml logs -f rewards-api

# 更新代码并重建服务
cd /home/ubuntu/apps/joyedrama-release
git pull --ff-only origin release/formal-production
cd services/rewards
docker compose -p joyedrama-rewards \
  --env-file .env.production \
  -f docker-compose.production.yml up -d --build
```

升级前请备份 PostgreSQL 数据卷；不要使用 `docker compose down -v`，该命令会删除数据库卷。

### 已确认可清空 Rewards 账本时的全新重建

仅当确认**无需保留既有 Rewards 用户、钱包、广告事件和运营审计数据**时，才可使用下面流程。它只会删除 `-p joyedrama-rewards` 这个 Compose 项目的 PostgreSQL 数据卷，不会改动宿主机 Nginx、证书或其他 Compose 项目。

```bash
cd /home/ubuntu/apps/joyedrama-release
git pull --ff-only origin release/formal-production
cd services/rewards

# 破坏性操作：删除 joyedrama-rewards 的 PostgreSQL 数据卷。
docker compose -p joyedrama-rewards \
  --env-file .env.production \
  -f docker-compose.production.yml down -v

# 首次创建空卷时执行 001_init.sql；Rewards API 启动后会依次补跑 002–037 迁移。
docker compose -p joyedrama-rewards \
  --env-file .env.production \
  -f docker-compose.production.yml up -d --build

curl -fsS http://127.0.0.1:18100/health
curl -fsS https://www.joyelleasia.com/joyedrama-rewards/health
```

若服务器使用旧版 Compose v1，请将每条 `docker compose` 替换为 `docker-compose`；如其不支持 `--env-file`，按本文件第二节先 `set -a; . ./.env.production; set +a` 后执行。
