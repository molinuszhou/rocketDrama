# 短剧内容接入调研记录

**调研日期：** 2026-08-14

## 已核验候选

| 方案 | 内容是否由平台提供 | 公开能力 | 当前判断 |
| --- | --- | --- | --- |
| 穿山甲内容输出（短剧） | 是，平台将短剧以 SDK 形式输出 | 完全封装模式；非完全封装模式可拉取短剧信息并调用播放/回调接口 | 与 DramaFlow 的“自有首页 + 自定义广告解锁 + 竖屏短剧”目标最贴合，优先推荐申请。 |
| BytePlus VideoOne Short Drama | 否，公开页描述的是播放器、后端、参考代码与视频处理能力 | Android 示例、短剧播放器、上下滑播放、锁集客户端逻辑 | 可用于接入用户自有或已授权内容，不能替代版权内容来源。 |
| Sea Star Licensing & Content Partnerships | 是，提供微短剧/竖屏剧版权合作 | 按地区、非独家/独家、联合分发与格式销售洽谈 | 可用于英文或国际内容采购；未发现公开自助 API，需要商务授权后确认交付形式。 |

## 关键事实

穿山甲官方“内容输出-短剧产品介绍”明确称短剧内容以 SDK 对外输出，且广告 SDK 为必接项；其非完全封装模式公开描述了短剧信息接口、播放回调接口与播放器能力。页面同时列出应用主体可能需准备的增值电信业务许可证、网络文化经营许可证、软著、广播电视节目制作经营许可证、短剧推广授权等资质，并明确内容仅允许激励广告解锁，不允许付费解锁。

BytePlus VideoOne 的短剧方案聚焦视频处理、分发、播放、加密、播放器和参考工程。公开页面没有声明向开发者提供可转授权的剧集库，因此应把它视为技术基础设施，而不是剧集版权来源。

Sea Star 表示可为 AVOD、SVOD、VOD、FAST、广播与数字化应用提供全球/地区范围的短剧授权和版权合作；具体片库、价格、格式和交付 API 需向其商务团队确认。

## 来源

[1] https://www.csjplatform.com/supportcenter/27862
[2] https://docs.byteplus.com/en/docs/byteplus-vos/overview-of-short-drama-videos
[3] https://seastarfilm.com/licensing-content

## 技术接入核验补充

穿山甲“接入前必读”页面说明，短剧内容 SDK 支持聚合页（完全封装）、自建聚合页（SDK 客户端与 API 接口）、滑滑流和预览卡片。Android Demo 接入需使用穿山甲应用 ID、在内容输出后台录入真实包名、上传所需资质后下载 JSON 配置文件并放入 `assets`。官方还明确要求先成功初始化广告 SDK，再初始化短剧内容 SDK；短剧内容需实时请求，不建议缓存；解锁广告必须包含穿山甲广告，否则内容可能被永久停止返回。

BytePlus Android 文档进一步确认其短剧示例由开发者替换业务 API、转换数据结构、提供剧集信息/剧集播放信息/解锁信息和播放鉴权令牌；其演示不包含第三方支付 SDK。这支持“BytePlus 是播放器与短剧产品技术底座，不是可转授权剧集片库”的判断。

补充来源：
[4] https://www.csjplatform.com/supportcenter/28549
[5] https://docs.byteplus.com/en/docs/byteplus-vos/short-drama-videos-android-integration
