# DramaFlow 包名与内容源迁移构建报告

**构建日期：2026-09-02**
**构建类型：Debug**
**包名：`com.dramaflow.app`**
**版本：`1.0.0`（versionCode 1）**

## 迁移结果

| 项目 | 最终状态 |
|---|---|
| Android namespace | `com.dramaflow.app` |
| Android applicationId | `com.dramaflow.app` |
| Firebase | 已替换为用户提供且 `package_name=com.dramaflow.app` 的配置 |
| 内容源 | 局域网 FastAPI 自有短剧；`GET /v1/feed`、详情、解锁和短时播放会话 |
| 播放器 | AndroidX Media3 / ExoPlayer |
| 外部短剧 PSSDK | 已从依赖和运行代码移除 |
| Dramaverse/BytePlus 凭据 | 已从 BuildConfig 注入移除；示例文件只保留空白预留字段 |
| VOD License | 源码树和 APK 均未发现 `.lic` 或 `assets/license` 条目 |
| 广告 | Pangle 6.5.1.2 Waterfall 直连；开屏、激励和插屏 |
| TopOn | 当前运行链路不启用；仅在配置示例中保留空白未来占位 |
| 局域网 HTTP | Debug 开启；Release 资源默认关闭 |

## 验证结果

Android `assembleDebug` 构建通过。通过 `aapt dump badging` 验证 APK 包名为 `com.dramaflow.app`、minSdk 26、targetSdk 35。APK 禁止项扫描未发现 Dramaverse、ShortPlay、BytePlus、ttvideo 或 License 文件。

内容服务以下冒烟测试全部通过：

| 测试 | 结果 |
|---|---|
| `tests/catalog_loader_smoke.py` | 通过 |
| `tests/local_media_smoke.py` | 通过 |
| `tests/rewards_smoke.py` | 通过 |
| `tests/smoke.py` | 通过 |

源码安全扫描确认，Pangle 测试参数只存在于未交付的 `local.properties`，Firebase 配置只用于受控本地构建；源码压缩包不包含两者。外部短剧 SDK 的实际 License、Security Key 和 VOD App ID 均不存在。

## APK 校验

```text
SHA-256: ebc2c735cfb7344cd7d7b11f0ee5d472abedb75dd90e510a37952955dc49b955
```

## 已知测试边界

局域网自有剧需要在“我的—局域网内容服务”填写开发电脑 IP。模拟器默认使用 `http://10.0.2.2:8000`；真机必须使用电脑的局域网地址。APK 构建与静态验证已完成，但 Pangle 广告填充、不同真机的视频解码和局域网防火墙仍需在目标设备上验收。

本 Debug 包不得直接提交商店。正式上架前必须切换 HTTPS/VOD、关闭客户端测试奖励证明、启用服务端广告验签、配置正式签名与 Firebase SHA 指纹，并完成隐私和广告数据披露。
