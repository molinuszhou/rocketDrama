# Rewards API 与管理后台拆分部署

生产部署将 Rewards 拆为两个独立容器：`rewards-api` 只处理 Android 客户端的 `/v2/` 业务接口，`rewards-admin` 只处理 `/admin` 和 `/v2/admin/*`。两个服务使用同一个 PostgreSQL 数据库和相同的受控环境变量，但独立监听本机回环端口。因此，仅修改 `admin_dashboard.py` 或后台管理路由时，可以只重建 `rewards-admin`，不会重启或替换正在服务客户端请求的 `rewards-api` 容器。运行时数据库迁移也只由 `rewards-api` 执行，避免后台 UI 的单独更新触及共享 schema。

## 首次切换到双服务

在服务器的 `services/rewards` 目录中，先复制或补充 `.env.production`。保留现有数据库、后台令牌、会话签名密钥、时区和 GeoIP 配置，并新增：

```dotenv
REWARDS_BIND_PORT=18100
REWARDS_ADMIN_BIND_PORT=18101
REWARDS_DATABASE_URL=
```

空的 `REWARDS_DATABASE_URL` 会继续使用当前 Compose 管理的 `postgres` 容器。随后将 `deploy/nginx/joyedrama-rewards-split.conf.example` 中的三个 location 合并到现有 HTTPS `server` 块。它保留原有 `/joyedrama-rewards/` 的 Android API 地址，并把管理 UI 和管理 API 分流到端口 `18101`。

验证 Nginx 配置后再 reload：

```bash
nginx -t && nginx -s reload
```

启动或更新两项服务：

```bash
cd /srv/JoyeDrama/services/rewards

docker-compose --env-file .env.production -f docker-compose.production.yml \
  up -d --build rewards-api rewards-admin
```

确认两个容器均为 healthy，并分别检查：

```bash
curl --fail http://127.0.0.1:18100/health
curl --fail http://127.0.0.1:18101/health
```

`/health` 中的 `sensitive_telemetry.raw_audit_enabled` 为 `true` 时，代表 `TELEMETRY_ENCRYPTION_KEY` 已有效加载；它不会回显密钥或原始审计内容。

## 仅更新后台

后台页面或 `/v2/admin/*` 路由修改后，使用下面命令即可。它只构建和替换 `rewards-admin`；`rewards-api` 保持运行：

```bash
cd /srv/JoyeDrama/services/rewards

docker-compose --env-file .env.production -f docker-compose.production.yml \
  up -d --build --no-deps rewards-admin
```

不要执行 `down`。`down` 会停止两个服务，也会使容器标准输出日志不再保留在当前容器实例中。若一次改动包含 SQL migration，先使用“启动或更新两项服务”的命令更新 `rewards-api`，再单独更新后台；不要把 migration 和仅后台更新混为同一条命令。

## 迁移到 AWS RDS PostgreSQL 的建议路径

不要在本次双服务切换同时迁移数据。先稳定运行双服务和行为采集，然后在独立维护窗口完成数据库迁移。RDS 应部署在和应用服务器网络延迟最低的区域，并且只接受应用安全组的 TLS 连接。生产阶段至少启用自动备份、删除保护和监控；收入结算、积分账本和原始审计成为关键业务后，再评估 Multi-AZ。

迁移窗口的顺序应为：停止新写入或短暂维护、从当前 PostgreSQL 执行一致性 `pg_dump`、导入 RDS、核验用户/账本/迁移版本行数、在 `.env.production` 设置 `REWARDS_DATABASE_URL` 为 RDS TLS URL、仅启动 `rewards-api` 和 `rewards-admin`、确认健康检查和真实登录/客户端 bootstrap。旧 Docker PostgreSQL 卷至少保留到 RDS 备份与恢复演练通过后再决定下线。

本仓库已允许使用 `REWARDS_DATABASE_URL` 覆盖 Compose 内部数据库连接；这只是迁移准备，不会自动创建、转移或删除任何数据库。

## Redis 的当前结论

当前阶段不应把 Redis 放到积分余额、解锁权益、广告结算或行为原始记录的事实来源中。PostgreSQL 的事务和唯一约束仍是这些数据的唯一权威。Redis 在后续出现明确指标时再加入：多个 API 副本的限流计数、短期 session/防重放、低频且可容忍短时陈旧的管理统计缓存，或一个独立后台 worker 的队列协调。加入时使用 cache-aside 与明确 TTL，缓存未命中必须回源 PostgreSQL，且绝不能把尚未持久化的奖励结算仅放进 Redis。
