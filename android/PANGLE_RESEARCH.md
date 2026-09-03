# Pangle 接入核验笔记

本笔记记录 2026-08-12 对 Pangle 官方 Android 文档的核验结果，供工程集成使用。

| 主题 | 官方文档确认的要点 | 对本工程的处理 |
| --- | --- | --- |
| Android 依赖 | Pangle 要求在 Gradle 仓库中引入 `https://artifact.bytedance.com/repository/pangle`，并通过应用模块添加 SDK 依赖。 | 在 `settings.gradle.kts` 配置 Pangle 仓库；SDK 版本由构建配置集中管理。 |
| 初始化 | 使用 `PAGConfig.Builder().appId(...)` 构造配置，并在 `PAGSdk.init(..., PAGInitCallback)` 的 `success()` 后加载广告。 | 提供异步 `PangleAdsManager`，不在应用首帧路径中强制等待初始化。 |
| 隐私 | `PAGConfig.Builder` 提供个性化广告同意状态配置；SDK 初始化后可能预加载广告。 | 首次隐私同意后才允许开始 SDK 初始化；未同意或 App ID 缺失时保持禁用。 |
| 激励广告 | `PAGRewardedAd.load(slotId, PAGRewardedRequest, PAGRewardedAdLoadListener)` 加载；展示前注册交互回调；`show()` 必须在主线程调用。 | 解锁、双倍奖励、任务广告共用统一激励广告入口；奖励只在 `onUserEarnedReward` 后触发。 |
| 预加载 | 展示或关闭瀑布激励广告后，SDK 会为下一次展示自动发起请求。 | 不额外实现无控制的循环预加载。 |
| 短剧内容 | 本次 Pangle 官方 Android 集成文档聚焦广告变现 SDK；未检索到公开、可供发行方直接接入的 Pangle 短剧内容/剧集播放 SDK 文档。 | 剧集内容需使用单独授权的内容服务或 Pangle/ByteDance 商务提供的私有 SDK、Maven 坐标和凭据；工程保留内容提供方接口，不把广告 SDK 误作剧集服务。 |

## 必须由账户管理员提供的配置

| 配置项 | 用途 | 处理规则 |
| --- | --- | --- |
| Pangle App ID | 初始化 Pangle Ads SDK | 置入本机 `local.properties` 或 CI 密钥变量，不提交至 Git。 |
| Rewarded Video 广告位 ID | 锁集解锁、双倍奖励、任务激励广告 | 按环境分离测试与生产广告位。 |
| App Open / Interstitial 广告位 ID（如启用） | 冷启动或自然内容间广告 | 默认不启用；需明确频控策略后再接入。 |
| 短剧服务 SDK / API 资料 | 分类、搜索、Feed、详情、播放器 | 必须为正式授权材料；与 Pangle 广告 SDK 独立处理。 |
| 奖励回调服务端地址（生产） | 激励广告奖励校验 | 生产奖励以服务端结果为准，避免仅信任客户端回调。 |

## 资料来源

[1]: https://www.pangleglobal.com/integration/integrate-pangle-sdk-for-android "Pangle：Android Integration"
[2]: https://www.pangleglobal.com/integration/android-initialize-pangle-sdk "Pangle：Initialize Pangle SDK"
[3]: https://www.pangleglobal.com/integration/android-rewarded-video-ads "Pangle：Rewarded Video Ads"

## 官方代码片段补充

官方激励广告示例页面展示的加载入口为 `PAGRewardedAd.loadAd("YOUR_AD_PLACEMENT_ID", request, listener)`，展示入口为 `rewardedAd.show(currentActivity)`。页面同时提供了服务端奖励验证说明，因此生产环境的解锁、双倍奖励和任务领取应由服务端完成最终确认，客户端的 `onUserEarnedReward` 仅作为发起奖励核验或展示待结算状态的触发条件。[3]

## 依赖版本核验说明

Pangle 官方集成页面确认了 Maven 仓库地址，但其当前网页未在可提取内容中暴露完整的依赖版本代码块。后续工程接入将通过 Pangle Maven 仓库的元数据和 Gradle 依赖解析实际验证 SDK 坐标；不得仅依据第三方网页或旧演示工程的版本号猜测生产依赖。

Pangle 官方 Maven 元数据在本次核验时返回 `com.pangle.global:ads-sdk` 的最新发布版本为 **6.5.1.2**。工程将固定使用此可解析版本，并在 Gradle 构建中再次验证。
