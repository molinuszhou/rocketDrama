# JoyeDrama 商业化 v3.0 实现说明

本版本将奖励、广告事件与钱包能力继续放在**独立 PostgreSQL 积分服务**中。局域网自有短剧内容服务仍只负责目录、播放会话、媒体文件与内容解锁；两项服务可以使用不同主机、端口和数据库独立部署。

## 数据库升级

新数据库启动时，Docker Compose 会按顺序执行：

1. `sql/001_init.sql`：基础账本、广告事件、签到、宝箱、皇冠和频控表；
2. `sql/002_monetization_v3.sql`：v3.0 欢迎奖励、市场钱包策略、三层钱包释放、领取步长与加速释放字段。

已运行旧版数据库时，**不要删除生产数据卷**。应在 PostgreSQL 中执行一次迁移：

```powershell
docker compose exec -T postgres psql -U $env:POSTGRES_USER -d $env:POSTGRES_DB -f /docker-entrypoint-initdb.d/002_monetization_v3.sql
```

如果未使用 Docker，请在安装目录执行：

```powershell
psql $env:DATABASE_URL -f .\sql\002_monetization_v3.sql
```

迁移使用 `ADD COLUMN IF NOT EXISTS` 与 `CREATE TABLE IF NOT EXISTS`，可安全用于已初始化的 v0.1.0 数据库。仍建议先对数据库做备份。

## v3.0 已实现规则

| 能力 | 服务端规则 | Android 展示 |
|---|---|---|
| 欢迎奖励 | 新用户初始化后创建一笔 `PENDING` 欢迎奖励；仅可领取一次并写入账本 | 首页和奖励中心显示“新人欢迎奖励”卡，领取后更新余额 |
| 首日签到 | 未签到用户的 `completed_streak=0`、`today_streak=1`；签到后才将第 1 天设为已完成 | 首日显示“今日 / 领”，不会提前显示对勾 |
| 攒币转轮 | 以当前生命周期领取步长、有效观看集数与待领奖状态计算进度 | 首页、发现、奖励、我的和播放器中始终提供悬浮入口；可领取时显示奖励金额 |
| 追剧领奖 | D0 每 1 集、D1–D3 每 2 集、D4+ 每 3 集形成一个领取轮次；奖励值按服务端曲线计算 | 奖励中心显示领取状态和下一轮提示 |
| 三层钱包 | L1 宝石；用户主动兑换后进入 L2 待释放余额；按业务日由服务器释放到 L3 可用余额 | 同时显示宝石、待释放余额、可用余额、预计剩余天数与每日加速额度 |
| 广告加速释放 | 完成激励广告后，受每日次数上限约束，将部分 L2 余额释放至 L3 | “广告加速”按钮显示今日已用/上限和每次最高释放额 |
| 皇冠之路 | 使用有效观看、签到、激励广告的真实数据库记录，在对应小时窗口内计算每个皇冠的进度 | 显示每项条件的当前/目标、百分比、奖励与点击后的完整规则 |

## 市场钱包策略

`geo_wallet_policy` 是数据库中的市场策略表。默认值为 `t3`，后续接入合规的国家/地区判定后，可在服务端创建用户时设置 `users.geo_tier`。客户端不能自行更改汇率、提现门槛、欢迎奖励或释放额度。

| 层级 | 宝石兑换比例 | 提现门槛 | 欢迎奖励 | 每日基础释放 |
|---|---:|---:|---:|---:|
| T1 | 250,000 宝石 / USD | USD 5.00 | 25,000 宝石 | USD 0.10 |
| T2 | 600,000 宝石 / USD | USD 2.50 | 60,000 宝石 | USD 0.05 |
| T3 | 1,500,000 宝石 / USD | USD 1.00 | 150,000 宝石 | USD 0.02 |

提现当前仍为资格预检，不会伪造支付成功。正式开放提现前，需要独立接入 KYC、支付通道、风控、申诉和后台审核工作流。

## 广告安全边界

开发环境可设置 `ALLOW_DEBUG_AD_PROOF=true` 来验证客户端联调。该开关只能用于测试；正式环境必须设为 `false` 并接入 Pangle 的服务端奖励验证，拒绝客户端自报奖励。

## 回归测试

在 PostgreSQL 已初始化且环境变量已配置的情况下运行：

```powershell
python -m pip install ".[test]"
python .\tests\rewards_postgres_smoke.py
python .\tests\http_api_smoke.py
```

`rewards_postgres_smoke.py` 覆盖欢迎奖励、首日签到、前三集免费有效观看、领取、主动兑换和服务器业务日释放。Android 构建只需执行 `./gradlew :app:compileDebugKotlin`；本轮按用户要求未生成或分发 APK。
