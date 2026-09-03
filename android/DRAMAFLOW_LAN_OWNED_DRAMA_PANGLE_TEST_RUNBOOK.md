# DramaFlow 局域网自有短剧与 Pangle 直连测试手册

**文档版本：1.0.0**
**适用构建：Debug 测试包**
**应用包名：`com.dramaflow.app`**

## 1. 本版范围

本版 Android 应用已从外部短剧 PSSDK 切换到自有短剧内容服务。应用通过局域网访问 FastAPI 目录、详情、分集解锁和短时播放会话，并使用 AndroidX Media3 播放服务端签发的 MP4 地址。Dramaverse、BytePlus VOD 和相关 License/Key 均不参与编译或运行；源码仅保留空白配置项，等待后续取得正式参数后再接入。

广告继续使用此前已经验证的 **Pangle Waterfall 直连测试配置**。运行参数只写入本地 `local.properties`，不写入源码、示例文件或源码压缩包。Pangle 官方要求通过 Maven 仓库引入 SDK，并以 App ID 初始化 SDK；Pangle SDK 还会合并广告标识相关权限，因此上架前需在 Play Console 的数据安全表单和广告声明中如实披露。[2] [3]

| 模块 | 当前测试实现 | 上线前处理 |
|---|---|---|
| 应用身份 | `com.dramaflow.app`，使用匹配的新 Firebase 配置 | 使用正式签名并核对 Firebase SHA 指纹 |
| 短剧目录 | FastAPI `GET /v1/feed` | 接入正式内容数据库与运营后台 |
| 视频播放 | 局域网本地 MP4 + 短时播放 URL + Media3 | 切换 HTTPS、CDN/VOD、正式鉴权和地域控制 |
| 锁集解锁 | 完成 Pangle 激励广告后调用内容服务解锁接口 | 改为服务端广告回调/验签，不信任客户端证明 |
| 奖励 | FastAPI 动态规则、SQLite 测试账本 | 迁移生产数据库、风控与审计 |
| 外部短剧 SDK | 已禁用，无 License、无 Key、无依赖 | 参数与授权齐备后作为独立内容适配器接回 |

## 2. 启动局域网内容服务

将测试视频放入内容服务的 `media` 目录，并以仓库中的 `content/owned_catalog.local-media.example.json` 为模板创建实际片单。片单里的 `videoAssetId` 必须是相对于 `LOCAL_MEDIA_ROOT` 的安全相对路径。

```bash
cd dramaflow_content_server
cp .env.example .env
cp content/owned_catalog.local-media.example.json content/owned_catalog.local-media.json
mkdir -p media/owned/local-test-drama
```

将已获授权的 MP4 文件复制到片单声明的路径，然后配置 `.env`：

```dotenv
ENVIRONMENT=development
PANGLE_CONTENT_ENABLED=false
LOCAL_MEDIA_ENABLED=true
LOCAL_MEDIA_ROOT=/absolute/path/to/dramaflow_content_server/media
OWNED_CATALOG_PATH=/absolute/path/to/dramaflow_content_server/content/owned_catalog.local-media.json
```

启动服务时必须监听全部局域网接口：

```bash
set -a
source .env
set +a
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

在开发电脑上确认服务正常：

```bash
curl http://127.0.0.1:8000/health
curl "http://127.0.0.1:8000/v1/feed?region=SG"
```

## 3. 在手机中配置内容服务地址

手机和开发电脑必须处于同一可信局域网。正常进入应用后，可在底部 **“我的”** 页面点击 **“局域网内容服务”** 填写地址；如果目录初次加载失败，错误页会直接显示 **“设置服务器地址”**，无需先进入“我的”。

```text
http://<开发电脑的局域网 IP>:8000
```

例如开发电脑地址为 `192.168.1.25` 时，填写 `http://192.168.1.25:8000`。保存后应用会立即重新加载目录，不需要重新打包 APK。Android 模拟器仍可使用默认地址 `http://10.0.2.2:8000`。

Android 9（API 28）起默认禁止明文 HTTP。本项目只在 Debug 资源中允许明文流量，Release 默认关闭，以防局域网测试设置进入上架包。[1] 明文 HTTP 可被同一网络中的第三方监听或篡改，因此不得用于互联网生产环境、登录凭据或支付信息。[4]

## 4. 功能验收

| 顺序 | 操作 | 预期结果 |
|---|---|---|
| 1 | 冷启动应用 | Logo 加载页后立即请求 Pangle 开屏；无填充或失败时继续进入签到流程 |
| 2 | 关闭/跳过开屏 | 展示深色七日连续签到弹窗；内容服务不可达也不虚假发放金币 |
| 3 | 进入首页 | 从局域网 `/v1/feed` 加载自有剧，卡片不区分内容来源 |
| 4 | 点击剧集 | 从 `/v1/dramas/{id}` 加载真实简介和分集列表，Media3 播放第 1 集 |
| 5 | 单击视频 | 画面、声音和进度同时暂停；再次单击同步恢复 |
| 6 | 展开简介 | 长文本可在简介区域内滚动查看 |
| 7 | 上滑视频 | 进入下一集；锁集时自动请求激励广告 |
| 8 | 完成激励广告 | 应用调用 `/v1/episodes/{id}/unlock`，随后重新获取播放会话并播放 |
| 9 | 完播一集 | 在自然断点请求插屏；广告关闭、跳过或失败后继续下一集 |
| 10 | 进入奖励页领取 | 所有领取入口先请求激励广告，再由服务端尝试结算 |

## 5. 常见故障

| 现象 | 排查方式 |
|---|---|
| 目录加载失败 | 在错误页点击“设置服务器地址”填入 Windows 电脑的 IPv4 地址；再核对防火墙、服务是否使用 `--host 0.0.0.0`，并在手机浏览器访问 `/health` |
| 图片不显示 | 封面 URL 必须能被手机访问；不要使用电脑的 `localhost` 路径 |
| 视频返回 404 | 核对 `LOCAL_MEDIA_ROOT`、片单 `videoAssetId` 和实际文件路径 |
| 视频返回 402 | 当前分集锁定；完整观看激励广告并等待内容服务解锁后重试 |
| 视频返回 403 | 短时播放令牌已过期或服务已重启；重新进入该集以获取新播放会话 |
| 开屏/激励/插屏无广告 | 核对网络、Pangle 测试包参数、隐私同意和广告填充；测试广告也不保证每次请求均有填充 |
| 覆盖安装失败 | 旧 `com.feather.joyelledrama` 与新 `com.dramaflow.app` 是不同包；如签名冲突，卸载同包名旧测试版后重装 |

## 6. 上架前阻断项

> 本 Debug 包用于局域网和测试广告联调，不能直接作为商店上架包。

上线前必须将内容地址改为 HTTPS，关闭本地 MP4 模式和客户端测试解锁证明，启用真实用户鉴权与服务端广告验签；还应配置正式签名、Firebase SHA 指纹、隐私政策、Pangle/广告标识披露、内容版权和地区授权。Pangle 官方集成页明确要求开发者在 Google Play Console 披露应用的隐私和安全实践。[2]

外部短剧 SDK 后续重新接入时，应将 License 和 Security Key 放入受控 CI Secret 或不提交的本地文件，禁止写入 Git、公开源码包和测试文档。

## References

[1] [Android Developers — Network security configuration](https://developer.android.com/privacy-and-security/security-config)
[2] [Pangle — Integrate Pangle SDK for Android](https://www.pangleglobal.com/integration/integrate-pangle-sdk-for-android)
[3] [Pangle — Initialize Pangle SDK](https://www.pangleglobal.com/integration/android-initialize-pangle-sdk)
[4] [Android Developers — Cleartext communications](https://developer.android.com/privacy-and-security/risks/cleartext-communications)
