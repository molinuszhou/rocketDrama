# DramaFlow 海外短剧内容测试与授权方案

> **合规说明：** 本文是基于公开资料的技术与商务筛选建议，不构成正式法律意见。任何片单上线、广告投放、试运营或商店发布前，应由具备资质的法务/合规人员核验每部剧的地域、语言、期限、媒体窗口、营销素材和二次分发权利。

## 核心建议

由于 DramaFlow 的主要服务地区在**中国内地以外**，不建议以中国内地穿山甲内容输出作为主内容来源。当前 Pangle 测试 App ID 与激励广告位仍然可以保留在广告变现层；但剧集内容应从具有目标地区授权权利的国际片库、版权代理或自制内容体系取得。

**优先采取“国际版权片库 + 现有 DramaFlow UI + Pangle 广告 SDK”的组合。** 这样既不会把内容选择绑定在广告平台上，也保留未来切换广告网络、地域策略和内容供应商的能力。

## 已核验候选与建议排序

| 优先级 | 候选方 | 公开能力 | 最适合的市场/诉求 | 建议测试动作 |
| --- | --- | --- | --- | --- |
| 1 | **Unisun Group** | 公开称提供 40+ 语言、按全球或地区授权的短剧 IP，覆盖 AVOD、SVOD、FAST、TVOD、移动 App 与广播窗口。 | 东南亚、MENA、拉美、南亚、非洲及多语言测试。 | 申请 3–5 部、30–60 天、非独家、目标地区限定的测试片单与样片。 |
| 2 | **COL Group International** | 公开称可直接授权 1,000+ 部微短剧，内容来源覆盖美国、中国、欧洲、印度与亚洲，并提供 screener 请求入口。 | 英语市场或跨区域首轮内容 A/B 测试。 | 索取 screener、权利表和移动 App AVOD 试点报价。 |
| 3 | **Face Production** | 公开称为中西方制作方的授权国际发行商，可提供多片库组合、独家/非独家授权和 10+ 语种本地化。 | 希望由单一合作方处理配音、本地化与多片库采购。 | 先确认目标地区、授权窗口、配音语种及技术交付规格。 |
| 4 | **Anverio** | 公开称提供 1,000+ 可授权竖屏中国微短剧及多语种配音/字幕方案。 | 将成熟中国题材快速改编为英语、西语、泰语、日语等市场版本。 | 索取按地区可用的片单、样片、字幕和配音报价。 |
| 5 | **Xiaowu Bros.** | 公开称提供 9,800+ 微短剧，并支持权利代理、收入分成和联合运营。 | 需要大量中文短剧国际化选题或希望讨论分成模式。 | 首先核验所选剧目的地区、IAA/IAP、推广素材与数据使用权。 |

上述候选均为**商务授权与内容交付线索**，不是无需许可即可调用的公共剧集 API。公开页面中的片库规模、语言和权利表述仅用于初筛，签约前必须以对方提供的逐剧权利表为准。[1] [2] [3] [4] [5]

## 按目标区域选择

| 主要首发区域 | 优先询盘对象 | 内容语言策略 | 第一期建议 |
| --- | --- | --- | --- |
| 东南亚 | Unisun、Face Production | 印尼语、泰语、越南语、马来语、菲律宾语 | 选择 3–5 部当地语言/当地演员作品，先跑广告填充、完播率与锁集解锁。 |
| 拉美 | Unisun、COL Group、Face Production | 西班牙语、葡萄牙语 | 不要只用直译字幕；优先索取本地化配音或本地制作剧。 |
| 中东/北非 | Unisun、Face Production | 阿拉伯语（需确认方言） | 在合同中写明国家清单、宗教/内容分级要求与营销素材审查。 |
| 北美/英语市场 | COL Group、Face Production、Sea Star | 英语 | 优先要求美国/加拿大权利、广告素材授权、年龄分级和隐私合规支持。 |
| 多地区同时测试 | Unisun、COL Group | 每个地区一种主语言 | 不要在一个包中混用未明确授权的地区；使用服务端按区域下发片单。 |

## 推荐的测试授权包

第一阶段不应直接采购大包年片库。建议向 2–3 家候选分别申请一个**小规模、地区限定、非独家、可撤销的试点授权包**，并把以下事项写进邮件或意向书。

| 项目 | 建议要求 |
| --- | --- |
| 数量与期限 | 3–5 部剧，30–60 天测试窗口；单集和整剧均可验收。 |
| 地区 | 写明国家/地区清单，避免使用“全球”但实际有排除区的模糊表述。 |
| 业务窗口 | 明确 Android/iOS App、AVOD、激励广告解锁、是否允许订阅、是否允许单集付费。 |
| 语言 | 指定原声、字幕、配音、SRT、M&E 音轨以及封面/剧名本地化权利。 |
| 技术交付 | 9:16 母版或 HLS/DASH、封面、缩略图、分集元数据、字幕、时长、分级与营销物料。 |
| 播放安全 | 明确 DRM/加密、播放鉴权、URL 有效期、离线缓存及盗录防护要求。 |
| 推广权利 | 单独确认能否在 TikTok、Meta、Google、Apple Search Ads 等投放剧集片段和封面。 |
| 数据与退出 | 明确可收集的行为数据、试点结束的下架与缓存清除要求、未达到目标时的退出机制。 |

## 与现有 DramaFlow 工程的接入方式

当前工程已预留 `ShortDramaContentGateway` 与缓存优先的目录层。实际接入时，不应把合作方的 HLS/播放 URL 直接固化在 APK 中，而应由你的服务端在完成用户身份、地区和权益检查后，下发短时播放授权。

| 内容方交付 | DramaFlow 对接位置 | 后端职责 |
| --- | --- | --- |
| 剧集片单、分类、封面、简介 | `ShortDramaContentGateway` 的 Feed/Search/Detail 映射 | 按地区、语言、授权有效期过滤。 |
| 分集视频与播放鉴权 | 播放页 / Media3 或未来播放器适配层 | 生成短期播放令牌，禁止裸露长期源地址。 |
| 锁集规则 | 现有 `unlockedUntil` 与广告奖励流程 | 根据地区和合同窗口配置免费集数、广告解锁集数与频控。 |
| 下架与权益变化 | 远程刷新 + 本地回退状态 | 实时下架、移除过期封面与播放入口，保留必要观看历史但不再播放。 |
| 数据回传 | 播放、完播、解锁、广告事件 | 使用匿名/合规标识，按合同提供聚合报表。 |

## 可直接发送的商务询盘要点

请在邮件主题中注明：`Request for non-mainland China mobile microdrama pilot licence – Android AVOD test`。正文建议说明：

> 我们正在为中国内地以外市场开发 Android 竖屏短剧应用，首期考虑在 [目标国家/地区] 以 AVOD 和激励广告解锁方式测试 3–5 部短剧。请提供可授权片单、screener、逐剧地域/语言/窗口权利表、测试报价、技术交付格式、字幕/配音可用性、封面与营销素材使用权，以及是否支持 30–60 天非独家试点授权。我们不需要内容抓取或未经授权的播放源；希望通过正式授权和受控播放接口完成联调。

公开联系入口包括 Unisun 的 `licensing@unisunhk.com`、COL Group International 的 `distribution@col.com`、Xiaowu Bros. 的 `bd@xiaowubrother.com`，以及 Face Production、Anverio 网站中的商务表单。[1] [2] [3] [4] [5]

## 下一步

建议先选择一个首发区域，并同时联系 **Unisun、COL Group International 和 Face Production** 索取样片与测试条款。谁能够最快提供“逐剧地域权利表 + 可播放的测试素材 + 明确 Android AVOD 权利”，就作为第一家工程联调方。收到其资料后，我可以将 DramaFlow 的演示剧库替换为合规的远程内容 Feed，并保留现有 Pangle 激励广告解锁与任务体系。

## References

[1]: https://unisunhk.com/ "Unisun Group — Short Drama Licensing & Vertical IP Library"
[2]: https://www.col-distribution.com/ "COL Group International — Microdrama Distribution"
[3]: https://www.faceproduction.net/ "Face Production — Vertical Drama Licensing & Global Dubbing"
[4]: https://anverio.com/vertical-micro-drama/ "Anverio — Vertical Micro Drama"
[5]: https://www.xiaowubros.com/services/micro-drama-licensing "Xiaowu Bros. — Micro Drama Licensing"
