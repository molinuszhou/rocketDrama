# DramaFlow 阶段性交付说明

**阶段：** 等待 Pangle App ID 与短剧授权资料期间的工程完善
**构建状态：** `:app:compileDebugKotlin` 与 `:app:assembleDebug` 已通过
**Debug APK：** `app/build/outputs/apk/debug/app-debug.apk`，约 21 MB

## 本阶段完成内容

| 模块 | 已完成工作 | 现阶段效果 |
| --- | --- | --- |
| Pangle SDK | 已配置官方 Maven 仓库并接入 `com.pangle.global:ads-sdk:6.5.1.2` | 未注入账号参数时安全禁用；不会发起真实广告请求。 |
| 广告初始化 | 移出启动路径，用户同意且主动触发广告时才异步初始化 | 广告渠道不阻塞首屏和普通浏览。 |
| 隐私选择 | 首次广告隐私同意弹窗；设置页可随时重新选择 | 拒绝时不会初始化 Pangle SDK。 |
| 激励业务流 | 锁集解锁、任务双倍均改为奖励回调后结算 | 提前关闭、加载失败、奖励失败均不发放权益。 |
| 广告保护 | 单请求锁与 30 秒展示间隔 | 可防止重复点击、并发加载和短时间连续展示。 |
| 内容架构 | `ShortDramaContentGateway` + `CacheFirstDramaRepository` | 可离线返回目录；获得授权 API 后可替换远程剧集而不重写页面。 |
| 目录缓存 | 使用本地 JSON 快照保存最近可用目录 | 网络服务接入后仍可保持缓存首屏和异常回退。 |
| 构建质量 | 修正备份规则并通过 Debug Kotlin 编译与 APK 打包 | 工程可继续在 Android Studio 中同步、安装与调试。 |

## 待账户资料到位后执行

| 所需资料 | 下一步动作 | 验收标准 |
| --- | --- | --- |
| Pangle App ID 与 Rewarded Slot ID | 写入本机 `local.properties` 或 CI Secret，真机启动 | SDK 初始化成功；未同意时不初始化。 |
| 测试广告位 | 走锁集、双倍任务、关闭、无填充、频控场景 | 只有 `onUserEarnedReward` 改变本地权益。 |
| 奖励服务端接口 | 用业务动作、用户身份和幂等键请求服务端结算 | 重放或重复回调不重复发放奖励。 |
| 短剧 SDK/API 文档 | 实现 `ShortDramaContentGateway` 的远程映射 | 首页、发现、搜索、详情与播放 URL 均由授权服务返回。 |
| 真机与产品规则 | 依据地区、隐私、频控与业务要求回归 | 启动、滚动、广告展示和回退路径均满足发布门槛。 |

> **安全提示：** App ID 和广告位 ID 不应提交到源码；真实现金、金币、提现和解锁资格不应只由客户端回调确定。项目内的 `pangle.properties.example` 仅为占位说明。

## 阶段奖励模块升级

金币模块已升级为透明、可达的阶段化奖励模型。任务中心展示每日基础金币上限、已领取数、剩余数、阶段点和固定宝箱；达到 3、6、10 阶段点可领取固定 60、120、220 金币。提现资格页面明确显示最低 500 金币、至少 3 集有效观看、完成今日签到等条件，以及每项未满足原因。客户端只演示资格与进度；实际金币、兑换、宝箱和提现由服务端最终结算。

本次升级后已重新通过 `:app:compileDebugKotlin` 与 `:app:assembleDebug`。新的 Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。
