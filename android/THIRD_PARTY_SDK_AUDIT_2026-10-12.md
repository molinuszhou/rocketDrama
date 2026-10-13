# 第三方 SDK 接入整改与审计

**审计日期：** 2026-10-12
**适用客户端：** JoyeDrama Android `com.feather.joyelledrama`
**代码基线：** `release/formal-production`，版本 `1.7.2.1 (88)`
**本次不包含：** APK/AAB 编译、签名或服务器部署。

## 已完成的代码整改

### TopOn：广告场景、Ready 判断与展示

TopOn 的广告场景接口已按官方顺序接入：

```text
进入业务广告场景
→ entryAdScenario(placementId, scenarioId)
→ isAdReady()
→ show(..., ATShowConfig.scenarioId(scenarioId))
```

激励广告按 TopOn 官方示例执行：用户点击激励入口时先记录场景到达、检查 `isAdReady()`；未 Ready 时对同一广告对象执行 `load()`，仅当加载失败或客户端加载超时后，才向既有业务层返回失败并尝试一次受事务保护的插屏回退。

- **Ready：** 直接调用 `show()`。
- **Not Ready：** 先调用 `load()`；`onRewardedVideoAdLoaded()` 后再次检查 Ready 并展示。
- **Load failed / timeout：** 才进入一次插屏回退；不会恢复“激励失败 → 插屏 → 恢复 → 重复激励”的栈溢出问题。

插屏现在透传真实业务 `logicalPlacement`，不再全部压缩为 `int_playback_action`，以确保客户端、Rewards 账本和广告审计的业务入口一致。

开屏广告也已经使用场景 ID 和 `ATShowConfig` 展示；加载超时由 5 秒调整为 TopOn 建议区间的下限 **6 秒**，兼顾冷启动填充率和启动速度。

### TopOn：收入字段

TopOn 的 `onAdRevenuePaid()` 已改为以 `ATAdInfo.publisherRevenue` 作为单次展示收入，而非自行按 eCPM 除以 1,000 推算。eCPM 仍会保留在本地审计记录和炫羽协议参数中；`publisherRevenue` 用于 Firebase、AppsFlyer 和 PSSDK 的实际展示收入回传。

### PSSDK：播放服务可用性门控

PSSDK 目录加载和播放器打开已分离：目录仍可浏览，但每次创建 PSSDK 的详情播放器 Fragment 前都会调用 `checkPlayStatus()`。

- 返回 `SUPPORT`：正常创建播放器。
- 返回 `NOT_SUPPORT`、`NETWORK_ERROR` 或未知状态：不创建空播放器，显示项目现有的可重试错误界面。
- 播放失败后的“重试”也会先复核服务状态。

PSSDK IAA 回传增加了 SDK 已启用且已初始化的保护，避免在短剧 SDK 不可用时调用 `reportRevenueInfo()`。

### AppsFlyer：初始化、归因与广告收入

AppsFlyer 已完成以下整改：

- `start()` 使用 `AppsFlyerRequestListener`，本地审计能区分“已发起”与启动请求返回 HTTP 200；不再把启动调用本身直接写成成功。
- 移除了 Release 普通日志中的 AppsFlyer UID，避免安装标识暴露在生产 Logcat。
- 已确认 `Non-organic` 的 B 面后，后续空归因、缺字段或 Organic 回调不会把用户降级回 A 面。
- SDK `6.17.5` 的每次真实展示收入新增调用官方 `AppsFlyerLib.logAdRevenue(AFAdRevenueData, additionalParameters)`，中介类型为 `TOPON`。
- 现有合作方要求的 `ad_impression` 与 `ad_impression_001` 自定义事件继续保留，不改变既有外部核对口径；只有 USD 才参与原有美元累计阈值，非 USD 不会伪造汇率，但仍会通过官方 IAA API 上报原始 ISO 货币与实际展示收入。

### Firebase：实际展示收入

Firebase 标准 `ad_impression` 现在使用 TopOn paid-impression 的：

- `publisherRevenue` 作为 `value`；
- TopOn 返回的 ISO-4217 货币作为 `currency`；
- 广告平台、广告源、广告形式、广告位与业务场景作为参数。

这避免了原先非 USD 收入被整体跳过的问题。既有累计自定义事件仍保持 USD 语义，避免把多个币种混入同一个金额。

### 炫羽展示上报

炫羽接口保留合作方指定的 `http://api.eventtrack.top`，但已增加以下保护：

- 正式运行时仅允许固定主机 `api.eventtrack.top` 和 HTTP 方案；错误配置不会向其他 HTTPS 主机外发数据。
- 非有限值或负 eCPM 不上报。
- 仅网络异常和 5xx 执行有限重试；4xx 不再无意义重试。
- 仍保持异步、非阻塞行为，炫羽失败不会影响广告展示、奖励结算或播放器恢复。

## TopOn 场景策略

合作方当前要求所有 TopOn 场景调用的 `scenarioId` 显式传 `null`，统一归入 TopOn **Default** 场景。因此客户端不新增 SCENARIO_ID 构建参数，也不需要在 TopOn 后台创建业务场景。调用顺序仍完整保留：`entryAdScenario(placementId, null) → isAdReady() → show(..., ATShowConfig.scenarioId(null))`。

## 审计结论与未自动变更项

### AdMob 与 Google Mobile Ads

当前 Manifest 只配置一个符合格式的 AdMob **App ID**，广告位 ID 没有被误写入 Manifest。Google Services 插件、Firebase 包名与正式 `applicationId` 的匹配校验均存在。TopOn 是广告请求的唯一中介入口，因此没有额外直接调用 `MobileAds.initialize()`，避免与 TopOn 适配层重复初始化。

### MAX Custom Network

代码没有嵌入 MAX SDK Key 或 MAX Ad Unit ID，符合“控制台/受控配置管理”的边界。当前 `io.github.alex-only:max_adapter_tpn:1.3.1`、MAX SDK `13.6.0` 与 TopOn Core `6.5.73.3` 的组合尚缺公开的三方兼容矩阵证据。上线前仍应向 TopOn、MAX 或该 Adapter 供应方确认：Adapter 版本兼容性、初始化责任、控制台字段映射与 R8 consumer rules。

### 隐私同意与数据收集

本次**没有擅自加入 UMP/CMP 或改变现有产品的广告授权流程**。但正式国际发行前仍必须完成隐私合规决定：

- AppsFlyer、Firebase 的启动和自动事件在 Application 启动时发生。
- Firebase 官方支持通过 `firebase_analytics_collection_enabled=false` 与 `setAnalyticsCollectionEnabled()` 在取得同意前暂停收集。
- AppsFlyer 官方支持将 `start()` 延迟到 Activity 中的同意完成后调用。
- 对 EEA、英国、瑞士及其他适用地区，需按实际投放地区、隐私政策和法务意见接入 CMP/Consent Mode/UMP，并明确未同意时哪些归因、分析和广告请求可执行。

这是产品与法律口径选择，不能只靠客户端代码猜测后直接改变，否则会影响归因、报表和变现。

### AppsFlyer 与炫羽的可靠性边界

炫羽已有有限重试；AppsFlyer 官方 `logAdRevenue()` 不提供本地送达回调。当前代码保留现有审计记录，但尚未为 AppsFlyer IAA 建立跨进程持久化 outbox。若业务要求“每个广告收入事件最终必达”，需在确认 AppsFlyer 去重语义后设计带稳定 impression ID 的持久队列，避免重试造成重复收入。

## 验证清单

生成 Release 包后，请在测试机按以下顺序验证：

1. 打开激励入口时确认请求记录按 Default 场景归类；广告 Ready 时正常展示。
2. 在激励广告未 Ready 时确认先进入加载态；仅加载失败或超时才尝试一次插屏回退。
3. 插屏不可用时确认恢复播放且不重复弹出广告。
4. 连续执行“激励失败 → 插屏展示/关闭 → 恢复 PSSDK 锁集”路径，确认不会递归、不会闪退、不会重复结算。
5. PSSDK 在受支持地区与不受支持地区分别测试：前者能打开播放器；后者保留目录并给出可重试提示，而非空黑页。
6. AppsFlyer 验证 `logAdRevenue` 的收入数据，同时核对原有 `ad_impression` / `ad_impression_001` 自定义事件未中断。
7. Firebase 使用 DebugView、Realtime 和 BigQuery 抽样验证 `ad_impression` 的 `value`、`currency`、广告源和重复率。
8. 核对炫羽只请求 `api.eventtrack.top`，HTTP 4xx 不重试，网络错误最多有限重试。

## 参考资料

[1]: https://help.toponad.net/docs/Scenario-advertising-scene "TopOn Scenario AD scene"
[2]: https://help.toponad.net/docs/Rewarded-video-Ad "TopOn Android Rewarded Ads"
[3]: https://help.toponad.net/docs/Interstitial-Ad-CQuK "TopOn Android Interstitial Ads"
[4]: https://help.toponad.net/docs/Splash-Ad-wwJ3 "TopOn Android Splash Ads"
[5]: https://help.toponad.net/docs/Revenue-reporting "TopOn Revenue Reporting"
[6]: https://dev.appsflyer.com/hc/docs/ad-revenue-1 "AppsFlyer Android Impression-Level Ad Revenue"
[7]: https://dev.appsflyer.com/hc/docs/integrate-android-sdk "AppsFlyer Android SDK Integration"
[8]: https://firebase.google.com/docs/analytics/measure-ad-revenue "Firebase Measure Ad Revenue"
[9]: https://firebase.google.com/docs/analytics/android/configure-data-collection "Firebase Analytics Data Collection Controls"
[10]: https://bytedance.sg.larkoffice.com/docx/Syexd36GCoDkGqxJa2wcaQchnQf "Dramaverse Short Drama Android SDK Integration"
