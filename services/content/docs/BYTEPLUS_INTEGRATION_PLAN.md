# BytePlus VideoOne 与 DramaFlow 融合方案

## 结论

**可以使用。** 对于主要服务中国内地以外市场的 DramaFlow，BytePlus VideoOne/VOD 是火山引擎体系中更匹配海外点播短剧场景的选择。它既可作为自有剧的云端视频处理、私有播放和短剧播放器能力，也可保留 DramaFlow 自己的 Feed、地区、钱包、广告和权益服务。

> 推荐的集成原则是：**BytePlus 管视频资产与播放器内核；DramaFlow 管内容运营、地区授权、用户权益和广告业务；Pangle 管广告展示与奖励回调。**

## 推荐架构

```text
自有视频文件
    │ 上传、转码、发布
    ▼
BytePlus VOD
    │ VID / Playlist / PlayAuthToken
    ▼
DramaFlow Content Service ── 地区、版权期、用户权益、广告验证
    │ /feed /detail /playback-session
    ▼
Android DramaFlow
  ├─ Compose 页面、统一自有剧/Pangle 剧集 Feed
  ├─ BytePlus Player SDK（播放自有剧）
  └─ Pangle Ads SDK（广告实验室、广告解锁）
```

## 为什么不直接替换整个 Android 应用

BytePlus VideoOne 的 Android 文档提供四层集成方式：直接接入完整短剧 Demo、接入短剧控件、接入 PlayerKit 控件或仅接入底层 Player SDK。[1] 当前 DramaFlow 已有 Compose UI、Pangle 广告测试中心、任务/权益和统一内容接口，因此应优先评估 **Player SDK** 或 **PlayerKit**：保留现有导航和业务 UI，只将自有剧播放内核改为 BytePlus。

只有当你愿意用 BytePlus 参考短剧页面大幅替换当前播放器/剧集体验时，才考虑导入完整 `vod-mini-drama` Demo。官方文档明确指出，完整短剧 Demo 需要替换业务 API、转换服务端数据结构并自行定制付费解锁逻辑。[1]

## 服务端映射

| DramaFlow 字段/接口 | BytePlus 对应概念 | 接入方式 |
| --- | --- | --- |
| `owned_catalog.json` 的 `videoAssetId` | VOD `VID` | 上传自有视频后记录每集 VID。 |
| 一个自有剧 | VOD Playlist | 按剧集创建 Playlist，保存 Playlist ID、标题、封面和免费集数。 |
| `GET /v1/feed` | 自有运营 Feed | DramaFlow 继续按地区、合同期和排序下发。 |
| `GET /v1/dramas/{id}` | 详情/分集/权益 | DramaFlow 返回 VID、封面、时长与是否锁集。 |
| `POST /playback-session` | PlayAuthToken | 服务端在权益校验后用 BytePlus VOD Server SDK/API 为 VID 签发短期 Token。 |
| `POST /unlock` | 解锁权益 | Pangle 服务端奖励验证成功后写权益库，再允许签发对应 VID 的 Token。 |

## Pangle 共存边界

Pangle 广告和 BytePlus VOD 是不同职责：Pangle 测试 App ID/广告位不能用作 BytePlus VOD 的 VOD 空间、VID、播放域名、Android License 或 Access Key。集成时要在同一 Android 项目中验证 Gradle 依赖、ProGuard、初始化次序和包体积；在 BytePlus 账号/License 未拿到之前，不应假设两者运行时必然兼容。

| 模块 | 负责事项 | 禁止事项 |
| --- | --- | --- |
| BytePlus VOD / Player | 上传、转码、VID、PlayAuthToken、播放、播放器体验与质量数据。 | 不直接发放 DramaFlow 金币/提现权益。 |
| DramaFlow 服务端 | 内容、地区、合同期、用户状态、权益账本、短期授权。 | 不向 Android 下发 BytePlus AK/SK 或永久播放地址。 |
| Pangle Ads | 广告加载、展示、奖励事件。 | 不以客户端广告点击/关闭事件作为服务端权益的唯一依据。 |

## 需要向 BytePlus 申请的测试资料

1. 海外账号及目标地区的 VOD/VideoOne 可用性确认。
2. VOD 空间、播放域名、测试用 Android App ID、App Name、License 文件与 SDK 版本组合。
3. Access Key/Secret Key 或服务端凭据；只存入 DramaFlow 内容 API 的密钥管理，不进入 APK 或 Git。
4. 私有加密与 `PlayAuthToken` 的测试开关、Token 失效规则、PlayAuthToken API/SDK 文档。
5. 测试片库上传配额、转码模板、封面/字幕、播放质量监控与水印/录屏保护能力。
6. 与 Pangle Ads 同项目集成的技术支持口径、最低 Android 版本、隐私声明及目标地区数据处理说明。

## 最小实施顺序

1. 先开通 BytePlus VOD，上传一部剧的 3–5 集，创建 Playlist，得到 VID/Playlist ID。
2. 将 VID 写入 `owned_catalog.json` 的 `videoAssetId`，`videoProvider` 设为 `byteplus_vod`。
3. 给 DramaFlow API 增加 BytePlus PlayAuthToken 签发器，取代目前的示例播放 URL。
4. 在 Android 使用 BytePlus Player SDK 播放自有剧，保持当前 Compose 外壳和 Pangle 广告实验室不变。
5. 在真机依次验证免费集播放、广告解锁、Token 过期重试、续播、地区拒绝、下架和播放失败回退。
6. 最后才评估完整 VideoOne 短剧模块是否值得替换现有播放页。

## References

[1]: https://docs.byteplus.com/en/docs/byteplus-vos/short-drama-videos-android-integration "BytePlus VideoOne: Short drama Android integration"
[2]: https://docs.byteplus.com/en/docs/byteplus-vos/short-drama-videos-server-integration "BytePlus VideoOne: Short drama server integration"
[3]: https://docs.byteplus.com/en/docs/byteplus-vos/overview-of-short-drama-videos "BytePlus VideoOne: Short drama solution overview"
