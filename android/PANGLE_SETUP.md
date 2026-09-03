# DramaFlow Pangle 激励广告接入手册

**版本：** 2026-08-12
**适用范围：** Android 原生工程 `com.dramaflow.app`

## 当前实现状态

工程已集成 Pangle Android Ads SDK，并将其调用隔离在 `ads/PangleAdsManager.kt` 中。应用只有在用户明确同意广告隐私说明，并主动点击“看广告解锁”或“看广告双倍”后，才会开始初始化 SDK 和加载激励广告。应用启动、首页渲染、短剧目录缓存与普通播放路径均不会等待广告 SDK，从而避免广告初始化拖慢冷启动。[1] [2]

| 能力 | 当前状态 | 说明 |
| --- | --- | --- |
| Pangle Maven 仓库 | 已配置 | `https://artifact.bytedance.com/repository/pangle`。 |
| Android Ads SDK | 已接入 | 固定依赖 `com.pangle.global:ads-sdk:6.5.1.2`。 |
| App ID / 广告位 | 待注入 | 通过本机配置、CI 环境变量或 Gradle 属性注入，禁止提交源码。 |
| 激励广告初始化 | 已实现 | 使用 `PAGConfig` 与 `PAGSdk.init` 异步初始化。 |
| 锁集广告解锁 | 已实现 | 只在 `onUserEarnedReward` 回调后放行剧集。 |
| 任务双倍奖励 | 已实现 | 只在 `onUserEarnedReward` 回调后对当前任务结算双倍奖励。 |
| 单请求保护 | 已实现 | 广告加载或展示期间，拒绝重复点击请求。 |
| 展示频控 | 已实现 | 同一设备最近一次成功展示后 30 秒内拒绝新的激励广告请求。 |
| 服务端奖励验签 | 待服务端 | 当前客户端已预留奖励边界；生产奖励必须由服务端最终确认。 |

> **重要：** 客户端回调只能证明 SDK 已产生一次奖励事件，不能作为现金、金币或权益的唯一可信凭据。正式环境必须将 Pangle 回调关联标识、用户 ID、业务动作和幂等键发送到服务端完成验签、风控和最终结算。[3]

## 安全注入配置

将 `pangle.properties.example` 中的占位值复制到开发机的 `local.properties`，或者由 CI 通过同名环境变量或 Gradle 属性注入。`local.properties` 已被源代码交付包排除，不能提交到版本库。

```properties
PANGLE_APP_ID=你的_Pangle_App_ID
PANGLE_REWARDED_SLOT_ID=你的_激励视频广告位_ID
```

Gradle 的取值优先级如下表。生产流水线建议仅使用 CI Secret 或受控的 Gradle 属性。

| 优先级 | 来源 | 适用场景 |
| --- | --- | --- |
| 1 | Gradle 属性 `-PPANGLE_APP_ID=...` | 受控的 CI 构建。 |
| 2 | `local.properties` | 本地调试与真机联调。 |
| 3 | 环境变量 `PANGLE_APP_ID` | 容器化或临时构建环境。 |
| 4 | 空字符串 | 安全降级：广告入口禁用，不触发 SDK 请求。 |

## 广告流程与用户体验

广告请求由用户行为触发，而不是应用启动或页面进入触发。若用户未同意、当前无有效 `Activity`、缺少 App ID、缺少广告位 ID、已有广告请求正在执行，或触发 30 秒展示间隔，界面会给出明确原因并保持原有业务状态。广告失败、提前关闭或奖励失败不会解锁剧集，也不会发放双倍奖励。

| 业务动作 | 请求入口 | 成功条件 | 失败/关闭行为 |
| --- | --- | --- | --- |
| 解锁锁集 | 播放器选集弹窗 | `onUserEarnedReward` | 保持锁定，不增加权益。 |
| 双倍任务奖励 | 任务领取弹窗 | `onUserEarnedReward` | 可返回普通领取，不结算双倍。 |
| SDK 未配置 | 任一广告入口 | 不适用 | 禁用广告按钮，提示配置缺失。 |
| 用户拒绝隐私 | 任一广告入口 | 不适用 | 不初始化 SDK，普通浏览不受影响。 |

用户可在“我的 → 设置 → 广告隐私与 Pangle”中重新选择广告隐私偏好。应用清除本地偏好后才会再次展示同意弹窗。

## 收到账户资料后的联调步骤

首先使用 Pangle 后台创建的测试 App ID 和激励广告位进行真机验证，避免将生产广告位用于开发调试。Pangle 文档要求在 SDK 初始化成功回调后再加载广告，且激励广告展示必须在主线程上调用。[2] [3]

1. 在 `local.properties` 填入测试 App ID 与激励广告位 ID。
2. 使用真实 Android 设备安装 Debug 包；广告填充、标识符与隐私行为不能只以模拟器结果为准。
3. 首次启动时选择“同意并继续”，进入锁集或任务双倍入口。
4. 验证一次完整观看、一次提前关闭、一次无填充或网络失败场景；确认只有完整奖励事件会改变 UI 权益。
5. 在 30 秒内连续触发两次请求，确认第二次被频控拦截。
6. 在设置页重新选择“暂不同意”，确认后续广告入口不调用 SDK。
7. 对接服务端奖励验签后，复测重放、重复回调、跨设备和异常网络恢复场景。

## 短剧内容服务说明

Pangle Ads SDK 和短剧版权/剧集内容服务属于不同边界。本工程当前已提供 `ShortDramaContentGateway` 契约、`CacheFirstDramaRepository` 缓存优先目录和 `replaceCatalogFromPartner()` 更新入口。收到授权内容服务的 Android SDK 或 HTTP API 后，只需实现网关映射并把远程目录传入缓存仓库，首页、发现、搜索、详情和播放器现有页面无需整体重写。

## 参考资料

[1]: https://www.pangleglobal.com/integration/integrate-pangle-sdk-for-android "Pangle：Android Integration"
[2]: https://www.pangleglobal.com/integration/android-initialize-pangle-sdk "Pangle：Initialize Pangle SDK"
[3]: https://www.pangleglobal.com/integration/android-rewarded-video-ads "Pangle：Rewarded Video Ads"
