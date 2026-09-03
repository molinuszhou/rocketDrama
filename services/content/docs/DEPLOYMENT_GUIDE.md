# DramaFlow 自有剧测试部署手册

> **内容与权利提醒：** 只上传你拥有播放、封面展示、字幕、广告变现与目标地区测试权利的剧集。测试期也应记录允许地区、授权期限、变现窗口和下架联系人；正式上线前请由法务/合规完成核验。

## 先做出两个选择

自有剧上线至少包含两层：**内容 API**（剧集、地区、权益、播放会话）与**视频层**（上传、转码、私有播放、CDN）。两层不要混在 Android App 里。

| 方案 | 组成 | 适合情况 | 优点 | 需要承担的工作 |
| --- | --- | --- | --- | --- |
| A. 托管视频 + 本项目内容 API | 托管视频服务负责上传、转码、HLS/DASH 与受控播放；FastAPI 负责内容目录、地区和权益。 | 首批仅有几部测试剧，希望尽快验证播放和广告解锁。 | 少维护转码、分片、CDN 与播放安全细节。 | 选择视频服务、保存资产 ID、在服务端生成短时 Token。 |
| B. 私有对象存储 + CDN + 本项目内容 API | 自有/云端对象存储保存源视频和转码产物，CDN 通过签名 URL/Cookie 分发。 | 已有云团队，或希望更强地控制成本、转码、DRM 和基础设施。 | 存储和分发策略可完全控制。 | 负责转码、HLS 分片、私有源站、CDN 密钥、监控和异常恢复。 |

对于少量首批测试剧，建议先评估方案 A；若你已经在 AWS 或其他云上有视频/CDN 基础设施，再评估方案 B。两种方案均要求 Android 只收到短时播放 Token/URL，不能获得对象存储凭据、视频服务 API Key 或永久播放地址。[1] [2] [3]

## 推荐的首批测试流程

### 1. 准备和审核片单

根据 `docs/FIRST_TITLE_INGESTION.md` 整理首部剧的 3–5 集、封面、字幕和地区授权。复制示例片单：

```bash
cp content/owned_catalog.example.json content/owned_catalog.json
```

将 `owned_catalog.json` 内的 `owned:` ID、地区、免费集数、视频资产 ID、封面和分集信息替换为真实测试数据。该文件只保存元数据和视频资产 ID；不要保存长期播放地址或任何云服务密钥。

### 2. 设置视频层

**方案 A：** 在托管视频服务中上传视频，保持资产私有并开启签名播放。将返回的视频资产 ID 写入 `videoAssetId`。例如，Cloudflare Stream 可以要求签名 Token，并可限制访问时间和地域；Mux 的 signed 播放策略要求在受保护服务端生成 JWT。[1] [2]

**方案 B：** 将源 MP4 放进私有存储桶，转码为 HLS/DASH，再通过 CDN 分发。播放前由内容 API 检查用户地区和权益后生成签名 URL/Cookie。CloudFront 文档说明，应用应先判断用户是否有访问权，再生成带有效期的签名 URL。[3]

### 3. 在本机用容器启动 API

将以下变量保存到不提交的 `.env`：

```dotenv
ENVIRONMENT=development
CORS_ORIGINS=http://10.0.2.2:8000,http://localhost:3000
OWNED_CATALOG_PATH=/app/content/owned_catalog.json
PANGLE_CONTENT_ENABLED=false
PLAYBACK_SIGNING_KEY=replace-with-a-long-random-server-secret
```

然后执行：

```bash
docker compose up --build
curl http://127.0.0.1:8000/health
curl "http://127.0.0.1:8000/v1/feed?region=SG&limit=20"
```

成功后，Feed 应返回你在片单中设置且允许 `SG` 的 `owned:` 自有剧。Android 模拟器可使用 `http://10.0.2.2:8000` 访问开发机；真机测试需要可访问的 HTTPS 测试域名，不能长期依赖电脑的本地 IP。

### 4. 部署到测试公网环境

把 API 容器部署到你的云容器服务或已有服务器，并至少完成以下配置：

| 项目 | 测试环境最低要求 |
| --- | --- |
| 域名与 HTTPS | 以 HTTPS 暴露 API，例如 `api-test.example.com`；Android 不应在正式测试中依赖明文 HTTP。 |
| 环境变量 | 在平台的密钥/环境变量管理中配置，绝不写入 Git 或 APK。 |
| 片单 | 将 `owned_catalog.json` 放入受控文件卷或改为数据库表；生产不要依赖人工覆盖本地文件。 |
| CORS | 只允许已知管理后台/测试域名；移动端本身不依赖宽松 CORS。 |
| 健康检查 | 对 `/health` 配置平台探针；部署后调用 `/v1/providers/health`。 |
| 日志 | 记录请求 ID、来源、地区和错误码；不要记录完整播放令牌、密钥或不必要个人数据。 |
| 备份与下架 | 备份元数据；提供按剧/地区即时下架开关。 |

### 5. 连接 Android

在 Android 的数据层中将演示仓库替换为此 API：

1. Feed 请求携带用户所在地区，例如 `GET /v1/feed?region=SG`。
2. 打开详情时请求 `GET /v1/dramas/{id}?region=SG`，只展示服务端返回的集数和权益。
3. 播放前请求 `POST /v1/episodes/{id}/playback-session`。自由剧返回 `SIGNED_URL`；Pangle 剧集未来返回 `PANGLE_SDK`。
4. 广告解锁时，生产环境必须以广告服务端验证结果为准，再调用权益接口；不能以客户端按钮点击或 Android 回调单独发放权益。
5. 客户端刷新详情，确认新解锁状态和短期播放会话。

## 当前框架中需要在正式试播前替换的原型逻辑

| 当前位置 | 当前行为 | 上线前替换为 |
| --- | --- | --- |
| `FreeDramaProvider` | 内存状态与示例数据。 | PostgreSQL/MySQL 内容、地区、合同期与权益表。 |
| `playback_session` | 生成 `example.invalid` 示例 URL。 | 视频服务 Token、CDN 签名 URL/Cookie 或 DRM 播放令牌。 |
| `unlock_episode` | 接受 `TEST_REWARD_PROOF`。 | 广告服务端回调验签、幂等权益账本和审计记录。 |
| `owned_catalog.json` | 挂载的测试片单文件。 | 运营后台或内容管理数据库，保留导入/审批流程。 |
| `PangleDramaProvider` | 默认禁用。 | 获得内容 SDK、地区授权和合作方配置后实现。 |

## 请提供给我以继续落地

请上传首部测试剧的压缩包，或提供安全的私有下载/对象存储访问方式，并同时给出：目标测试国家/地区、允许的变现方式、每集是否需要广告解锁、是否已有封面与字幕、以及你倾向的方案 A 或 B。收到后我可以把真实测试片单导入框架，并把播放会话替换为你选定视频服务的受控 Token。

## References

[1]: https://developers.cloudflare.com/stream/viewing-videos/securing-your-stream/ "Cloudflare Stream: Secure your Stream"
[2]: https://www.mux.com/docs/guides/secure-video-playback "Mux: Secure video playback"
[3]: https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-signed-urls.html "Amazon CloudFront: Use signed URLs"
