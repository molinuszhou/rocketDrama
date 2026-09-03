# 首批自有剧测试数据接入清单

## 先准备的最小测试包

每部测试剧建议先只选 **3–5 集**，不要一开始上传整部片库。测试目标是验证上传、转码、受控播放、地区过滤、广告解锁、续播和下架流程。

| 项目 | 最小要求 | 说明 |
| --- | --- | --- |
| 视频文件 | 每集一个 MP4（H.264/AAC）或已有 HLS 主播放列表。 | 竖屏短剧优先 9:16；保留原始母版，不要只保留压缩后的测试文件。 |
| 分集信息 | 集号、标题、时长、文件名。 | 服务端通过稳定的内部 ID 管理，不以本地文件名作为最终业务 ID。 |
| 剧集信息 | 剧名、简介、类型、原语言、总集数、免费集数。 | 首批建议设前 1–2 集免费，后续集先用测试广告解锁。 |
| 图片 | 2:3 竖版封面、横版分享图、每集缩略图。 | 如暂时没有，可先以占位图联调，正式上架前必须更换。 |
| 地区授权 | 允许测试的国家/地区、起止日期、AVOD/广告解锁是否允许。 | 服务端会按地区下发，不能只在客户端隐藏。 |
| 权利确认 | 你拥有测试期间的播放、封面展示、字幕、推广片段和广告变现权利。 | 建议保存授权链路与下架联系人；上线前由法务核验。 |
| 字幕/音轨 | SRT/VTT、原声音轨、配音音轨（如有）。 | 先使用一种语言测试，后续再扩展多语言版本。 |

## 推荐的目录结构

```text
first-title/
├── manifest.json
├── covers/
│   ├── drama-cover-2x3.jpg
│   └── episode-001.jpg
├── video-source/
│   ├── ep-001.mp4
│   ├── ep-002.mp4
│   └── ep-003.mp4
└── subtitles/
    ├── ep-001.zh-CN.srt
    └── ep-002.zh-CN.srt
```

## `manifest.json` 模板

```json
{
  "title": "请替换为剧名",
  "externalReference": "optional-rights-or-delivery-reference",
  "language": "zh-CN",
  "genres": ["爱情"],
  "availableRegions": ["SG", "MY", "TH"],
  "availableFrom": "2026-08-18T00:00:00Z",
  "availableUntil": "2026-12-31T23:59:59Z",
  "allowedMonetization": ["AVOD", "REWARDED_UNLOCK"],
  "freeEpisodes": 2,
  "episodes": [
    {
      "index": 1,
      "title": "第 1 集",
      "videoFile": "video-source/ep-001.mp4",
      "posterFile": "covers/episode-001.jpg",
      "subtitleFiles": ["subtitles/ep-001.zh-CN.srt"]
    }
  ]
}
```

## 上传与播放顺序

1. 将源视频上传到**私有**对象存储桶；不要把桶设置为公开读。
2. 使用视频服务或转码任务输出自适应 HLS/DASH；原始 MP4 只作源文件归档。
3. 将播放清单与分片放在 CDN 后，仅允许通过服务端签名 URL、播放令牌或 DRM 获取。
4. 将剧集元数据、地区权利和媒体对象 ID 写入数据库；不要把长期 `m3u8`/MP4 地址写入 Android 包或公开 JSON。
5. Android 调用 `POST /v1/episodes/{episodeId}/playback-session`，由服务端检查地区和权益后下发短期播放会话。
6. 测试广告解锁时，先让 Android 广告实验室验证 Pangle 回调，再将生产权益处理改为广告服务端验证后写库。

## 请提供给我的资料

当你准备好后，可上传首部测试剧的压缩包，或提供私有对象存储的安全访问方式；同时告诉我首发测试国家/地区和每部剧允许的变现模式。我会据此把示例自由剧替换成真实内容清单，并补充转码、存储和播放令牌配置。
