# PSSDK 播放能力核验

> 核验日期：2026-09-30。适用实现：JoyeDrama Android 正式分支所依赖的 `pssdk-1.8.0.0`。

## 结论

- **不能将 Pangle/PSSDK 短剧切换为自有 ExoPlayer 播放。** 内容 SDK 不返回短剧视频 URL；媒体播放由 SDK 封装，客户端不能代理或保存 URL。
- 但可继续使用 **PSSDK 的 `ShortPlayFragment` 作为实际播放器**，并调用其公开接口做宿主控制：指定集数、指定起播时间/进度、暂停/恢复、静音、倍速，以及在详情页叠加自定义控件。
- 因此正式包应采用“**PSSDK 媒体层 + JoyeDrama 控制层/奖励层**”的方式，而不是使用本地 Media3/ExoPlayer 媒体层。

## 官方依据

1. [内容 SDK 常见问题：短剧播放问题](https://www.csjplatform.com/supportcenter/28314)
   - 明确说明：SDK 已封装播放能力，**不返回短剧播放 URL**。
   - 同页说明：底部进度条不支持隐藏或自定义样式；播放/暂停不应假设有通用独立接口。
2. [短剧功能配置](https://www.csjplatform.com/supportcenter/28550)
   - Android 详情页支持默认或自定义选集面板；自定义选集调用 SDK 的集数切换接口。
   - 支持播放器回调和自定义 View，用于宿主绘制额外 UI。

## 已核验的本地 SDK 1.8.0.0 API

通过本地 API jar 的公开签名确认，`ShortPlayFragment` 提供：

- `startPlayIndex(int)` / `startPlayIndexAndTimeSeconds(int, int)`
- `setCurrentPlayTimeSeconds(int)`
- `startPlay()` / `pausePlay()` / `stopPlay()`
- `setVideoSpeed(float)` / `setMuted(boolean)`
- `setBottomExtraViewContent(View, BottomViewType)`

`PSSDK.DetailPageConfig.Builder` 同时公开起播集数、起播时间、进度条边距、底部扩展 View、自动下一集、沉浸模式等配置。

## 实施限制

- 不读取、抓取、缓存或转发 PSSDK 的视频 URL。
- 不直接套用仅适用于本地 ExoPlayer 的手势播放层，以免拦截 PSSDK 自己的视频手势。
- 允许在 PSSDK 详情页之上增加不拦截媒体手势的宿主控件，并将选集、倍速、进度拖动映射回公开的 Fragment API。
