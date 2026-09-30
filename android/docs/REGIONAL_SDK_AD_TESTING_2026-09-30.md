# Pangle / TopOn 区域网络核验

**核验日期：** 2026-09-30
**用途：** JoyeDrama 正式生产分支的短剧内容与广告联调排障。

## 官方可确认事项

1. [Pangle 官方支持地区页](https://www.pangleglobal.com/knowledge/regions-Pangle-supports) 列出 **台湾** 与 **印度尼西亚** 为其亚洲支持地区；该页的列表中**未列出香港**。
2. [TopOn Pangle 集成说明](https://help.toponad.net/docs/Pangle-integration-instructions) 明确要求：非中国大陆 Android 集成需在 TopOn SDK 下载工具选择 `Is it in China? = No` 并使用 **Pangle SDK**；中国大陆使用的是 **CSJ** 平台/网络。TopOn 中应使用 Pangle 平台创建的 App ID 和对应广告位 Slot ID 配置 Pangle 网络广告源。
3. Pangle 支持地区仅说明可服务市场，**不等同于某一测试设备、VPN 出口或广告位实时有库存**。激励视频无填充仍可能来自广告位配置、TopOn 分层、广告源竞价、包名/签名、GDPR/隐私同意、设备广告标识、VPN/代理信誉或实时库存。
4. 当前公开资料未给出 PSSDK（短剧内容 SDK）在香港、台湾、印尼按 IP 的单独可用性承诺；因此不能把 Pangle 广告网络地区名单直接推断为 PSSDK 内容服务保证。

## 建议的正式联调顺序

1. **避免将 VPN 当作正式地区验收环境。** 使用对应市场的真实 SIM/物理设备或经供应商批准的云真机；保留出口 IP、国家、App 版本、包名、广告位 ID 与 TopOn error code。
2. 在 TopOn 后台确认正式 App ID、正式 Rewarded/Interstitial Slot ID、Pangle network、Bidding/Waterfall 广告源、eCPM、地域定向和启用状态相互一致；确认 Android 包使用的是海外 Pangle adapter，而非中国大陆 CSJ 版本。
3. 对广告填充和 PSSDK 内容分别进行探针测试：先只初始化并请求一个广告位，再只初始化并请求 PSSDK 热门目录，避免将两条网络链路混在同一个失败结论中。
4. 激励广告库存不足时，在 TopOn 增加已获准的备用广告网络/广告源；代码只应展示已加载成功的广告，并在有限次数失败后恢复播放，不应反复阻塞观看。
5. 将每次失败的 TopOn `errorCode/fullErrorInfo`、实际返回的广告网络、PSSDK `ErrorInfo.code/msg` 交给 Pangle/TopOn 技术支持核查账号、广告位和区域许可。不要在客户端试图通过 VPN 或 IP 伪装绕过区域/库存限制。

## 当前客户端保护措施

- 激励与插屏在网络加载阶段不暂停正在播放的短剧；只有广告素材已加载并准备展示时才暂停。
- 剧中插屏在同一观看会话内连续不可用 **2 次** 后自动停止继续请求，观看可持续进行；锁集解锁仍保持“广告成功后才解锁”的产品规则。
