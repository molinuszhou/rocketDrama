# TopOn 服务端激励官方接入要点（内部实施参考）

来源：

- 中文官方文档：https://help.toponad.net/cn/docs/msbnkj
- 英文官方文档：https://help.toponad.net/docs/Serverside-incentives
- 获取时间：2026-09-30

## TopOn 官方流程

1. 在 TopOn 后台的**激励视频广告位**开启「服务端激励」，并配置开发者回调 URL。
2. Android 在**加载激励广告之前**，通过 TopOn SDK 的 `setLocalExtra` 传入：
   - `ATAdConst.KEY.USER_ID`：用户唯一标识；
   - `ATAdConst.KEY.USER_CUSTOM_DATA`：业务关联数据。
3. 用户完成激励广告后，TopOn 以 **GET** 请求开发者回调 URL。核心回调参数包括：`user_id`、`trans_id`、`reward_amount`、`reward_name`、`placement_id`、`extra_data`、`network_firm_id`、`adsource_id`、`scenario_id`、`sign`，可选 `ilrd`。
4. 服务端必须以 `trans_id` 做幂等去重后才结算业务奖励。
5. TopOn 服务器回调必须在 2 秒内返回；无响应时最多重试 5 次。结果状态码：
   - `200`：处理成功；
   - `601`：签名验证失败；
   - `602`：其他处理失败。

## 官方签名规则

无 `ilrd`：

```text
MD5("trans_id=" + trans_id + "&placement_id=" + placement_id + "&adsource_id=" + adsource_id + "&reward_amount=" + reward_amount + "&reward_name=" + reward_name + "&sec_key=" + sec_key)
```

有 `ilrd`：

```text
MD5("trans_id=" + trans_id + "&placement_id=" + placement_id + "&adsource_id=" + adsource_id + "&reward_amount=" + reward_amount + "&reward_name=" + reward_name + "&sec_key=" + sec_key + "&ilrd=" + ilrd)
```

## 项目结论

- Pangle/PSSDK 在正式项目中仅用于短剧内容目录和播放器，**不用于 TopOn 广告奖励验签**。
- 正确的服务器配置名应为 `TOPON_REWARDED_S2S_SECRET`，值对应 TopOn 服务端激励规则中的 `sec_key`。
- 客户端绝不可携带或回传该密钥；服务端应通过 TopOn 的 S2S 回调签名验证和 `trans_id` 幂等记录后结算奖励。
- 对 TopOn 聚合的第三方 ADN，TopOn 官方建议优先使用各 ADN 自己的服务端激励；TopOn Adx、直投及交叉推广可使用 TopOn S2S 回调（Network Firm ID 分别为 66、67、35）。

## 上线配置清单

1. 在正式 TopOn **激励广告位**开启服务端激励，并将 callback URL 配为 Rewards 服务的 `/v2/ads/topon/rewarded/callback`。
2. 将该广告位的 `sec_key` 写入服务端 `TOPON_REWARDED_S2S_SECRET`，并将广告位 ID 写入 `TOPON_REWARDED_PLACEMENT_ID`。
3. Release APK 会在加载广告前传入 `USER_ID` 与一次性 `USER_CUSTOM_DATA`；不要在 TopOn 后台覆盖这两个字段。
4. 对所有计划参与现金/权益奖励的瀑布流广告源，确认其 S2S 回调路径与本服务兼容；没有服务端激励回调的来源不得用于该正式激励广告位。
