# Dramaverse 合作方接入信息记录

**记录日期：2026-08-20**
**信息来源：用户提供的合作方 Lark 接入文档与参数表**

## 已确认的 SDK 与配置类别

| 类型 | 已确认内容 | 处理原则 |
|---|---|---|
| 短剧 SDK | 合作方文档提供了 `com.bytedance.dramaverse:pssdk` 的 Gradle 依赖坐标。 | 在接入分支中使用依赖版本占位符；实际版本须根据合作方文档锁定。 |
| BytePlus 播放器 | 合作方文档说明使用 BytePlus VOD Android Player；公开文档显示初始化、短时播放令牌和播放器生命周期路径。 | VOD App ID 与 License 仅通过本机 `local.properties` 或 CI Secret 注入，绝不写入 Git。 |
| 短剧内容 App ID | 参数表已提供。 | 仅通过本机 `local.properties` 或 CI Secret 注入 BuildConfig。 |
| VOD App ID | 参数表已提供。 | 仅通过本机 `local.properties` 或 CI Secret 注入 BuildConfig。 |
| VOD License 文件 | 参数表列出了一个 `.lic` 文件名，但文件尚未上传。 | 取得后放入 Android `app/src/main/assets/license/`；由 `.gitignore` 排除，并以 `assets:///license/<file>.lic` 形式引用。 |
| Firebase 配置 | 参数表列出了一个 Google Services JSON 文件名，但文件尚未上传。 | 取得后放入 Android `app/` 模块；禁止公开其中的项目配置。 |
| TopOn 广告 | 参数表已提供 1 个应用 ID，以及开屏、激励、插屏 3 个广告位 ID。 | 仅从 `local.properties` / CI Secret 注入；需实现单次加载、展示频控、失败降级和服务端权益核验。 |
| AppsFlyer 参数 | 参数表已提供归因配置。 | 不属于本轮核心播放/广告接入；除非用户明确要求，否则不启用。 |
| 安全密钥 | 参数表中含合作方安全配置。 | 不写入源码、日志、交付文档或 APK；只有在服务端接口确有要求时才使用，并优先放在内容服务环境变量中。 |

## 接入文档中的运行限制

1. 测试短剧播放需通过 SDK 支持的国家或地区网络节点，否则可能发生播放失败或 `10011` 错误。文档列出的支持地区包括中国台湾、泰国、越南、马来西亚、印度尼西亚、菲律宾、新加坡、日本、韩国、巴西、澳大利亚、新西兰、部分拉美国家及中东、非洲和其他地区。
2. Demo 仅用于预览，完整剧集列表需要使用合作方发放的正式应用信息请求。
3. 合作方更新日志说明短剧 SDK 支持 Gradle 引入，并新增音频语种、分类热门剧集及语言元数据等能力；其最新日志还记录了播放器版本升级。
4. 合作方文档要求针对 16 KB 设备支持使用较新播放器版本；实现时应锁定合作方批准的版本组合。

## BytePlus 公开文档可验证的实现要点

1. VOD 播放器须在 `Application` 初始化，并以 assets 中的 License URI 作为初始化参数。
2. VOD 视频应由应用服务端下发视频 ID 与临时播放令牌；客户端不保存长期播放凭据。
3. 离开播放器页面时必须异步释放播放器资源，以释放解码器、内存和网络资源。
4. BytePlus 的短剧 Demo 适合替换整套播放器/页面架构；现有 Compose 应用更适宜先采用合作方短剧 SDK 的内容 API 与嵌入式详情播放器，避免整体迁移至 XML Demo。

## 当前缺失的合作方资料

外部 Lark 文档正文通过公开访问仅显示更新日志和地区限制，未能取得完整的快速入门章节，包括 Maven 仓库、初始化参数、请求签名、完整 API 调用、ProGuard 规则和 PSSDK 播放器创建方式。为完成真实集成，需要从合作方取得可下载的完整文档、SDK AAR/Demo，或有完整章节访问权限的文档链接。

在实现过程中不得将任何 App ID、广告位、归因 Key、License、Firebase 配置、安全密钥或令牌写入 Git。测试值只保存在本机 `local.properties` 或受控 CI Secret。

## 参考

[1] [Dramaverse 短剧安卓 SDK 接入文档（合作方提供）](https://bytedance.sg.larkoffice.com/docx/Syexd36GCoDkGqxJa2wcaQchnQf?open_in_browser=true)

[2] [BytePlus Short drama videos — Android integration](https://docs.byteplus.com/id/docs/byteplus-vos/short-drama-videos-android-integration)

[3] [BytePlus Android Player SDK: Quickstart](https://docs.byteplus.com/en/docs/byteplus-vod/docs-getting-started-with-the-android-player-sdk)
