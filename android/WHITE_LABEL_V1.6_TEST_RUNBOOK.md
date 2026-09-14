# JoyeDrama 白包 v1.6 测试与联调手册

## 1. 本次接入范围

版本 `1.6`（`versionCode=16`）已完成以下 Android 代码与构建接入：

| 链路 | 实现状态 | 验证口径 |
|---|---|---|
| TopOn 聚合广告 | 已接入开屏、激励、插屏；保留 v8 原有广告点与 Rewards API 结算链路 | TopOn Logcat 中请求、展示、关闭均返回成功；广告展示后应用流程继续 |
| AppsFlyer | 已初始化；仅在 TopOn `onAdRevenuePaid` 中上报 `ad_impression` 和累计 `ad_impression_001` 两个收入自定义事件 | AppsFlyer Debug/后台可见安装、事件名、USD 金额、币种与次数 |
| Firebase | 已使用匹配包名的 `google-services.json`；发送 `ad_impression` 标准事件和一个可配置的激励完成自定义事件 | Firebase DebugView 可见事件和参数 |
| 炫羽服务器 | 已按 PDF 在 TopOn 广告展示时 GET `/event/w_ad_show` | 炫羽监控收到 `uid/app_ver/pkg_name/ad_type/ad_network/ecpm` |
| v8 积分与解锁 | 仍由 Rewards API 负责，不因第三方上报失败而阻断 | 完整看激励广告后奖励、解锁、宝箱、翻倍等原有账本操作正常 |
| 自有短剧内容 | 现有 Local Content API、播放 URL、封面、进度与 v8 解锁保持可用；首页/发现页从 `genres` 生成分类，并按收藏、续播和题材偏好排序推荐 | 目录、分类、推荐、播放、续播、锁集解锁正常 |

> 开屏广告、激励广告、插屏广告的展示都先通过 TopOn 回调确认；炫羽、AppsFlyer、Firebase 是旁路数据上报，失败不会错误地发放奖励，也不会阻塞用户继续播放。

## 2. 测试前配置

将 `android/white-label.properties.example` 的必要字段复制到 `android/local.properties`。请勿提交 `local.properties`、`app/google-services.json`、License 或签名文件。

```properties
sdk.dir=C:\\Users\\<用户名>\\AppData\\Local\\Android\\Sdk
# 无合作方 App Key 时：Debug 包填 true，自动使用 TopOn 官方 Demo App/广告位。
# TopOn 官方 Demo 仅用于验证初始化、加载、展示与应用广告链路，不代表合作方真实瀑布流或收入。
TOPON_DEMO_MODE=true
TOPON_APP_ID=h6a880d76bf897
TOPON_APP_KEY=<合作方提供的测试 App Key；Demo 模式下可留空>
TOPON_INTERSTITIAL_PLACEMENT_ID=n6a880e948c456
TOPON_REWARDED_PLACEMENT_ID=n6a880eaaa1a1e
TOPON_SPLASH_PLACEMENT_ID=n6a880ebb7fb00
ADMOB_APP_ID=ca-app-pub-4636975460860893~1434961000
APPSFLYER_DEV_KEY=DdWbxT9VRELdEsZiAcnGea
ANALYTICS_DEBUG=true
```

将 `Dramaflow-google-services.json` 放在下列位置：

```text
android/app/google-services.json
```

文件中的包名必须是 `com.dramaflow.app`。当前工作区已安装匹配文件，但它被 Git 忽略，不会随代码推送。

## 3. 真机 Debug 开关

在 USB 调试已连接设备上执行：

```powershell
adb shell setprop debug.firebase.analytics.app com.dramaflow.app
adb logcat -c
adb logcat -v time | Select-String "WhiteLabelAnalytics|AppsFlyer|secmtp|anythink|FirebaseAnalytics"
```

TopOn 测试真机应具备 Google 服务并能够取得 GAID。测试期 `ANALYTICS_DEBUG=true` 会打开 TopOn 网络调试日志；正式上架前必须设为 `false`。

## 4. 推荐联调顺序

| 步骤 | App 操作 | 预期应用结果 | 应核查的数据 |
|---:|---|---|---|
| 1 | 先确认 `TOPON_DEMO_MODE=true`，彻底结束 App 后冷启动 | Logcat 出现 `mode=official-demo`、`Splash request started`、`Splash loaded`、`Splash shown`；关闭后进入首页 | Logcat 标签 `TopOnAdsManager` / `secmtp` 显示初始化、请求、展示；`onAdShow` 触发炫羽展示；如 Demo 产生 paid impression，`onAdRevenuePaid` 触发 AppsFlyer/Firebase 收入事件 |
| 2 | 点击欢迎奖励或签到 | 展示激励广告；完整观看后 Rewards API 结算宝石 | paid impression 触发 AppsFlyer/Firebase 收入事件；完整奖励回调触发 Firebase `rewarded_ad_completed` |
| 3 | 播放第 4 集并选择解锁 | 激励广告完整观看后解锁并自动继续播放 | 同步骤 2，且 Rewards API 解锁成功 |
| 4 | 播放至 30 秒 | 触发剧中插屏；关闭后原播放继续 | `int_episode_midroll` 的炫羽展示和 paid impression 收入事件 |
| 5 | 拖拽进度条或调整倍速 | 每次对应操作可按 v8 规则触发插屏 | TopOn 插屏展示；Rewards API 插屏展示记录；paid impression 收入事件 |
| 6 | 本集翻倍、随机宝箱、皇冠加速、钱包加速 | 都使用激励广告；关闭后由 Rewards API 更新快照 | paid impression 收入事件、Firebase 完整观看事件与对应账本变化 |

## 5. 炫羽事件校验

当前合作方 PDF 只定义了一个客户端事件：

```text
GET https://api.eventtrack.top/event/w_ad_show
```

每次 TopOn **广告展示**会携带下列字段：

| 字段 | 来源 |
|---|---|
| `uid` | AppsFlyer SDK 的 AF UID |
| `app_ver` | BuildConfig 版本，当前为 `1.6` |
| `pkg_name` | 当前包名 `com.dramaflow.app` |
| `ad_type` | TopOn `ATAdInfo` 广告格式 |
| `ad_network` | TopOn `ATAdInfo` 广告网络名称 |
| `ecpm` | TopOn `ATAdInfo` eCPM |

若炫羽监控未收到数据，先检查：AppsFlyer UID 是否已生成、设备网络是否可访问 `api.eventtrack.top`、TopOn 是否真实触发展示回调、以及 Logcat 中 `WhiteLabelAnalytics` 是否有 HTTP 状态。

## 6. 当前必须补齐的合作方资料

| 缺失项 | 为什么必须提供 |
|---|---|
| TopOn `App Key` | **官方 Demo 测试不需要合作方 App Key**：设 `TOPON_DEMO_MODE=true` 即可使用官方 Demo 凭据。切换到合作方广告位、真实瀑布流和真实广告收入联调时，仍必须提供合作方 App Key。 |
| 无 | 已收到《AppsFlyer 收入事件接入指南》并实现：paid impression 单次 `ad_impression`，累计达到 $0.01 后 `ad_impression_001` |
| Firebase 标准事件和自定义事件最终定义 | 当前基线为 `ad_impression` 与 `rewarded_ad_completed`；可按合作方文档通过配置/代码调整 |
| 短剧 SDK AAR/Maven 坐标 | 已从历史版本恢复 `com.bytedance.dramaverse:pssdk:1.8.0.0` 及 Maven 仓库；已验证可与本地视频、TopOn、AppsFlyer、Firebase 共同构建 |
| 短剧 App ID、VOD App ID、安全密钥 | 当前保持本地视频测试，因此开关默认关闭；未来开启 `SHORT_DRAMA_SDK_ENABLED=true` 时必须提供这些参数 |
| 短剧 SDK 的测试包名/签名白名单要求 | 外部目录与外部播放器启用前需确认当前 `com.dramaflow.app` 与测试签名已获授权 |

已从此前资料将 License 本地放入 `app/src/main/assets/l-1153247249-ch-vod-a-1053223.lic`；文件被 Git 忽略。短剧 PSSDK 的热门、最新、分类和分类内容读取管理器已恢复，默认保持 `SHORT_DRAMA_SDK_ENABLED=false`，所以当前仍只从本地 Content API 读取并播放测试视频。收到外部短剧测试参数后，只需配置 License 路径、App ID、VOD App ID 和安全密钥，再开启开关进行外部目录与播放器联调。

## 7. 构建命令

```powershell
cd android
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

当前沙盒已完成开启 `TOPON_DEMO_MODE=true` 的 `:app:assembleDebug`，以及强制关闭 Demo 模式的 `:app:assembleRelease` 构建验证；生成的 Debug `BuildConfig.TOPON_DEMO_MODE=true`，Release 为 `false`。开屏请求已修正为：先在 `onPostResume` 等待 Activity 窗口进入前台，再等待 TopOn 异步初始化回调成功，最后加载/展示开屏；Demo 模式还会使用官方 Demo 的 AdMob App ID。已验证 PSSDK、TopOn、AppsFlyer、Firebase 与炫羽上报依赖可共同打包。真机使用带 Google Play 服务的设备，Logcat 可按 `TopOnAdsManager` 与 `secmtp` 查看 TopOn 日志；Demo 测试通过后，仍需在合作方认可的网络、账号和正式参数环境中验证真实填充、归因及服务器监控。
