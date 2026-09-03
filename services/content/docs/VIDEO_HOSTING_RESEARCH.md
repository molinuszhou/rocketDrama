# 自有剧测试视频托管与受控播放调研

**调研日期：** 2026-08-18

## 核验结论

| 路径 | 主要能力 | 适合阶段 | 服务端职责 |
| --- | --- | --- | --- |
| 托管视频服务（例如 Cloudflare Stream） | 上传、自动转码、HLS/DASH 分发、私有视频与签名 Token。 | 首批少量测试剧集，优先减少转码/CDN 运维。 | 保存视频资产 ID；完成地区和权益判断后生成/请求短时 Token。 |
| 托管视频服务（例如 Mux） | 视频资产、带签名播放策略的播放 ID、JWT 播放授权与播放数据能力。 | 需要较完整视频产品能力或后续播放质量分析。 | 私钥仅保留在服务端；按用户权益签 JWT，不向 Android 暴露 API 密钥。 |
| 对象存储 + CDN（例如 S3 + CloudFront） | 自主管理源视频、转码产物、私有分发与签名 URL。 | 已有云团队、希望控制成本/地区/CDN/DRM，或后续片库规模扩大。 | 管理对象、转码、CDN 私钥/密钥组、HLS 多分片授权和下架。 |

Cloudflare Stream 文档说明，启用 `requireSignedURLs` 后仅凭视频 ID 不能访问；可通过短期 Token 或签名密钥生成访问令牌，并支持按地理位置限制。[1] Mux 的 signed 播放策略要求由受保护的服务端生成 JWT，不能在公开客户端生成。[2] CloudFront 的签名 URL 由应用在完成用户访问权校验后创建，策略可限制有效期以及（自定义策略下的）IP 范围。[3]

## 对当前首批测试的含义

首批只有少量测试剧集时，优先选择“托管视频服务 + 本项目 FastAPI 内容服务”可减少转码和 CDN 配置；服务端仍负责剧集元数据、目标地区、用户权益和签发播放会话。若已有 AWS/云运维团队，选择对象存储 + CDN 也可行，但需要额外落实 HLS 转码、密钥、私有源站、缓存策略与运维监控。

无论选择哪一种，视频源文件必须保持私有；Android 只能获取短期播放 Token/URL，不能获得存储桶凭据、长期 URL 或视频服务 API 密钥。

## References

[1]: https://developers.cloudflare.com/stream/viewing-videos/securing-your-stream/ "Cloudflare Stream: Secure your Stream"
[2]: https://www.mux.com/docs/guides/secure-video-playback "Mux: Secure video playback"
[3]: https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-signed-urls.html "Amazon CloudFront: Use signed URLs"
