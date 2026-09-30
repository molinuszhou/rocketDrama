# JoyeDrama 正式发布分支

本分支用于 Android 正式包 **`com.feather.joyelledrama`** 的发布与部署。

| 目录 | 正式环境职责 |
|---|---|
| `android/` | Kotlin 原生 Android 客户端。Release 固定使用 Pangle/PSSDK 短剧目录和播放器。 |
| `services/rewards/` | PostgreSQL 奖励账本、广告事件审计、钱包与任务结算、运营后台及正式 Docker 部署。 |
| `services/README.md` | 服务范围和部署入口说明。 |

## 内容与广告

正式 Release 不使用自建本地 Content API，也不部署本地 MP4 视频。短剧目录与播放内容来自已授权的 Pangle/PSSDK。AppsFlyer 归因返回 `Organic` 时进入 A 面；非自然量归因进入 B 面。

Rewards 是独立 HTTPS 后端。签名或 CI 构建前，请在被 Git 忽略的 `android/local.properties` 或受控 CI Secret 中填写正式 TopOn、AppsFlyer、Firebase、Pangle/PSSDK 与 Rewards API 参数。模板见 [`android/partner.properties.example`](android/partner.properties.example)。

## 服务器部署

当前正式部署以**宿主机原生 Nginx**为唯一 HTTPS 网关，不使用 Caddy。Nginx 保持现有证书、`80/443` 端口和网站路由，仅新增 `/joyedrama-rewards/` 反向代理到本机 Docker 中的 Rewards API。

部署步骤见：[Rewards 正式部署说明](services/rewards/docs/AWS_PRODUCTION_DEPLOYMENT.md)。

## 分支约定

- `main` 保留此前开发与测试历史。
- `release/formal-production` 是正式发布分支。
- 生产凭据、Firebase 配置、PSSDK License、签名密钥和 `.env.production` 禁止提交到 Git。
