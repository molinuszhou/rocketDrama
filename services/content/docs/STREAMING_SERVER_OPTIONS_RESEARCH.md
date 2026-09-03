# 点播短剧流媒体方案调研记录

## 用户场景

DramaFlow 主要面向中国内地以外市场，当前为少量自有剧测试，Android 原生播放器为主，后续需要按地区、用户权益和广告解锁控制播放。

## 核验结果

| 类型 | 代表方案 | 官方能力 | 对点播短剧的含义 |
| --- | --- | --- | --- |
| 托管视频服务 | Cloudflare Stream | 私有视频、签名 Token、HLS/DASH 播放清单、有效期及地理访问限制。 | 适合作为首批测试的默认选择：上传、转码和分发由托管服务完成，内容 API 只签发短时 Token。 |
| 对象存储 + CDN | S3 + CloudFront | 私有内容、可信密钥组、签名 URL；应用先做用户权益判断，再返回可访问的短期链接。 | 适合已有 AWS 资源或需要更深控制的团队，但需自行负责 HLS/DASH 转码、分片、安全密钥和运维。 |
| 自建实时流媒体服务器 | SRS | 支持 RTMP、WebRTC、HLS、HTTP-FLV、SRT、MPEG-DASH 等实时协议，提供 Docker/Kubernetes 部署。 | 更适合直播、实时连麦或自建实时网关；对当前“预先制作的短剧点播”并非首选，仍需另配转码、VOD 存储、CDN、签名和监控。 |

Cloudflare Stream 文档说明，视频启用 `requireSignedURLs` 后不能仅用视频 ID 公共访问；开发者可以为测试使用短期 Token，或在高并发下用签名密钥自行生成 Token，且支持 HLS/DASH 清单。[1] CloudFront 文档说明，应用先判断用户是否有权访问，再签发有效期受限的 URL；可通过自定义策略限制资源、开始/截止时间与 IP 范围。[2] SRS 官方定位为实时流媒体网关，主打 RTMP、WebRTC、HLS、HTTP-FLV、SRT 和 DASH 等协议以及容器化部署。[3]

## References

[1]: https://developers.cloudflare.com/stream/viewing-videos/securing-your-stream/ "Cloudflare Stream: Secure your Stream"
[2]: https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-signed-urls.html "Amazon CloudFront: Use signed URLs"
[3]: https://ossrs.net/lts/en-us/ "SRS: Simple Realtime Server"

## Mux 补充

Mux 的安全播放文档说明，`signed` 播放策略要求有效 JWT，JWT 应由受保护服务端生成而非公开客户端生成；签名播放可设置到期时间。该文档还提醒，原生 Android/iOS 应用不会携带浏览器 `Referer`，因此不能像 Web 一样依赖域名 Referer 校验；移动端应以服务端签发短期 Token、用户授权和其他设备/风险策略为主要边界。[4]

补充来源：
[4] https://www.mux.com/docs/guides/secure-video-playback
