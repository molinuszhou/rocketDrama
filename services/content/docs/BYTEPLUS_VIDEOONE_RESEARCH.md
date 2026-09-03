# 火山引擎海外点播方案：BytePlus VideoOne / VOD 调研

**调研日期：** 2026-08-18

## 产品边界

面向中国内地以外市场，BytePlus VideoOne/BytePlus VOD 是与火山引擎视频点播/短剧能力对应的国际化产品线。BytePlus VideoOne 的短剧方案由云端视频处理与分发、客户端播放器和参考代码组成；公开资料明确将其定位为用于快速搭建竖屏短剧应用的端到端方案。[1]

## 已核验能力

| 能力 | 官方资料结论 | 对 DramaFlow 的作用 |
| --- | --- | --- |
| 点播与短剧方案 | 提供短剧场景云端处理/分发、播放器和参考项目。 | 可以承载自有剧的上传、转码、播放以及短剧页面能力。 |
| Android 短剧集成 | 提供短剧 Demo、短剧控件、PlayerKit 与底层 Player SDK 四种集成层级。 | 当前已有自定义 Compose 界面，适合优先评估 PlayerKit/Player SDK，或按其数据模型适配完整短剧 Demo。 |
| 自有业务 API | Android 方案要求开发者替换业务 API 并转换服务端数据结构。 | 我们已实现的 Feed、详情、分集、地区和权益 API 可作为 BytePlus 短剧数据层的来源。 |
| 安全与体验 | 方案列举私有视频加密、防录屏、低码高清转码、端到端质量监控与分集无缝切换。 | 可增强自有剧版权保护、启动速度、连续播放与质量监控。 |
| 服务端示例 | 官方短剧服务端 Demo 使用 MySQL、Redis、VOD、RTC、访问密钥，支持上传视频、创建播放列表、剧集/免费集数/用户解锁记录，并为分集生成短期 PlayAuthToken。 | 与我们自有剧服务的内容、权益和播放会话模型高度匹配；可借鉴其“剧集=播放列表、集=VID、服务端签发播放授权”的形式，而无需重写产品接口。 |

## 服务端核心流程

1. 在 BytePlus VOD 创建空间、上传视频、选择多码率在线转码模板、添加播放域名并发布视频。
2. 将每集视频的 `VID` 组成播放列表；把播放列表 ID、标题、封面、总集数和免费集数写入自有内容数据库。
3. DramaFlow API 在地区、内容授权和用户权益校验后，为允许播放的分集生成短期 `PlayAuthToken`。
4. Android 使用 BytePlus Player/短剧组件播放，不在客户端放置 Access Key、Secret Key 或长期播放地址。
5. 广告解锁后，服务端写入幂等权益记录，再刷新分集列表和播放授权。

## 需要向 BytePlus/合作方确认

* 目标国家/地区的服务可用性、数据处理与版权适用条件。
* VOD 空间、播放域名、Android SDK License、测试额度和播放器版本组合。
* 私有加密、PlayAuthToken、DRM、截图/录屏保护与下载限制的可用层级。
* 是否只采用 VOD/Player SDK，还是采用完整 VideoOne 短剧方案；后者服务端示例增加 MySQL、Redis 与额外模块依赖。
* 与现有 Pangle 广告 SDK 共存时的依赖版本、初始化顺序和数据合规要求。

## References

[1]: https://docs.byteplus.com/en/docs/byteplus-vos/overview-of-short-drama-videos "BytePlus VideoOne: Short drama solution overview"
[2]: https://docs.byteplus.com/en/docs/byteplus-vos/short-drama-videos-android-integration "BytePlus VideoOne: Short drama Android integration"
[3]: https://docs.byteplus.com/en/docs/byteplus-vos/short-drama-videos-server-integration "BytePlus VideoOne: Short drama server integration"
