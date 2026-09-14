# 白包 v1.6 外部 SDK 与测试上报备忘

## 合作方测试参数

来源：`/home/ubuntu/upload/参数表-测试包.xlsx`。

| 配置 | 测试值 |
|---|---|
| 应用包名 | `com.dramaflow.app` |
| AppsFlyer Dev Key | 已在参数表提供（仅本地 BuildConfig 使用，禁止提交到公开仓库） |
| TopOn App ID | 已在参数表提供 |
| TopOn 插屏广告位 | 已在参数表提供 |
| TopOn 激励广告位 | 已在参数表提供 |
| TopOn 开屏广告位 | 已在参数表提供 |
| AdMob App ID | 已在参数表提供 |
| Firebase | `Dramaflow-google-services.json`，包名匹配 `com.dramaflow.app` |

> 参数表未列出 TopOn App Key，也未列出短剧 SDK 的短剧 App ID、VOD App ID、安全密钥。TopOn 初始化需要 `ATSDK.init(context, appId, appKey)`，因此 App Key 是广告真实联调的阻塞参数。

## 炫羽海外事件上报

来源：`/home/ubuntu/upload/海外事件上报.pdf`。

- 域名：`api.eventtrack.top`
- 请求方法：`GET`
- 每次事件通用参数：`uid`（AppsFlyer SDK 获取的 AF ID）、`app_ver`、`pkg_name`。
- 当前文档定义的广告展示路径：`/event/w_ad_show`。
- 广告展示参数：`ad_type`、`ad_network`、`ecpm`；均直接从 TopOn `ATAdInfo` 获取。
- 实现要求：上报失败不能影响广告关闭、内容播放、奖励结算或解锁。

## TopOn 参考

官方文档：

- Android Guide: https://help.toponad.net/docs/vxp6Qy
- Test Guide: https://help.toponad.net/docs/How-to-test-ads
- 官方示例工程：https://github.com/toponteam/TPN-Android-Demo

本地参考工程：`/home/ubuntu/topon_android_demo_reference`。

| 项目 | 已验证的 SDK API |
|---|---|
| 初始化 | `ATSDK.init(context, appId, appKey)` |
| 测试日志 | `ATSDK.setNetworkLogDebug(true)`；Logcat 使用 `secmtp` 标签 |
| 激励广告 | `ATRewardVideoAd(context, placementId)`、`load()`、`show(activity)`；奖励回调 `onReward(ATAdInfo)`，关闭回调 `onRewardedVideoAdClosed(ATAdInfo)` |
| 插屏广告 | `ATInterstitial(context, placementId)`、`load()`、`show(activity)`；展示回调 `onInterstitialAdShow(ATAdInfo)`，关闭回调 `onInterstitialAdClose(ATAdInfo)` |
| 开屏广告 | `ATSplashAd(context, placementId, listener, timeout)`、`loadAd()`、`show(activity, ViewGroup)` |
| 广告收入 | `ATAdInfo.getTopOnAdFormat()`、`getNetworkName()`、`getEcpm()`、`getCurrency()`；`ATAdRevenueListener.onAdRevenuePaid(ATAdInfo)` |

TopOn 测试要点：测试真机应具备 Google 服务并可获取 GAID；开启网络调试日志后检查所有广告行为的 `result=success`；上架前关闭 SDK 网络调试。

## AppsFlyer 参考

官方文档：

- 广告收入 API: https://dev.appsflyer.com/hc/docs/ad-revenue-1
- 应用内事件: https://dev.appsflyer.com/hc/docs/in-app-events-sdk

已核对 SDK `com.appsflyer:af-android-sdk:6.15.2`：

- 初始化：`AppsFlyerLib.getInstance().init(devKey, listener, context)` 后调用 `start(context)`。
- AF ID：`getAppsFlyerUID(context)`。
- 自定义事件：`logEvent(context, eventName, values)`。
- 展示层广告收入：`logAdRevenue(AFAdRevenueData, additionalParameters)`。
- `MediationNetwork.TOPON` 在该 SDK 版本中存在。

> 合作方要求的两个 AppsFlyer 自定义事件名称与字段仍需以《AppsFlyer 收入事件接入指南》为准；实现可先以可配置的展示、激励完成事件作为测试基线，再按文档精确替换。

## Firebase 参考

- 已提供的 `Dramaflow-google-services.json` 与当前包名匹配。
- 当前白包基线：使用标准事件 `ad_impression`，以及自定义事件 `rewarded_ad_completed`。
- 测试期使用 Firebase DebugView 验证；推荐在 Android Debug 构建中通过 `adb shell setprop debug.firebase.analytics.app com.dramaflow.app` 打开 DebugView。

## AppsFlyer 收入事件指南（合作方 PDF）

来源：`/home/ubuntu/upload/AppsFlyer收入事件接入指南.pdf`。

- 本项目**不使用** AppsFlyer `logAdRevenue`；两个事件均为合作方定义的 In-app Event，使用 `AppsFlyerLib.logEvent`。
- 仅在广告平台/聚合 SDK 的真实 paid impression/revenue paid 回调（TopOn `onAdRevenuePaid`）中上报；不得在加载、展示、关闭或奖励回调中上报或估算收入。
- 单次事件：`ad_impression`。必填参数 `currency: "USD"`、`value: <本次真实 USD 收入数值>`；可选参数为 `ad_platform`、`ad_source`、`ad_unit_id`、`ad_placement`、`ad_type`。
- TopOn 回调提供 eCPM 时，单次展示收入为 `ecpmUsd / 1000.0`。
- 累计事件：`ad_impression_001`。只累计 USD、正数、有限的真实展示收入；内存累计值达到或超过 `0.01 USD` 时上报一次，其 `currency` 和 `value` 为本轮完整累计值，随后清零。进程结束时不持久化余额。
- 验收：每个 paid impression 只产生一次单次事件；两事件参数名必须小写 `currency` / `value`，且 `value` 必须为数值；应在 AppsFlyer Live Events/Raw Data 核验金额、币种和次数。

## 短剧 SDK 恢复结论

历史 `com.feather.joyelledrama` 工程使用 `com.bytedance.dramaverse:pssdk:1.8.0.0`，并依赖下列 Maven 仓库：

- `https://artifact.bytedance.com/repository/Volcengine/`
- `https://artifact.byteplus.com/repository/public/`

PSSDK 已在 v1.6 工程作为可选依赖恢复，并验证与 TopOn、AppsFlyer、Firebase、Media3 本地视频共同 Debug/Release 构建。可选内容管理器在开关开启和参数齐全后使用 `PSSDK.requestPopularDrama`、`requestNewDrama`、`requestCategoryList` 和 `requestFeedListByCategoryIds` 读取热门、最新、分类和分类内容。当前 `SHORT_DRAMA_SDK_ENABLED=false`，本地 Content API 和本地视频播放保持不变。

## TopOn 官方 Demo 与测试模式

TopOn 的 SDK 日志开关应在 Debug 测试中打开，上线前关闭。Logcat 可按 `secmtp` 查看初始化、请求、展示、点击与关闭等链路。TopOn 测试工具和在线 ID 测试本身仍要求 SDK 已用 App ID 与 App Key 初始化；其作用是检查 SDK/adapter 集成或调试已在 TopOn 后台配置的广告位，不能替代应用内的广告流程回归。[1]

为在没有合作方 App Key 的阶段测试当前应用的广告逻辑，v1.6 已内置 **仅 Debug 可用** 的 `TOPON_DEMO_MODE`。设为 `true` 时，Gradle 自动使用 TopOn 官方 Android Demo `placementId.json` 中的公开 Demo App ID、App Key，以及 Rewarded / Interstitial / Splash 测试广告位。Demo 测试可验证 `ATSDK.init`、广告加载、展示、关闭、v8 权益结算和旁路事件调用；其广告源、填充和收入不能视为合作方真实数据。Release 变体强制让 `BuildConfig.TOPON_DEMO_MODE=false`，防止 Demo 凭据被带入正式包。

| 配置场景 | `TOPON_DEMO_MODE` | App Key 来源 | 适用范围 |
|---|---:|---|---|
| 当前开发验证 | `true` | TopOn 官方 Demo | SDK 初始化、三类广告展示和应用流程回归 |
| 合作方测试/真实数据联调 | `false` | 合作方 TopOn 账户 | 实际广告位、AppsFlyer/Firebase/炫羽数据核验 |
| Release / 上线 | 强制 `false` | 合作方正式账户 | 正式投放 |

[1]: https://help.toponad.net/docs/How-to-test-ads "TopOn — How to Test"
