# JoyeDrama 生产代码审计 2.0

**审计日期：** 2026-10-09
**审计基线：** `release/formal-production`，审计开始时为 `97545b1`
**范围：** Android Release 源码、Rewards FastAPI/PostgreSQL、Docker Compose、Nginx 子路径代理、GeoIP、管理后台、广告/PSSDK 与上报链路。

> 本报告区分“已在本轮修复”与“需要第三方平台或产品决策才能真正关闭”的风险。没有把客户端可伪造的广告回调误称为已解决的反作弊方案。

## 结论

当前版本可以继续用于**内部/伙伴测试**；在完成下面“发布前必须关闭”的两项前，不建议把奖励/提现作为可实际兑付的生产功能开放：

1. **激励、观看、插屏回退仍由客户端事件触发结算。** 会话令牌可防止伪造其他用户 UID，但不能证明用户本人真的完成了广告或观看。必须接入广告平台/聚合平台的可验签 S2S/SSV 回调（或经验证的 Play Integrity + 一次性服务端授权）才能把它变成可信现金结算证据。
2. **会话强制开关需要随新版 Android 灰度。** 本轮已实现新协议和服务端开关；为了兼容已上线老 APK，首次后端部署仍默认关闭。新版 APK 覆盖并回归后，才可设为 `REWARDS_REQUIRE_USER_SESSION=true`。

## 本轮已修复并提交前验证的事项

| 级别 | 修复项 | 处理结果 |
|---|---|---|
| P0 | 管理后台“上报记录”查询 SQL 把 `{where}` 当普通字符串 | 改为 f-string，日期、渠道、事件、状态、UID、广告位筛选和分页能够正确拼接参数化条件。 |
| P0 | 提现重复申请可占用同一余额 | 新增 `045_v1716_withdrawal_reservations.sql`：同一用户请求使用行锁，接受申请即原子扣减/预留 `cash_wallet_micros`；新增 `client_request_id` 唯一索引、币种/市场快照字段。 |
| P0 | 提现请求重放 | Android 每次请求携带 `client_request_id`；相同键重试返回原申请，不再次扣款。 |
| P1 | 账本 reference 幂等键跨用户冲突 | 新增 `046_v1717_user_scoped_ledger_idempotency.sql`，唯一约束改为 `(user_id, reference_type, reference_id)`。 |
| P1 | 并发观看可重复累加任务进度 | 在同一用户行锁内判定 event、奖励与剧集是否已观看，重复观看同一 `drama_id + episode_id` 不再推进任务。 |
| P1 | 金额展示/阻尼使用二进制浮点 | 钱包及美元显示改用 `Decimal` + 明确的 `ROUND_HALF_UP`；阻尼结算改为 `Decimal` 向下取整。 |
| P1 | 多实例 API 同时启动可能竞争数据库迁移 | 运行时迁移加入 PostgreSQL session advisory lock，直到完整迁移序列结束才释放。 |
| P1 | 无效会话在 FastAPI middleware 变成 HTTP 500 | 会话校验异常现在返回标准 JSON `401`/`1904`，不会再成为内部错误。 |
| P1 | 广告运营镜像未复用 Rewards 会话 | 新版客户端有会话时会携带 `X-Rewards-Session` + Bearer；首次 Bootstrap 前不强行带协议头，以兼容灰度。 |
| P1 | PSSDK 广告收入在 SHOWN 而非 paid 回调写入，且可能重复 | PSSDK `RevenueInfo` 改为只在 TopOn `onAdRevenuePaid` 写入，并以 `AtomicBoolean` 保证单广告对象仅处理一次 paid 回调。 |
| P1 | 全屏广告 onStop 覆盖 PSSDK 恢复标志 | 全屏广告已暂停时保留原恢复意图，广告关闭后可正确续播。 |
| P1 | 后台市场策略中货币符号可进入 HTML 属性 | 动态市场/皇冠字段统一使用 `esc()` 或数字转换；管理后台页面新增 CSP、`nosniff`、`no-referrer` 响应头。 |
| P1 | 匿名 UID 与会话被云备份/设备迁移复制 | 将包含匿名安装 ID 与 Rewards 会话的 SharedPreferences 排除在 Android cloud backup/device transfer 之外。 |

## GeoIP：问题判断与本轮修复

### 已确认的代码/部署链路

- 服务端只会在 `/v2/bootstrap` 成功时写入 `users.geo_country`，并且只补写空字段；不会保存原始 IP。
- Compose 已将宿主机 `./geoip/GeoLite2-Country.mmdb` 以只读方式挂载到容器 `/usr/share/GeoIP/GeoLite2-Country.mmdb`。
- Python 依赖 `geoip2>=5.0,<6.0` 已在 `pyproject.toml` 声明，生产 Docker 镜像会通过 `pip install .` 安装。
- 沙盒没有你的 MMDB 文件，也未安装项目依赖，因此不能代替 AWS 容器做真实国家库读取验证；Docker CLI 在沙盒不可用。

### 为什么“服务器文件存在”仍可能是空值

按概率排序：

1. **生产容器仍是旧镜像/未重建**，因此没有 GeoIP 功能或健康诊断代码；
2. **MMDB 只在宿主机存在，未实际挂载进运行中的 `rewards-api` 容器**；
3. **Nginx 前还有 CDN/LB，API 收到的是代理私网 IP 或 CDN IP**，`GeoCountryResolver` 会拒绝私网/保留地址；
4. 当前 Nginx 片段没有随着本次代码更新，或真实客户端请求尚未再次触发新版 `/v2/bootstrap`；
5. 数据库已有用户字段为空，但测试过程没有成功执行 Bootstrap（例如钱包同步失败）。

### 本轮变化

- `/health` 增加安全的 `geoip` 状态：`database_path_configured`、`database_exists`、`geoip_library_available`、`reader_ready`；不公开 IP 或内部文件路径。
- 解析顺序改为取 Nginx 清洗后的 `X-Forwarded-For` 第一个地址；不再错误取代理链最后一个地址。
- Nginx 片段覆盖外部可伪造的 `X-Real-IP`/`X-Forwarded-For`，统一转发 `$remote_addr`。
- GeoIP 解析错误记录错误类型但不记录原始 IP。

### AWS 部署后的核验命令

在 `~/joyedrama/services/rewards` 执行：

```bash
# 1. 拉取本次代码并重建 API；使用服务器实际支持的 compose 命令。
git pull origin release/formal-production

docker compose -p joyedrama-rewards \
  --env-file .env.production \
  -f docker-compose.production.yml up -d --build

# 2. 查看容器内的安全诊断；前三项必须为 true。
curl -fsS http://127.0.0.1:18100/health

# 3. 验证 MMDB 在容器内存在且可读取。预期打印 US。
docker compose -p joyedrama-rewards --env-file .env.production \
  -f docker-compose.production.yml exec rewards-api \
  python -c "from app.geo import GeoCountryResolver; r=GeoCountryResolver('/usr/share/GeoIP/GeoLite2-Country.mmdb'); print(r.country_for_ip('8.8.8.8'))"

# 4. 查看服务启动和 GeoIP 读取错误；日志不会输出用户 IP。
docker compose -p joyedrama-rewards --env-file .env.production \
  -f docker-compose.production.yml logs --tail=150 rewards-api

# 5. 同一测试用户重新打开新版 APK 后，检查是否已写入国家。
docker compose -p joyedrama-rewards --env-file .env.production \
  -f docker-compose.production.yml exec postgres sh -lc \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT user_id, geo_country, created_at, updated_at FROM users ORDER BY updated_at DESC LIMIT 20;"'
```

如果命令 3 返回 `US`、但真实用户仍为空，应检查 `www.joyelleasia.com` 前是否有 CDN/LB。此时必须在 Nginx 的 **`http {}`** 块按对应供应商官方 CIDR 配置 `real_ip_header`、`set_real_ip_from`、`real_ip_recursive on`；不能直接信任公网请求传入的 `X-Forwarded-For`。

## 会话强制部署顺序

`.env.production` 新增：

```properties
REWARDS_REQUIRE_USER_SESSION=false
```

1. 先部署后端，保持 `false`；老 APK 不会被突然阻断，新 APK 已会携带签名会话。
2. 用新版 APK 回归：钱包同步、签到、看剧、奖励、广告镜像、提现账户、提现申请。
3. 新版 APK 覆盖并确认后，将该值改为 `true`，再执行 Compose `up -d --build`。
4. 开启后，任何用户状态写请求若没有有效 Bearer 会话都会返回 `401/1904`。

> 会话令牌防的是“伪造/猜测其他匿名 UID 调接口”，不是广告完成的服务端真实性证明。

## 仍需产品或第三方配置才能关闭的风险

| 优先级 | 风险 | 为什么不能靠本次代码单独解决 | 建议 |
|---|---|---|---|
| 发布前 P0 | 客户端可伪造 `rewarded complete`、观看时长、插屏回退事件 | Android 客户端的 UUID、proof、event 和时间戳都能被篡改；HMAC 会话无法证明广告已看完。 | 向 TopOn/MAX/广告网络确认 S2S reward callback 或 SSV 的签名、transaction ID、custom-data/user 映射方案；服务端验证成功后才记账。若无法获得 SSV，现金提现功能应保持人工审核/关闭。 |
| 发布前 P0 | 旧 APK 兼容期仍允许无会话请求 | 为避免已安装用户立刻失效，后端默认灰度兼容。 | 完成新版覆盖后按上节启用 `REWARDS_REQUIRE_USER_SESSION=true`。 |
| P1 | 旧 `ADMIN_DASHBOARD_TOKEN` 仍是兼容后门 | 现有生产环境可能尚未用账号密码首次登录；直接删除会造成后台锁定。 | 在 `.env.production` 设置 `ADMIN_INITIAL_USERNAME` / `ADMIN_INITIAL_PASSWORD`，确认可通过账号密码登录后，轮换并移除 `ADMIN_DASHBOARD_TOKEN`；下一轮可删除兼容分支并加入 RBAC、限速和可撤销管理员 session。 |
| P1 | 炫羽正式端点仍是 HTTP GET | 明文通道会暴露和允许篡改 UID/广告元数据；客户端无法把 HTTP 变成 HTTPS。 | 向炫羽索取 HTTPS POST 接口后改造；若对方没有 HTTPS，正式环境应评估是否禁用该投递。 |
| P1 | 广告隐私同意不是完整 CMP/UMP | 目前产品按“用户主动点广告后允许广告”执行，符合既有产品决定，但不是地区目的级 CMP。 | 若投放 GDPR/严格隐私地区，接入 UMP/CMP，并在 AppsFlyer/PSSDK/TopOn 初始化前依据目的级授权门控。 |
| P1 | 管理员会话仍存于浏览器 sessionStorage，用户会话仍是普通本地偏好设置 | 本轮已缓解 XSS 和备份复制，但还不是 Keystore/可撤销 refresh-token 体系。 | 下一安全迭代使用 Android Keystore 加密会话存储；服务端为用户/管理员引入可撤销 session ID、轮换和登录限速。 |
| P1 | 区域货币策略可原地修改 | 存量余额仍会按当前策略显示；虽然提现申请新增了币种/市场快照，既有账本没有完整 policy version。 | 对有资产用户禁止原地改币种/精度；创建新版策略并显式迁移或只分配给新用户。 |

## 验证记录

- `python3 -m compileall -q services/rewards/app`：通过；
- 迁移清单与 SQL 文件一一匹配：**45** 个增量迁移；
- Decimal 边界验证：`2,675,000 micros -> 2.68`、`1,005,000 micros -> 1.01`、IDR 零小数位正确；
- Compose YAML、回环端口、GeoIP 只读挂载与会话开关验证：通过；
- 管理后台 JavaScript `node --check`、动态字段转义断言：通过；
- `./gradlew :app:compileDebugKotlin --no-daemon`：通过，未生成 APK；
- `./gradlew :app:testDebugUnitTest --no-daemon`：通过，未生成 APK；
- Release 编译未执行：正式 `REWARDS_API_BASE_URL`、Firebase `google-services.json` 等受保护配置不在该沙盒，且本次遵守“不生成 APK”要求。

## 本轮新增迁移

| 迁移 | 用途 |
|---|---|
| `045_v1716_withdrawal_reservations.sql` | 提现请求幂等、余额预留、币种/市场快照与活动申请索引。 |
| `046_v1717_user_scoped_ledger_idempotency.sql` | 账本幂等键改为按用户作用域唯一。 |
