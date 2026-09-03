# DramaFlow 自有剧点播流媒体选型建议

> **使用边界：** 本文针对预制短剧的点播（VOD），不是直播。视频、封面、字幕和播放地区只能在你拥有相应测试/分发权利的前提下接入。

## 先区分“点播视频平台”与“实时流媒体服务器”

DramaFlow 当前播放的是预先制作的短剧集。核心需要是：上传、转码为自适应 HLS/DASH、私有播放、短时 Token、全球 CDN、地区控制和 Android 播放兼容。因此，首批测试不一定需要自建“流媒体服务器”；托管视频服务或对象存储加 CDN 通常更符合点播需求。

| 方案 | 典型实现 | 适合的阶段 | 视频安全与 Android 对接 | 运维负担 |
| --- | --- | --- | --- | --- |
| **托管视频服务** | Cloudflare Stream 或 Mux + DramaFlow 内容 API | 几部测试剧、需要快速验收上传/播放/广告解锁。 | 内容 API 在确认地区和权益后签发短期 Token；Android Media3 请求 HLS/DASH 清单。 | 低。平台承担转码、分发和大部分视频基础设施。 |
| **对象存储 + CDN** | S3/对象存储 + 转码服务 + CloudFront/其他 CDN + 内容 API | 已有云团队、需要更强控制、准备扩展片库。 | 内容 API 生成 CDN 签名 URL/Cookie；Android 播放 HLS/DASH。 | 中至高。需要负责转码、密钥、CDN、源站、日志与告警。 |
| **自建实时流媒体服务器** | SRS + 私有存储/转码/CDN + 内容 API | 未来增加直播、实时连麦、RTMP/SRT/WebRTC 接入时。 | 仍需额外实现 VOD 资产管理、签名、地区/权益、CDN 分发。 | 高。需要常驻运维、扩缩容、补丁、监控和抗滥用。 |

## 方案 A：托管视频服务

Cloudflare Stream 提供私有视频、签名 Token、HLS/DASH 清单和地域限制能力；其文档将短期 Token 明确用于低量或测试场景，高并发时可改用服务端签名密钥生成 Token。[1] Mux 也支持 signed 播放策略，但签名 JWT 必须由受保护服务端生成，不能放在 Android 客户端。[2]

**接入路径：** 上传源视频并启用私有/签名播放 → 将视频资产 ID 写入 `owned_catalog.json` 的 `videoAssetId` → DramaFlow 的 `/playback-session` 在地区与权益校验后生成供应商 Token → Android 用 Media3 播放得到的 HLS/DASH URL。

这条路径的优点是**最快建立安全的点播验证链路**，不足是会依赖托管平台的产品能力与计费模型。

## 方案 B：对象存储 + CDN

CloudFront 的官方流程是：应用先判断用户是否有权访问，生成包含限制条件和有效期的签名 URL，再由 CDN 验证签名并分发私有内容。[3] 自建架构通常还要加上视频转码输出 HLS/DASH 多码率分片、私有存储桶、密钥组/签名服务、内容下架、日志和成本监控。

这条路径的优点是**控制力高，适合成熟后的片库与运营需求**；不足是首批测试也需要处理较多视频基础设施细节。对于 HLS，多分片请求的授权策略要事先设计；不要只对首个 `m3u8` 签名，而忽略子清单、密钥和 ts/fMP4 分片访问。

## 方案 C：自建 SRS

SRS 是面向实时媒体的高效服务器，支持 RTMP、WebRTC、HLS、HTTP-FLV、SRT 和 DASH，并提供 Docker/Kubernetes 部署能力。[4] 它适合未来如果 DramaFlow 要加入**直播、创作者实时推流、实时审核或互动内容**。

但对于当前短剧点播，SRS 不是“上传几部 MP4 就安全点播”的完整方案。仍需额外补齐 VOD 转码、媒体资产元数据、私有对象存储、全球 CDN、分片访问令牌、内容下架、播放质量监控和 24×7 运维。因此应只在有明确实时媒体需求、具备运维能力并愿意维护基础设施时考虑。

## Android 原生播放器关键规则

1. 使用 AndroidX Media3/ExoPlayer 播放 HLS 或 DASH，不在客户端保存视频平台私钥、对象存储密钥或永久 URL。
2. 每次开始播放前调用 DramaFlow `/v1/episodes/{episodeId}/playback-session`；服务端完成用户、地区、授权期限和权益检查。
3. 服务端返回短时播放会话，而不是视频源文件地址；播放时间必须覆盖单集时长并考虑网络重试。
4. 原生 Android 不可靠地携带浏览器 `Referer`，不要把域名 Referer 校验当作唯一的防盗播措施；应组合短时 Token、用户授权、地区策略和风险控制。[2]
5. 需要即时下架时，先从内容 API Feed/详情中移除，再撤销/停止签发播放 Token；长期已签发 Token 的有效期应保持短。

## 选择前请确认

| 需要你决定的信息 | 为什么重要 |
| --- | --- |
| 首发测试国家/地区 | 决定 CDN 覆盖、视频服务可用性、地区限制和版权配置。 |
| 测试剧数、集数、单集平均大小 | 决定转码量、存储和分发规模。 |
| 是否只做点播，还是 6–12 个月内要做直播 | 决定是否需要提前引入 SRS 等实时服务器。 |
| 是否已有 AWS/Cloudflare/Mux 或其他云账号 | 决定接入成本和部署复杂度。 |
| 是否要求 DRM、下载、离线观看或水印 | 决定播放器、视频服务和版权保护能力。 |

## 当前可执行的最小动作

1. 在两种点播路径中选择一个进行测试：**托管视频服务**或**对象存储 + CDN**。
2. 上传第一部剧的 3–5 集并保持视频私有。
3. 用 `content/owned_catalog.example.json` 填写真实资产 ID、地区和集数。
4. 将供应商签名密钥只存入内容 API 的服务端环境变量。
5. 我再将 `playback-session` 中的原型 URL 适配为你选定供应商的真实短期 Token。

## References

[1]: https://developers.cloudflare.com/stream/viewing-videos/securing-your-stream/ "Cloudflare Stream: Secure your Stream"
[2]: https://www.mux.com/docs/guides/secure-video-playback "Mux: Secure video playback"
[3]: https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-signed-urls.html "Amazon CloudFront: Use signed URLs"
[4]: https://ossrs.net/lts/en-us/ "SRS: Simple Realtime Server"
