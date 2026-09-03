# JoyeDrama 动态奖励与轻量运营手册

## 适用范围

本手册对应当前 FastAPI 轻量配置服务和 JoyeDrama Debug 应用。服务端管理**有效剧集观看任务**、签到、广告解锁、广告翻倍、可选圆环、钱包展示和文案；Android 负责展示已下发的状态，并在 Pangle 激励广告完成后请求服务端结算。广告观看次数不会形成任务进度或独立领奖目标。

> 该实现用于**开发与试运营前联调**。它已提供规则版本、SQLite 事务、幂等键和不可变流水，但不是支付、提现或正式广告归因系统。生产环境必须补齐用户鉴权、数据库备份、Pangle 服务端回调/验签、HTTPS、审计与权限管理。

## 一、保存已验证的客户端配置

| 配置类别 | 保存位置 | 规则 |
|---|---|---|
| Pangle App ID、开屏/插屏/激励广告位 | Android `local.properties` 或 CI Secret | 保持当前已验证的 **Waterfall 直连**广告位；不与 Server Bidding 广告位混用。 |
| Dramaverse / VOD 参数与 License | Android 本地受控路径与 CI Secret | 不提交 License、security key 或 Firebase 文件。 |
| 动态奖励服务地址 | Android `local.properties` 的 `REWARDS_API_BASE_URL` | 模拟器为 `http://10.0.2.2:8000`；真机使用电脑局域网 IP；生产必须用 HTTPS 域名。 |
| 奖励规则 | 服务端 `runtime/reward_rules.json` | 可更新、可版本化；运行目录不可公开暴露。 |
| 权益账本 | 服务端 `runtime/reward_ledger.sqlite3` | 包含用户权益流水，必须备份、限制访问且不得提交 Git。 |
| 管理员令牌 | 仅服务端环境变量 `ADMIN_CONFIG_TOKEN` | 不写入 APK、Web 前端、日志、规则 JSON 或文档。 |

## 二、启动服务

### Docker Compose

```bash
cd dramaflow_content_server
cp .env.example .env
# 编辑 .env：至少设置一个不可预测的 ADMIN_CONFIG_TOKEN
export ADMIN_CONFIG_TOKEN='请替换为长随机值'
docker compose up --build
```

首次启动将创建 `runtime/reward_rules.json` 与 `runtime/reward_ledger.sqlite3`。如果希望从示例规则开始，可先复制：

```bash
mkdir -p runtime
cp runtime/reward_rules.example.json runtime/reward_rules.json
```

### 本地 Uvicorn

```bash
cd dramaflow_content_server
export ENVIRONMENT=development
export REWARD_RULES_PATH="$PWD/runtime/reward_rules.json"
export REWARD_LEDGER_PATH="$PWD/runtime/reward_ledger.sqlite3"
export ADMIN_CONFIG_TOKEN='请替换为长随机值'
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

Android 模拟器需使用 `http://10.0.2.2:8000`。真机和电脑必须在同一局域网，使用 `http://<电脑局域网IP>:8000`；仅限开发测试。正式服务必须使用公开 HTTPS 域名。

## 三、运营规则调整

### 安全读取当前规则

```bash
curl -sS http://127.0.0.1:8000/v1/admin/rewards/config \
  -H "Authorization: Bearer $ADMIN_CONFIG_TOKEN"
```

### 更新规则

1. 先读取并保存现有 JSON。
2. 修改需要调整的字段，例如 `unlock.free_episodes_default`、`unlock.daily_rewarded_unlock_limit`、**有效剧集观看任务**的 `target`/`reward_coins`、签到数组和用户文案。默认模板提供 1、3、5 集的阶梯观剧任务，关闭圆环；如需调整层级或开启圆环，应向用户明确说明有效观看计量方式。
3. **每次修改必须更改 `version`**，如由 `development-v1` 改为 `development-v2`。
4. 使用完整 JSON 替换规则；服务会校验字段范围、任务 ID 唯一性和类型。

```bash
curl -sS -X PUT http://127.0.0.1:8000/v1/admin/rewards/config \
  -H "Authorization: Bearer $ADMIN_CONFIG_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @/secure/path/reward_rules.v2.json
```

| 常用字段 | 可调行为 | 运营注意事项 |
|---|---|---|
| `feature_flags.*` | 开关签到、广告解锁、钱包和整体奖励。 | 关闭后客户端不应再开始新奖励流程。 |
| `unlock.*` | 免费集数、每次广告解锁范围、每日广告解锁次数、文案。 | 必须与内容授权、广告库存和用户可见说明一致。 |
| `tasks[]` | 仅配置有效剧集观看任务的集数目标、金币/现金展示奖励与可选广告翻倍。 | 当前服务拒绝广告次数、分钟数或解锁次数作为任务进度类型；任务 ID 一旦上线应尽量稳定。 |
| `checkin.*` | 连续签到奖励、广告翻倍开关。 | 奖励数组按连续天数依次使用，超出数组长度取最后一个值。 |
| `ring.*` | 可选的有效观看圆环奖励，默认关闭。 | 若启用，开发版采用客户端上报；生产应改为服务器可验证的播放事件。 |
| `wallet.*` | 钱包显示和资格提示文案。 | 当前只展示资格和账本；不得直接由此接口发起支付或资金转移。 |

## 四、广告权益结算流程

```text
用户主动点“广告解锁”或“广告翻倍”
          ↓
Pangle 激励广告完整观看并触发奖励回调
          ↓
Android 请求 /v1/rewards/ad-claims
          ↓
服务端验证广告证明，签发 5 分钟一次性 ad_token
          ↓
Android 请求 /v1/rewards/claims
          ↓
服务端事务检查规则/次数/令牌，写入账本并返回最新状态
```

在开发环境中，Android Debug 包会使用 `TEST_PANGLE_REWARD` 完成与服务端的接口联调。生产环境中该证明会被拒绝，必须替换为 Pangle 服务端回调或可验签的服务端凭据。**不能**在 `onUserEarnedReward` 中直接增加金币、现金或本地永久解锁。

## 五、真机验收步骤

1. 启动奖励服务，并在 Android `local.properties` 设置正确的 `REWARDS_API_BASE_URL`。
2. 安装最新 Debug APK。首次安装显示“开启奖励之旅”说明；选择“开启旅程”后才启用广告并优先尝试开屏广告，选择“暂不开启”则不请求广告。开屏与插屏继续使用已验证的 Waterfall 广告位。
3. 打开“任务”页，应看到连续签到奖励、服务端返回的 1/3/5 集观剧阶梯任务和仅作展示的活动奖励演示；圆环仅在服务端明确开启时显示。若显示“奖励服务尚未连接”，优先检查地址、局域网、防火墙和 cleartext 调试配置。
4. 完整播放一集；应用在接近结尾或播放完成时提交有效观看事件。刷新任务页后检查进度变化。
5. 在“选集”选择锁定集数并完整观看激励广告。服务端结算成功后，分集解锁会持久化；若奖励服务地址不可达、验证失败或结算失败，应用仍应提示“本次已解锁，奖励未到账”并允许当前播放会话继续，但重启后不应将该临时解锁伪装为永久权益。
6. 完成可领取任务后，分别测试“领取奖励”和“广告翻倍”。检查金币余额、任务状态和服务端流水。
7. 使用相同网络请求重复提交相同 `event_id` 或 `claim_id`，账本不应重复发放奖励。

## 六、故障排查

| 现象 | 优先检查 |
|---|---|
| 任务/钱包显示未连接 | `REWARDS_API_BASE_URL` 是否正确；模拟器使用 `10.0.2.2`，真机使用电脑局域网 IP；服务 `GET /health` 是否可达。 |
| 广告看完但未解锁 | Android 日志是否显示广告完成；`POST /v1/rewards/ad-claims` 是否成功；开发环境是否为 `ENVIRONMENT=development`；生产环境是否已接入真正的服务端验证。 |
| 规则更新不生效 | `version` 是否变化；PUT 是否使用正确 Bearer Token；查看 `GET /v1/admin/rewards/config` 的返回。 |
| 401 / 503 管理接口 | `Authorization: Bearer` 格式是否正确；服务端是否设置 `ADMIN_CONFIG_TOKEN`。 |
| 解锁/领取重复 | 服务端按 `event_id`、广告 request ID 和 `claim_id` 幂等；客户端不得自行随机重放已完成的领取请求。 |
| Pangle 40034 | 检查是否误将 Server Bidding 广告位用于直连 SDK 请求；当前客户端应使用已验证的 Waterfall 直连广告位。 |

## 七、上线前替换清单

在真实上线前，应将 SQLite 替换为有备份和访问控制的数据库，加入真实用户身份与设备风控，使用 HTTPS，接入 Pangle 服务端广告验证，基于可验证播放心跳计量有效观看，并建立管理员身份、角色、审计、规则审批和紧急回滚机制。账本、奖励资格与任何兑换/提现流程必须清晰、可解释、可审计，且与内容授权、广告政策和目标市场法规一致。
