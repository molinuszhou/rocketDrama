# DramaFlow 性能设计与验收计划

本文件区分两个事实：**工程已落地的性能设计**与**尚需在目标真机上测得的性能结果**。当前交付已通过 Kotlin 编译与 Debug APK 打包验证，但未连接真实 Android 设备或模拟器，因此不对毫秒级启动时长、帧率或内存占用作未经测量的承诺。

## 1. 性能目标与测量口径

Android 启动优化应同时观察 TTID（首帧显示）与 TTFD（用户可实际操作的完整显示），而不是只看启动动画出现的时间。[1] 对短剧类产品，首屏内容可见、发现流滑动、搜索结果展示、打开播放器和从播放器返回是最关键的用户路径。

| 指标 | 建议验收口径 | 采样方式 | 适用场景 |
| --- | --- | --- | --- |
| 冷启动 TTID | 在目标机型上连续多次取中位数与 P95；不得只记录单次最佳值 | `adb shell am start -S -W`、Logcat `Displayed`、Macrobenchmark | 安装后首次或进程被杀后的启动。 |
| 冷启动 TTFD | 首屏可操作、内容列表已达到最低可用状态时上报 | `ReportDrawn`/`reportFullyDrawn` + Macrobenchmark | 真实内容和缓存接入后。 |
| 滑动流畅度 | 发现流、首页列表、搜索结果分别记录帧时间与掉帧 | `FrameTimingMetric`、Perfetto FrameTimeline | 中低端 Android 设备。 |
| 交互延迟 | Tab 切换、进入播放器、打开选集与解锁弹窗 | Macrobenchmark 自定义用户旅程 | 高频操作路径。 |
| 包体与内存 | Release APK/AAB 大小、冷启动 PSS、滑动峰值内存 | Android Studio Profiler、`dumpsys meminfo` | 每次引入播放器/广告 SDK 后回归。 |

Android 官方将冷启动 **5 秒以上**、温启动 **2 秒以上** 和热启动 **1.5 秒以上** 视为需要关注的启动表现。[1] 这些阈值用于发现问题，而不是替代产品团队在目标机型上设定的更严格业务目标。

## 2. 已在工程中实现的措施

| 设计点 | 当前实现 | 对启动或流畅度的作用 |
| --- | --- | --- |
| 轻量系统启动页 | `core-splashscreen` + 矢量启动图标 | 在首个 Compose 帧绘制前提供系统级可见反馈，避免自定义大图或空白闪烁。 |
| 无自定义 `Application` | 未定义 `Application.onCreate()` | 避免把内容请求、广告初始化、数据库迁移等工作放入冷启动关键路径。 |
| 本地首屏数据 | `DemoDramaRepository` 同步返回小型内置剧库 | 首页与发现页不等待网络，第一帧即可组合。 |
| 单 Activity + 状态驱动路由 | `MainActivity` + `AppViewModel` + Compose | 减少 Activity 切换和重复初始化；路由变化仅重组当前窗口。 |
| 条件组合页面 | 根界面只组合当前 Tab 或当前独立页面 | 避免未显示的播放器、钱包、搜索页在启动时参与复杂组合。 |
| 长列表组件 | `LazyColumn`、`LazyRow`、`VerticalPager` | 只组合可见或临近可见内容，避免一次性绘制全部剧目。 |
| 本地小数据存储 | 搜索历史由 `SharedPreferences` 保存 | 无需启动时读取大数据库或远端账户。 |
| 发布构建优化 | `minifyEnabled` 与 `shrinkResources` 已启用 | 计划发布包经过 R8 与资源压缩处理。 |
| Profile 接入基础 | 依赖 `androidx.profileinstaller` | 为真实用户路径生成 Baseline Profile 做准备。 |

## 3. 正式服务接入时的禁止项

下列事项是短剧、广告与钱包能力接入后最容易破坏启动体验的地方。Android 官方建议将非关键资源延后加载、将耗时操作移出主线程，并用系统追踪定位主线程阻塞。[3]

| 禁止项 | 风险 | 替代方案 |
| --- | --- | --- |
| 在 `onCreate()` 同步拉取用户、剧库、远程配置或广告 | 拉长 TTID，甚至导致首屏白屏 | 先展示缓存/骨架；后台并发刷新，结果以状态流更新。 |
| 在主线程解码整屏剧集封面 | 首帧与列表滚动卡顿、GC 峰值 | 使用图片库按尺寸解码，限制内存缓存；预取相邻卡片。 |
| 启动即初始化全部广告网络和支付 SDK | Binder/IO/类加载占用关键路径 | 按广告位或钱包入口延迟初始化；记录失败但不阻塞内容浏览。 |
| 在发现流中同时创建多个播放器 | 内存、解码器和网络争用 | 仅保留当前项和相邻项的受控预加载，离屏及时释放。 |
| 让客户端单独决定提现资格或奖励 | 产生风控、幂等和欺诈风险 | 服务端签发资格、广告验证与奖励结算结果；客户端只渲染状态。 |
| 将热更新配置视为首屏硬依赖 | 配置超时将阻断启动 | 使用上次有效配置并设置短时刷新；失败时安全降级。 |

## 4. 上线前性能工作流

第一步，使用 Release 构建而不是 Debug 构建测量；Debug 的调试开销不能代表最终用户。第二步，在至少一台中低端 Android 设备和一台主流设备上，对启动、首次滚动、发现页连续滑动、搜索、进入/退出播放器分别运行多次。第三步，用 Perfetto 或 Android Studio Profiler 查看主线程中的初始组合、图片解码、广告 SDK 初始化和 Binder 调用；Android 官方建议重点调查启动中超过约 20ms 的操作。[3]

第四步，以真实用户旅程生成 Baseline Profile：启动应用、切换首页/发现/任务/我的、滚动推荐列表、搜索、打开并返回播放器、展开选集。Baseline Profile 适合覆盖启动、导航和滚动等高频路径；官方建议同时使用 Startup Profile 来优化 DEX 布局。[2] 第五步，记录每个版本的 TTID、TTFD、帧耗时、内存和 APK/AAB 体积，并以 P95 或分位数门槛阻止明显回归。

```bash
# 安装 Release 包后，示例：重复测量冷启动（需连接 Android 设备）
adb shell am force-stop com.dramaflow.app
adb shell am start -S -W \
  -n com.dramaflow.app/.MainActivity \
  -a android.intent.action.MAIN \
  -c android.intent.category.LAUNCHER

# 观察启动日志中的 TTID / TTFD
adb logcat -d | grep -E "Displayed|Fully drawn"
```

## 5. 当前验证记录

| 验证项 | 结果 | 备注 |
| --- | --- | --- |
| Gradle Wrapper | 通过 | 使用 Gradle 8.11.1。 |
| Kotlin 编译 | 通过 | 执行 `:app:compileDebugKotlin`；仅出现已知图标 API 弃用提示。 |
| Debug APK 打包 | 通过 | 执行 `:app:assembleDebug`，产物为 `app-debug.apk`。 |
| APK 安装与真机启动 | 未在本环境执行 | 交付环境未连接 Android 设备/模拟器；需在目标机型按本文件测量。 |
| 真实短剧/广告/支付联调 | 未接入 | 当前工程刻意使用本地演示仓库和模拟回调。 |

## 参考资料

[1]: https://developer.android.com/topic/performance/vitals/launch-time "Android Developers：App startup time"
[2]: https://developer.android.com/topic/performance/baselineprofiles/overview "Android Developers：Baseline Profiles overview"
[3]: https://developer.android.com/topic/performance/appstartup/analysis-optimization "Android Developers：App startup analysis and optimization"
