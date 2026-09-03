# DramaFlow 双内容来源服务契约

## 目标

服务端向 Android 客户端提供**统一的内容发现、详情、分集、权益和播放授权协议**。客户端不需要了解节目来自自由剧还是 Pangle；只有在播放或解锁时，才按服务端下发的 `playbackMode` 与 `source` 执行相应流程。

## 内容来源边界

| 来源 | 服务端职责 | Android 客户端职责 | 不应由服务端承担的事项 |
| --- | --- | --- | --- |
| `FREE_DRAMA` | 管理版权元数据、地区可见性、剧集、播放 URL、短时播放令牌、免费集数与自有权益。 | 请求统一接口，使用受控播放令牌播放；展示服务端下发的权益状态。 | 不能将对象存储长期 URL、媒体源密钥或版权合同直接暴露给客户端。 |
| `PANGLE_DRAMA` | 保存平台返回/授权配置的展示元数据、地区策略、排序与可用状态；将外部内容映射为统一卡片。 | 根据 `playbackMode = PANGLE_SDK` 调用合作方内容 SDK 或其提供的播放容器。 | 不伪造 Pangle 播放 URL、广告解锁或内容可用性；未经正式内容授权时不返回真实剧集。 |

## 统一对象

### Drama

| 字段 | 说明 |
| --- | --- |
| `id` | 稳定的内部剧集 ID，例如 `free:shadow-contract`。 |
| `source` | `FREE_DRAMA` 或 `PANGLE_DRAMA`。 |
| `title`、`summary`、`coverUrl` | 用于 Feed、搜索和详情页。 |
| `genre`、`language`、`originRegions` | 用于内容筛选和本地化。 |
| `totalEpisodes`、`freeEpisodes` | 分集数与免费试看范围。 |
| `playbackMode` | `SIGNED_URL`（自由剧）或 `PANGLE_SDK`（平台内容）。 |
| `availability` | 服务端按照目标地区、合同期限、发布状态计算的可见性。 |

### Episode entitlement

统一接口返回 `FREE`、`AD_UNLOCK`、`COIN_UNLOCK`、`UNAVAILABLE` 四种状态。自由剧可由本服务端计算；Pangle 剧集应由合作方 SDK/回调确认，不将外部权益伪造为本地已解锁。

## API 约定

| 接口 | 用途 | 核心参数 |
| --- | --- | --- |
| `GET /v1/feed` | 混合内容 Feed。 | `region`、`language`、`cursor`、`limit`。 |
| `GET /v1/dramas/{dramaId}` | 统一详情与可用分集。 | `region`、`user_id`（可选）。 |
| `POST /v1/episodes/{episodeId}/playback-session` | 自由剧签发短期播放会话；Pangle 返回 SDK 路由说明。 | `region`、`user_id`、`device_id`。 |
| `POST /v1/episodes/{episodeId}/unlock` | 仅处理自由剧的广告/金币权益状态机；生产环境必须验证服务端广告回调。 | `method`、`user_id`、`proof`。 |
| `GET /v1/providers/health` | 内容源状态，仅供运营/测试后台使用。 | 无。 |

## 地区与授权原则

1. 所有 Feed 查询必须带有地区代码。服务端只向客户端返回授权地区、有效合同窗口和合规分级均满足条件的内容。
2. 自由剧的播放 URL 只通过短时播放会话返回；示例项目使用本地模拟 URL，生产环境应更换为 CDN 签名 URL 或 DRM/播放令牌。
3. Pangle 内容适配器默认为禁用，直到获得正式内容 SDK、应用配置和相应的版权/地区授权。测试期只返回结构化占位条目，不回传虚构视频地址。
4. 本地缓存只用于界面回退和历史记录。下架、地域变更或授权到期时，服务端优先返回 `UNAVAILABLE`。
