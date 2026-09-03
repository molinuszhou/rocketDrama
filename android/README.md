# DramaFlow Android

**DramaFlow** 是依据《FreeDrama 产品功能策划说明书（静态拆解版）》实现的 Android 原生交互原型。工程采用 Kotlin 与 Jetpack Compose，目标是在不阻塞首屏的前提下，提供可替换的短剧内容、激励广告、任务与钱包边界。

> **当前定位：** 已完成 Pangle Android 激励广告 SDK 的工程集成、隐私同意、频控和奖励回调保护；尚未注入 Pangle 账户参数，也尚未接入授权短剧内容、真实支付、提现或服务端奖励验签。因此，当前构建不会请求真实广告或发放真实资产。

## 已实现范围

| 产品域 | 当前实现 | 说明 |
| --- | --- | --- |
| 启动与导航 | 系统启动页、单 Activity、Compose 四 Tab | 启动路径不进行网络、广告 SDK 或大图加载。 |
| 首页、发现与搜索 | 分类、推荐、纵向内容流、搜索历史、空态 | 目录采用缓存优先策略，离线也可立即渲染首屏。 |
| 播放与选集 | 演示播放画面、选集、继续播放、收藏和历史 | 锁集已接入广告解锁流程，但缺少广告配置时会安全禁用。 |
| 任务与钱包 | 签到、任务、普通领取、演示钱包与资格提示 | 现金、金币、提现资格仍为本地原型状态，生产环境必须由服务端决定。 |
| Pangle 激励广告 | SDK 依赖、隐私同意、按需初始化、锁集解锁、双倍奖励 | 使用 `PangleAdsManager` 隔离 SDK；只在奖励回调后进入业务结算。 |
| 广告保护 | 单请求、30 秒展示间隔、提前关闭/失败不发奖 | 客户端保护不替代服务端验签、风控与幂等。 |
| 设置 | 广告隐私状态与重新选择入口 | 用户拒绝时不会初始化广告 SDK，普通内容浏览不受影响。 |

## 架构与性能取向

应用不在 `onCreate()` 中加载广告或远程内容。内容目录由 `CacheFirstDramaRepository` 先返回磁盘缓存或本地回退目录，后续授权内容服务可调用 `replaceCatalogFromPartner()` 替换目录；广告 SDK 只在用户主动选择激励广告后异步初始化。这样的分层可以把网络与渠道依赖移出首帧关键路径。

| 层级 | 文件位置 | 职责 |
| --- | --- | --- |
| 入口与主题 | `MainActivity.kt`、`ui/theme/` | 原生启动页、Compose 根界面和深色主题。 |
| 单向状态 | `AppViewModel.kt` | 路由、播放、收藏、任务、广告奖励和 Snackbar 状态。 |
| 广告隔离层 | `ads/PangleAdsManager.kt` | Pangle 初始化、激励广告加载、展示、频控与回调防重。 |
| 内容数据层 | `data/CacheFirstDramaRepository.kt` | 目录快照、离线回退与未来远程目录替换。 |
| 授权内容契约 | `data/ShortDramaContentGateway.kt` | 短剧 Feed、搜索、详情、播放 URL 与权益模型的接入边界。 |
| 页面 | `ui/MainScreens.kt`、`ui/DetailScreens.kt` | 四个主 Tab、播放器、任务、钱包、设置等页面。 |

Android 官方建议将冷启动、首帧可见（TTID）和完全绘制/可交互体验纳入性能基线。本工程采用系统启动页、本地首屏目录、按路由组合和广告延迟初始化来缩短关键路径。[1] [2]

## Pangle 配置

请勿把真实 App ID 或广告位 ID 提交进源码。复制 `pangle.properties.example` 的内容到开发机 `local.properties`，或在 CI 使用同名 Gradle 属性/环境变量注入：

```properties
PANGLE_APP_ID=你的_Pangle_App_ID
PANGLE_REWARDED_SLOT_ID=你的_激励视频广告位_ID
```

详细的 SDK 接入、隐私流程、频控、测试广告位联调步骤和服务端奖励验签要求见 [PANGLE_SETUP.md](PANGLE_SETUP.md)。Pangle 官方说明要求在初始化成功回调后加载广告，且激励广告展示需在主线程进行。[3] [4]

## 内容服务接入

Pangle 公开 Android 文档提供的是广告变现 SDK，而不是公开剧集内容服务。拿到 Pangle/ByteDance 的短剧合作 SDK，或第三方已授权内容 API 后，应实现 `ShortDramaContentGateway` 并把转换后的目录传入 `replaceCatalogFromPartner()`。本地目录缓存会作为网络异常时的首屏回退，不需要改写现有 UI 页面。

## 金币、阶段宝箱与资格规则

金币与任务模块已改为透明的阶段化模型：基础任务奖励、广告双倍数额、每日基础金币上限、阶段点阈值和宝箱奖励都会在领取前展示。当前原型设置为每日基础金币上限 800；达到 3、6、10 个阶段点可领取固定的 60、120、220 金币宝箱。最低兑换资格为 500 金币、至少完成 3 集有效观看并完成当日签到；不满足时会明确列出缺失条件。

这些参数仅用于原型展示。生产环境必须由服务端下发、签名和审计，并由服务端决定广告奖励、阶段宝箱、兑换资格和提现申请状态；客户端不应自行结算真实金额。完整规则见 [REWARD_RULES.md](REWARD_RULES.md)。

## 本地构建与验证

工程以 JDK 17、Android SDK Platform 35 和 Android Gradle Plugin 8.7.3 为基线。可用 Android Studio 直接打开工程同步；在当前验证环境中使用 Gradle 8.11.1 完成 Debug Kotlin 编译与 Release APK 构建。

```bash
# 使用 Android Studio 内置 Gradle，或已安装的 Gradle 8.11.1
./gradlew :app:assembleDebug
```

产物目录：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

Release APK 未签名，仅用于工程验证。正式上架必须使用受控的签名密钥、真实账号配置、隐私声明、服务端奖励校验和真机回归测试。

## 后续接入顺序

| 阶段 | 待接入能力 | 关键约束 |
| --- | --- | --- |
| 1 | Pangle 测试 App ID 与激励广告位 | 先走真机完整观看、提前关闭、无填充、频控和隐私撤回测试。 |
| 2 | 服务端奖励验证 | 用幂等键、用户身份、业务动作与渠道回调决定最终奖励。 |
| 3 | 授权短剧目录与播放器 | 实现 `ShortDramaContentGateway`，支持缓存、分页、播放错误回退和合法授权。 |
| 4 | 登录、任务与钱包服务 | 金额、资格、频次和风控结论全部以服务端签名结果为准。 |
| 5 | 性能发布 | 使用 Macrobenchmark、Baseline Profile 与真机 TTID/TTFD、帧率回归。 |

## 参考资料

[1]: https://developer.android.com/topic/performance/vitals/launch-time "Android Developers：App startup time"
[2]: https://developer.android.com/topic/performance/appstartup/analysis-optimization "Android Developers：App startup analysis and optimization"
[3]: https://www.pangleglobal.com/integration/android-initialize-pangle-sdk "Pangle：Initialize Pangle SDK"
[4]: https://www.pangleglobal.com/integration/android-rewarded-video-ads "Pangle：Rewarded Video Ads"
