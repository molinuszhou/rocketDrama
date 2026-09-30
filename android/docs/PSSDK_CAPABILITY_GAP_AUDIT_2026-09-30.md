# JoyeDrama PSSDK 集成能力差距审计

**审计日期：** 2026-09-30
**审计分支：** `release/formal-production`，基线提交 `6e22d4b`
**审计对象：** `com.bytedance.dramaverse:pssdk:1.8.0.0` 的公开 Android API 及 JoyeDrama 当前封装

## 审计方法与边界

本清单以两项证据交叉比对：

1. 本地 Gradle 缓存中的 **PSSDK 1.8.0.0 AAR 公共接口**（`javap` 提取）；
2. 正式分支中 `ShortDramaSdkManager`、`PangleShortDramaPlayer`、`CatalogScreens` 与播放器调用路径。

> “未接入”表示当前 JoyeDrama 没有调用该公开能力，或仅调用了接口而没有完成产品层闭环；它不代表每一项都应该立刻开发。涉及广告、用户资料、收藏同步和收入回传的功能，须先确认 Pangle/PSSDK 的账号权限、数据口径和合规要求。

官方参考：[
BytePlus 短剧 Android 集成指南](https://docs.byteplus.com/zh-CN/docs/byteplus-vos/short-drama-videos-android-integration)。

---

## 当前已接入的关键能力

| 能力 | 当前状态 | JoyeDrama 实现位置 |
|---|---|---|
| SDK 初始化、License、App ID / VOD App ID / security key | 已接入 | `ShortDramaSdkManager.initializeIfEnabled` |
| SDK 播放服务检查 | 已接入状态闭环 | `PSSDK.checkPlayStatus` → `PlaybackServiceStatus` → 播放前品牌化重试页 |
| 内容语言切换 | 已接入单语言切换 | `PSSDK.setContentLanguage` |
| 热门、最新、分类目录 | 已接入，分类元数据独立刷新 | `requestPopularDrama`、`requestNewDrama`、`requestCategoryList` |
| 首页续页 | 已接入 | 读取 `hasMore`，底部滚动后请求下一页 |
| 分类内容 | 已接入分页续载 | `requestFeedListByCategoryIds` 与分类独立 cursor |
| 关键词搜索 | 已接入防抖与分页续载 | `searchDrama` 与搜索独立 cursor |
| SDK Detail Fragment 播放 | 已接入 | `PSSDK.createDetailFragment` |
| 选集、续播、自动下一集 | 已接入 | `startPlayIndex`、`startPlayAtTimeSeconds`、`enableAutoPlayNext` |
| 分集锁定与激励广告解锁 | 已接入且由 JoyeDrama 奖励服务控制 | `isNeedBlock`、`showAdIfNeed` |
| 播放进度、完播、越界切剧 | 已接入 | `onProgressChange`、`onVideoPlayCompleted`、`onOverScroll` |
| 暂停、恢复、静音、倍速、拖动 | 已接入 | `pausePlay`、`startPlay`、`setMuted`、`setVideoSpeed`、`setCurrentPlayTimeSeconds` |
| Release 防截屏 | 已由宿主应用实现 | `FLAG_SECURE`，不是 PSSDK 专属能力 |

---

## 未接入或仅部分接入的能力清单

### P0：已于 2026-09-30 补齐（稳定性、播放体验、内容可用性）

| # | PSSDK 提供能力 | 实现结果 | JoyeDrama 落点 |
|---:|---|---|---|
| 1 | `checkPlayStatus` 的 `SUPPORT` / `NOT_SUPPORT` / `NETWORK_ERROR` | 已实现运行时状态与播放前二次校验；受限地区或网络错误展示品牌化重试 / 返回目录入口，不阻断目录浏览 | `ShortDramaSdkManager.checkPlaybackService`、`CatalogScreens.showSdkDramaPlayer` |
| 2 | `ShortPlayDetailPageListener.onPlayFailed(ErrorInfo)` | 已实现品牌化错误闭环：记录错误码、暂停宿主状态、提供重试或返回目录；返回 `true` 避免 SDK 叠加默认弹框 | `PangleShortDramaPlayer.onSdkPlaybackFailure` |
| 3 | SDK / 宿主沉浸模式回调 | 已实现双向桥接：PSSDK idle 自动沉浸、SDK进入/退出回调同步系统栏；宿主按钮同步调用 `requestStart/StopImmersiveMode` | `DetailPageConfig.enableImmersiveMode`、`PlayerScreen` |
| 4 | `setVideoClickInterceptor` | 已实现；视频点击统一映射到 JoyeDrama 暂停 / 播放逻辑。横滑切剧采用 Activity **旁路观察**而非点击拦截器，故不抢占 PSSDK 纵滑、广告与原生控件触控 | `DetailPageConfig.setVideoClickInterceptor`、`DramaAppActivity.dispatchTouchEvent` |
| 5 | `ShortPlayFragment.preLoadVideo(ActionResultListener)` | 已实现首集预热，成功或失败仅写诊断日志，不阻塞首帧或播放 | `PangleShortDramaPlayer.showSdkDramaPlayer` |
| 6 | `onVideoInfoFetched(..., VideoPlayInfo)` | 已接入诊断回调，记录分集与 SDK 视频信息摘要；后续可在确认字段语义后接入首帧/鉴权分析指标 | `PangleShortDramaPlayer.onSdkVideoInfoFetched` |
| 7 | 分类/搜索结果 `hasMore` | 已实现独立 cursor、到底自动续载、去重与失败终止；分类不会再只显示首屏，搜索不会截断首批结果 | `loadCategoryPage`、`searchPage`、`CatalogScreens` |

> **横滑结论：** `IClickInterceptor` 只提供“点击是否拦截”的接口，不能可靠识别 `MOVE` / `UP` 手势。因此项目没有错误地把横滑塞进点击拦截器；而是由 `Activity.dispatchTouchEvent` 在不消费事件的前提下旁路识别视频安全区的横向大幅滑动，并在 SDK 收到原始事件后异步调用既有 `switchDramaBySwipe`。这让左右滑屏切剧对 PSSDK 正常可用，而不会破坏 SDK 的竖滑、广告点击或选集控件。

### P1：建议纳入下一轮内容与留存迭代

| # | PSSDK 提供能力 | 当前情况 | 缺口 / 用户影响 | 建议落地方式 |
|---:|---|---|---|---|
| 8 | `requestFeedList(page, size)` 通用推荐流 | 未调用 | 当前“发现”页主要复用热门/最新和本地偏好排序，未使用供应商的通用推荐 feed | 增加“为你推荐”数据源，与现有 UI 统一模型合并；先进行 A/B 验证，不直接替换首页 |
| 9 | `requestDramaByTag` 与 `requestFeedListByCategoryIds(categoryIds, tagIds, ...)` | 仅用分类 ID；标签 ID 永远为空 | SDK 剧目的标签数据仅用于本地分组，无法请求更精准的题材/情绪/主题内容 | 将 `ShortPlay.Tag` 转为可点击筛选标签；支持单标签和分类 + 标签组合查询 |
| 10 | `setCollected(dramaId, collected, ...)` 与 `ShortPlay.isCollected` | 收藏仅保存在本地 `SharedPreferences` | 换机/重装无法同步；PSSDK 内容侧也不知道用户收藏状态 | 设计收藏主数据归属后接入：优先以 JoyeDrama 服务端为权威，或在确认 PSSDK 用户 ID 与数据归属后双向同步；避免双写冲突 |
| 11 | `setLike(dramaId, episodeId, liked, ...)`、`EpisodeData.isLiked`、点赞总数 | 未接入 | 播放器只有本地“收藏”心形，不具备分集点赞互动 | 若产品需要社交互动，可添加“点赞”并与收藏视觉区分；需确认 PSSDK 的用户身份和幂等语义 |
| 12 | `onItemSelected`、`onVideoPlayStateChanged` | 均为空实现 | 选中剧集、SDK 原生暂停/播放等状态没有进入 JoyeDrama 埋点、任务和 UI 状态机 | 将其接入统一播放状态：刷新选集状态、收藏/分享 UI、分析事件，避免只依赖进度回调推断状态 |
| 13 | `FeedListLoadResult.fromCache` | 忽略 | 无法区分缓存目录与实时目录，难以分析“内容旧”或首次加载慢的问题 | 至少记录来源；可在离线/弱网提示“展示缓存内容”，并在恢复网络后后台刷新 |
| 14 | `getContentLanguages` / `setContentLanguages` | 仅设置一个与 UI 对应的内容语言 | 内容语言没有回退策略；某个 UI 语言在供应商库库存不足时容易空白 | 启动时读取支持语言；建立“界面语言 → 首选内容语言 → 回退语言”的可配置映射，并在 UI 中说明内容语言差异 |

### P2：商业化、品牌化和运维增强（需先确认供应商协议/产品策略）

| # | PSSDK 提供能力 | 当前情况 | 缺口 / 用户影响 | 建议落地方式 |
|---:|---|---|---|---|
| 15 | `reportRevenueInfo(RevenueInfo)` | 未调用 | PSSDK 未收到 TopOn/MAX 等广告收入反馈；若供应商用该数据优化分发或商业化归因，则数据链路不完整 | 确认 PSSDK 对 `RevenueType`、美元收入、广告格式和 `aboutUnlock` 的口径后，从已验证的 TopOn impression/revenue 回调单次上报，避免重复记账 |
| 16 | `AdCustomProvider` / `DrawAdProvider` / `adCustomProvider(...)` | 未接入 | 不能把自有原生广告以 SDK 支持的位置嵌入详情/信息流 | 仅在明确需要 SDK 内 Draw 广告时实施；复用现有 TopOn 原生广告链路，并遵循插入频率、关闭和隐私规范 |
| 17 | 自定义控制视图：`IControlView`、`IControlLoadingView`、`IControlProgressBar`、`IControlStatusView`、`onObtainPlayerControlViews` | 返回空列表；目前采用宿主覆盖层 | 目前可用，但宿主覆盖层与 SDK 内建层可能出现层级、点击或安全区竞争 | 若继续采用宿主 UI，至少用 DetailPageConfig 显式隐藏/调整重复的 SDK 控件；如需深度原生融合，迁移为 PSSDK 控制视图实现 |
| 18 | DetailPageConfig 的品牌与显示配置：默认封面、封面缩放、启动图标、文本颜色/字号/显隐、进度条显隐与底部边距、视频显示模式 | 只配置隐藏 SDK 顶栏、起播集/时间、自动下一集、隐藏额外底部视图 | 当前混合 UI 下可能有 PSSDK 原生控件与 JoyeDrama 控件重叠，且加载/封面品牌不一致 | 明确“SDK 负责视频，JoyeDrama 负责 UI”的边界后，统一关闭或重设 SDK 原生进度、标题、底部控件、封面和显示模式 |
| 19 | `playSingleItem` | 未使用 | 搜索/预览落地时只能进入连续剧播放模式 | 若做“单集试看”“广告素材预览”或外部深链，可启用；常规追剧无需优先开发 |
| 20 | `setResolution` | 未调用 | 无手动清晰度档位，当前依赖 SDK 默认自适应策略 | 先确认 PSSDK 的可用分辨率与默认 ABR；如用户反馈弱网耗流量，再提供“省流/高清”设置 |
| 21 | `clearLocalCache`、`getVersion` | 未接入产品或诊断界面 | 发生地区、缓存或版本兼容问题时，客服/测试缺少受控自助诊断 | 在 Debug / 内部诊断页展示 SDK 版本、服务状态和缓存清理；正式用户设置中不要直接暴露危险清缓存按钮 |
| 22 | `setEligibleAudience(boolean)` | 当前初始化后固定设置为 `true` | 已调用但未形成年龄/受众资格业务链路；在有未成年人、地域或内容分级要求时存在合规风险 | 仅在拥有明确年龄门槛、地区与隐私同意状态后设置；将资格来源封装在合规模块，而非固定常量 |
| 23 | Fragment 生命周期 `stopPlay()` 与 `startPlayIndexAndTimeSeconds(...)` | 使用暂停/恢复与 `startPlayIndex`；未显式调用停止和“集 + 时间”跳转 | 通常由 Fragment 生命周期处理，但在快速切页、销毁或深链恢复时资源释放与精准恢复缺少显式策略 | 审计 `releasePlayer` 和 Activity 销毁时机；若 SDK 不自动停止则显式 `stopPlay`，深链/恢复场景优先使用“集 + 时间”原子调用 |

---

## 推荐实施顺序

### 第一批：先解决当前联调与播放体验问题

1. **已完成（本次）**：服务状态闭环、播放失败重试、沉浸双向桥接、视频点击拦截、首集预加载、视频信息诊断、分类和搜索分页。
2. **下一步建议**：为预加载与视频信息记录增加结构化首帧 / 鉴权 / 失败率指标；仅在供应商确认字段稳定后使用视频信息驱动业务判断。

### 第二批：增强内容发现和留存

5. 接入通用推荐 Feed、标签筛选与内容语言回退。
6. 决定收藏主数据归属后，再接入 PSSDK 收藏同步；不要把当前本地收藏直接替换为供应商收藏。
7. 接入 SDK 的状态/选中回调，统一观看进度、奖励、埋点与播放器 UI。

### 第三批：商业化与深度品牌化

8. 与 Pangle 确认收入回传协议后，接入 `reportRevenueInfo`。
9. 评估 Draw 广告和 PSSDK 原生控制视图；若采用，先制定 UI 边界和广告频率策略。
10. 对分辨率、缓存清理、内容资格等能力做配置化与 Debug 诊断支持。

---

## 本次结论

当前 PSSDK 集成已经覆盖了 **核心内容获取、搜索、分类、播放、续播、解锁、进度回传、完播奖励触发和基础播放控制**，不是“只接了一个播放器”。

它仍是一个以宿主 UI 覆盖 PSSDK 详情页的混合集成，但本次已经完成**播放服务状态、SDK 原生沉浸/点击控制、首集预加载、错误闭环与分类/搜索续页**。接下来优先级应转向**收藏同步、标签发现、通用推荐 Feed、内容语言回退、状态埋点与收入回传**；这些能力需要先确认供应商账号权限、用户身份和收入数据口径。
