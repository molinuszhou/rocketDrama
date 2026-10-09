# JoyeDrama Android

**JoyeDrama** 是一个以自有产品品牌呈现的 Android 短剧客户端。应用在当前测试阶段使用本地内容服务和 Media3 播放器，同时已保留 PSSDK 内容检索、广告聚合、奖励服务和钱包的集成边界。内容供应商、广告网络与运营配置均属于内部集成层，不会出现在产品导航、活动命名或页面结构中。

## 当前架构

启动路由、页面状态、播放控制、奖励流程和界面渲染按照职责拆分在 `com.dramaflow.app` 下。`LauncherActivity` 仅负责系统 SplashScreen 和进入产品活动；`DramaAppActivity` 是轻量的生命周期、路由和共享状态宿主。页面与业务流程由同一 package 中的 extension 模块组成，借助 `internal` 可见性共享宿主状态，而不会将实现细节暴露给其他 application 模块。

| 模块 | 位置 | 职责 |
| --- | --- | --- |
| 启动路由 | `launcher/LauncherActivity.kt` | 系统 SplashScreen 与产品活动转发。 |
| 产品宿主 | `presentation/DramaAppActivity.kt` | 生命周期、路由、共享状态、服务地址设置和播放进度持久化。 |
| 启动协调 | `presentation/StartupCoordinator.kt` | 并行预热目录、首屏封面、奖励快照与开屏广告，并在必要资源就绪后进入主页。 |
| 页面与通用组件 | `presentation/CatalogScreens.kt`、`AppChrome.kt`、`WalletScreen.kt` | 首页、发现、搜索、个人中心、统一资产栏、钱包与通用 View 组件。 |
| 奖励中心 | `presentation/RewardDashboard.kt`、`RewardEngagement.kt`、`RewardLifecycle.kt`、`RewardAdFlow.kt` | 皇冠之路、签到、宝箱、新人奖励、账本、广告结算与奖励生命周期。 |
| 播放体验 | `presentation/PlayerScreen.kt`、`EpisodeFlow.kt` | Media3 播放器、沉浸控件、手势、选集、解锁、完播奖励与续播。 |
| UI 常量 | `presentation/PresentationConstants.kt` | 产品内共享色板、偏好键、恢复状态键与内容配置常量。 |
| SDK 与服务边界 | `ads/`、`content/`、`local/`、`rewards/` | 广告、PSSDK、内容服务与奖励服务的隔离层。 |
| 分析 | `analytics/EngagementAnalytics.kt` | 非产品供应商命名的埋点与广告收入分析。 |

> **白包命名约束：** 产品代码不使用内容供应商名称命名页面、Activity、目录或分析模块。新增集成应放入明确的 SDK 或服务适配层，避免供应商概念进入用户可见体验。

## 启动与首屏预加载

进入 `DramaAppActivity` 后，目录请求、奖励快照和开屏广告流程并发开始。目录返回后，首屏可能出现的最多 12 张封面将通过 Coil 写入内存和磁盘缓存。应用仅在目录、开屏流程及这批封面请求全部完成后进入主界面；封面预热设有 **1.8 秒上限**，因此慢速或失效图片不会阻塞启动。这一约束将“预加载”变成可观察的首屏交接条件，而非仅发起但不等待的后台请求。

播放器的分集与位置每三秒持久化一次。应用从后台返回时优先恢复同一 Activity 和 ExoPlayer；若进程被系统回收，启动协调器在目录预热完成后恢复已保存的剧集、分集与播放位置。

## 奖励中心与钱包

奖励页首屏使用一体式、可点击的宝石与钱包余额栏。用户上滑至首屏余额离开可视区域后，顶部会淡入与首页一致的资产栏；宝石入口展示收益明细，钱包入口进入钱包页。金额控件启用单行、自适应字号，覆盖最高约 200 万宝石与 200 美元的展示范围。

皇冠之路采用紧凑时间线：每级使用不同的等级图标，连线显示等级条件与当前进度，节点下方展示宝石奖励和加速倍率。每日签到只保留一个可见主标题，避免标题层级重复。钱包展示可提现余额、预计次日转入金额、广告加速提现及提现规则；真实申请仍由服务端资格策略决定。

## 广告、内容与安全边界

广告展示、关闭和恢复逻辑位于 `ads/TopOnAdsManager.kt` 与奖励广告流程模块。全屏广告出现前会保存播放状态并退出沉浸式模式，关闭后自动恢复原本正在播放的剧集；广告窗口采用系统栏安全区域，降低刘海、挖孔和曲面屏边缘关闭按钮难以触达的风险。

短剧内容在测试阶段来自局域网内容服务。PSSDK 的搜索与发现接口通过 `content/ShortDramaSdkManager.kt` 适配，生产环境使用 SDK 返回内容时保留统一的搜索结果和详情播放入口。本地服务继续作为测试和网络失败回退内容源。

Release 构建启用窗口防截屏标志；Debug 构建保持可截图，便于测试和问题定位。真实广告奖励、钱包入账、提现资格和支付均必须以服务端回调、验签、幂等和风控结论为准，客户端不应单独发放真实资产。

## 本地验证

工程使用 **JDK 17**、Android SDK Platform 35 和 Gradle Wrapper。标准 Kotlin 编译验证命令如下：

```bash
cd android
./gradlew :app:compileDebugKotlin -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64
```

内容服务的离线冒烟脚本可在仓库根目录执行。奖励服务的 HTTP 冒烟测试需要先启动 PostgreSQL 与奖励 API；测试地址默认是 `http://127.0.0.1:8110`，可通过 `REWARDS_TEST_BASE_URL` 覆盖。

## 正式 Release 构建与签名（Windows）

> 以下脚本仅适用于当前 `release/formal-production` 检出的正式工程。脚本使用 `local.properties.release` 中的正式配置，构建期间会临时覆盖 `local.properties`，并在结束（包括失败）后自动恢复原文件。脚本不会保存 Upload Key 密码。

### 前置检查

确认下列受控本地文件存在，且与正式包名 `com.feather.joyelledrama` 对应：

- `local.properties.release`：正式广告、AppsFlyer、Pangle、Rewards HTTPS 地址等构建参数；
- `app/google-services.json`：Firebase 项目配置；
- `app/src/main/assets/license/l-42456072209-ch-vod-a-1053223.lic`：Pangle 短剧 License；
- 工作区根目录的 `joyedrama-upload-key-and-sign-scripts/joyedrama-upload-keystore.jks`：Google Play Upload Key。

脚本运行前会打印当前 Git 分支、版本与包名。若不是预期的正式分支或版本，先停止并检查工程状态。

### 生成已签名 Release 测试 APK

在 PowerShell 中执行：

```powershell
cd D:\gworkspace\R1\JoyeDrama\android
powershell -ExecutionPolicy Bypass -File .\scripts\build-release-test-apk.ps1
```

用于重复构建、希望跳过 Gradle `clean` 时：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-release-test-apk.ps1 -SkipClean
```

该脚本依次执行 `:app:assembleRelease`、`zipalign`、Upload Key 签名、签名校验与对齐校验。输出 APK 位于：

```text
android\release-artifacts\JoyeDrama-release-test-signed-时间戳.apk
```

### 生成 Google Play 上传 AAB

在 PowerShell 中执行：

```powershell
cd D:\gworkspace\R1\JoyeDrama\android
powershell -ExecutionPolicy Bypass -File .\scripts\build-google-play-aab.ps1
```

用于重复构建、希望跳过 Gradle `clean` 时：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-google-play-aab.ps1 -SkipClean
```

该脚本执行 `:app:bundleRelease`，再以 Upload Key 签名和校验 AAB。若已启用 **Google Play App Signing**，上传这个生成的 AAB；Google Play 会用其托管的 App Signing Key 重新签名分发给用户。输出 AAB 位于：

```text
android\release-artifacts\JoyeDrama-google-play-upload-signed-时间戳.aab
```

两个脚本均会在签名环节交互要求输入 Upload Key 密码，密码不会写入脚本、Git、构建参数或产物目录。`release-artifacts/` 已被 Git 忽略。

## 参考资料

[Android app startup guidance](https://developer.android.com/topic/performance/vitals/launch-time) 说明了启动耗时与首帧可交互的测量方法。Pangle 及聚合广告的初始化与激励广告回调规范应以当前接入 SDK 的官方文档为准，并在真机上覆盖完整观看、提前关闭、无填充、隐私撤回、前后台切换与播放恢复场景。
