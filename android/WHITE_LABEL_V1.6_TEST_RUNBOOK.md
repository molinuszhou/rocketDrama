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
# 当前已收到合作方测试 App Key：关闭官方 Demo，使用合作方应用和广告位。
# Demo 模式仅在缺少合作方 App Key 时使用；真实广告位联调必须设为 false。
TOPON_DEMO_MODE=false
TOPON_APP_ID=h6a880d76bf897
TOPON_APP_KEY=<合作方提供的测试 App Key>
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
| 1 | 确认 `TOPON_DEMO_MODE=false`，彻底结束 App 后冷启动 | Logcat 出现 `mode=partner-config`、`Splash request started`、`Splash loaded`、`Splash shown`；关闭后进入首页 | TopOn 使用合作方测试开屏位；`onAdShow` 触发炫羽展示；paid impression 触发 AppsFlyer/Firebase 收入事件 |
| 2 | 点击欢迎奖励或签到 | 展示激励广告，完整观看后 Rewards API 结算宝石 | paid impression 触发 AppsFlyer/Firebase 收入事件；完整奖励回调触发 Firebase `rewarded_ad_completed` |
| 3 | 播放第 4 集并选择解锁 | 激励广告完整观看后解锁并自动继续播放 | 同步骤 2，且 Rewards API 解锁成功 |
| 4 | 播放至 30 秒 | 触发剧中插屏；按广告素材自身关闭规则出现关闭按钮或可使用系统返回键；关闭后原播放继续 | `TopOnAdsManager` 依次出现 `Interstitial shown`、`Interstitial closed`；随后记录 `int_episode_midroll` |
| 5 | 拖拽进度条或调整倍速 | 每次对应操作可按 v8 规则触发插屏；关闭后继续原播放 | TopOn 插屏展示/关闭、Rewards API 插屏展示记录及 paid impression 收入事件 |
| 6 | 本集翻倍、随机宝箱、皇冠加速、钱包加速 | 都使用激励广告；关闭后由 Rewards API 更新快照 | paid impression 收入事件、Firebase 完整观看事件与对应账本变化 |

### 插屏关闭异常的排查边界

插屏的“×”关闭按钮、出现时机和最短可关闭时长由 TopOn 实际选中的广告源及素材控制，应用不应覆盖或自行伪造关闭按钮。应用侧已在全屏广告期间让出系统返回键，并在 TopOn `onInterstitialAdClose` 回调后恢复播放器与宿主导航。若广告页面持续无法关闭，请提供从 `Interstitial request started` 至 `Interstitial closed` 或 `Interstitial load failed` 的完整 `TopOnAdsManager` 与 `secmtp` 日志；若没有 `Interstitial closed`，应由合作方根据广告网络、素材和广告位策略排查。

## 5. 炫羽事件校验

当前合作方 PDF 只定义了一个客户端事件：

```text
GET http://api.eventtrack.top/event/w_ad_show
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

炫羽测试服务使用 **HTTP（80 端口）**，不是 HTTPS。Android 已通过网络安全配置仅放行 `api.eventtrack.top` 的明文请求；`local.properties` 必须为 `XUANYU_EVENT_BASE_URL=http://api.eventtrack.top`。若炫羽监控未收到数据，先检查 AppsFlyer UID 是否已生成、设备网络是否可访问该 HTTP 域名、TopOn 是否真实触发展示回调，以及 Logcat 中 `WhiteLabelAnalytics` 是否有 HTTP 状态或最终失败记录。

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

## 8. v8.1 低打扰奖励广告与皇冠成长验收

本轮默认策略已调整为：剧中插屏默认在播放 90 秒后触发，且每小时最多 2 次；拖动和倍速插屏分别每小时最多 1 次，并且只有当用户待领取宝石达到后台配置的“拖动/倍速触发所需待领取宝石”阈值时才触发。当前默认阈值为 3,000 Gems。该阈值可在 Rewards 运营后台调整为 0（只按频控触发）或更高数值（进一步降低打扰）。

首次启动并完成 Rewards 快照同步后，如果新人欢迎奖励仍为未领取状态，会优先弹出“新人专享奖励”窗口；用户可以立即观看激励广告领取，也可以稍后领取。该弹窗结束后再进入连续签到窗口。已领取用户不会重复弹出。

皇冠仍为青铜、白银、黄金、钻石、皇冠五档。当前默认单集倍率为 1.00x、1.10x、1.35x、1.80x、2.50x；越高等级所需成长进度越高，皇冠作为长期目标。用户侧页面会同时显示当前倍率、下一档倍率、进度条和广告加速入口。

验收时应分别验证：

1. 新用户首次启动可以先看到新人奖励弹窗，领取后不会再次弹出；
2. 剧中播放 30 秒不会立即出现插屏，默认应在 90 秒附近触发；
3. 待领取宝石低于阈值时拖动进度条和调整倍速不会弹插屏；
4. 待领取宝石达到阈值后，拖动和倍速可分别触发插屏，但各自频控独立；
5. 皇冠页面能显示后续高倍率档位，完成等级后只影响后续生成的单集宝石，不回溯修改已生成奖励。

## 9. 开屏 Skip 与炫羽网络异常说明

开屏现在使用 TopOn 官方 `ATSplashSkipInfo` 自定义 Skip 入口。右上角 Skip 与广告展示容器处于**同一顶层覆盖层**而非广告容器内部，避免广告素材替换子视图后把 Skip 遮住。它会先显示“跳过 5s”倒计时；真机日志应出现 `Splash show with custom skip view` 和 `Splash custom skip supported=true`。支持时，TopOn SDK 绑定 Skip 点击；点击后应收到 `Splash dismissed`，并进入首页。若日志显示 `custom skip supported=false`，右上角将保留“广告播放中”状态提示，关闭时机仍由当前广告源素材控制。

合作方测试参数中的开屏广告源日志可能出现 `close_button=0`、`allows_skip=1`、`countdown=5`。这表示是否显示关闭按钮、最短可跳过时间和 Skip 行为仍由 TopOn 实际选中的广告源配置决定；应用不能覆盖广告源的关闭策略。若自定义 Skip 支持开启，则优先使用应用右上角自定义 Skip；若不支持，则应按广告素材自身的可跳过规则操作。

炫羽上报失败不会阻断广告、奖励或播放器。当前对网络/DNS/HTTP 非成功响应最多进行 2 次重试；日志会区分“将重试”和“最终失败”。炫羽测试地址必须使用 `http://api.eventtrack.top`（80 端口）；此前出现 `Failed to connect to api.eventtrack.top:443` 是错误使用 HTTPS 的直接表现。切换为 HTTP 后，若仍失败，再由合作方检查设备网络、DNS、地区访问和服务器可用性；AppsFlyer/Firebase 的收入事件仍可独立上报。
