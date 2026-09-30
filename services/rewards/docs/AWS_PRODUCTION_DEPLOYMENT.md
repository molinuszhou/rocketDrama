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
| `PANGLE_CALLBACK_SECRET` | 合作方提供的广告服务端回调验签密钥 |
| `ADMIN_DASHBOARD_TOKEN` | Rewards 后台管理接口访问令牌 |

保留以下默认值即可：

```properties
REWARDS_BIND_PORT=18100
CORS_ORIGINS=https://www.joyelleasia.com
TIMEZONE=UTC
```

`PANGLE_CALLBACK_SECRET` 只能放在服务器 `.env.production` 中。禁止放入 Android `local.properties`、APK、Git 仓库或客户端请求中。

### 3. 启动容器

先确认 `18100` 未被占用：

```bash
ss -ltnp | grep ':18100' || true
```

没有输出即可启动：

```bash
docker compose -p joyedrama-rewards \
  --env-file .env.production \
  -f docker-compose.production.yml \
  up -d --build
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

## 五、生产安全要求

生产环境会拒绝 `ALLOW_DEBUG_AD_PROOF=true`，并且缺少 `PANGLE_CALLBACK_SECRET` 时会拒绝启动。非自然量 B 面的现金相关激励奖励必须等待广告平台服务端可验证的完成结果，客户端广告回调本身不能直接发钱。

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
