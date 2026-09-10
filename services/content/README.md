# DramaFlow 内容服务框架

该工程是自由剧（`FREE_DRAMA`）与未来 Pangle 短剧（`PANGLE_DRAMA`）的统一内容编排服务。它重点解决内容融合问题，而不是把任意媒体文件或 Pangle 广告参数直接暴露给客户端。

> **当前可运行范围：** 已实现自有剧示例库、地区过滤、统一 Feed/详情/分集、短时播放会话、自由剧测试解锁状态机，以及 Pangle 内容适配边界。开发环境还支持受路径保护的本地 MP4 测试模式；Pangle 内容源默认禁用。当前广告测试 App ID 和广告位仅属于 Android 广告测试，不是内容服务凭据。

## 架构概览

```text
Android DramaFlow
      │
      ├── GET /v1/feed、/v1/dramas/{id}
      ├── POST /v1/episodes/{id}/playback-session
      └── POST /v1/episodes/{id}/unlock
               │
        ContentOrchestrator
          ┌────┴────┐
          │         │
  FreeDramaProvider   PangleDramaProvider
          │         │
  版权元数据/地区/      Pangle 内容 SDK 或
  CDN 播放令牌/权益     合作方配置（待接入）
```

## 已提供接口

| 接口 | 用途 |
| --- | --- |
| `GET /health` | 服务存活检查。 |
| `GET /v1/feed?region=SG` | 返回按地区过滤的统一内容 Feed。 |
| `GET /v1/dramas/free:shadow-contract?region=SG` | 返回详情和分集权益。 |
| `POST /v1/episodes/free:shadow-contract:ep:3/playback-session` | 已解锁自有剧分集的短时播放会话。若分集配置为 `local_filesystem`，开发环境会返回受短时令牌保护的本地媒体 URL。 |
| `GET /v1/dev/local-media/{episode_id}?token=...` | 仅开发环境的本地媒体响应端点；只接受由播放会话签发的短时令牌，不应被公网或生产环境使用。 |
| `POST /v1/episodes/free:shadow-contract:ep:3/unlock` | 自由剧测试解锁；仅接受 `TEST_REWARD_PROOF`，生产环境必须换成服务端广告验签。 |
| `GET /v1/providers/health` | 显示自由剧与 Pangle 内容源状态。 |
| `POST /v1/rewards/bootstrap` | 返回版本化奖励规则、钱包、任务、签到、圆环、已解锁分集和最近流水。 |
| `POST /v1/rewards/events` | 记录幂等的有效观看事件，并更新服务端任务/圆环进度。 |
| `POST /v1/rewards/ad-claims` | 将完成的激励广告兑换为短期一次性权益令牌；开发环境仅接受显式测试证明。 |
| `POST /v1/rewards/claims` | 结算任务、签到、圆环或广告解锁；余额与权益只由服务端写入。 |
| `GET /v1/rewards/ledger` | 返回用户最近的可审计奖励流水。 |
| `GET/PUT /v1/admin/rewards/config` | 受 `ADMIN_CONFIG_TOKEN` 保护的动态规则读取与替换接口。 |

## Windows 本地运行

本项目的本地媒体和 Android 真机联调以 Windows 10/11 + PowerShell 为准。将服务解压到例如 `C:\\DramaFlow\\dramaflow_content_server` 后，在 PowerShell 中执行：

```powershell
cd C:\\DramaFlow\\dramaflow_content_server
python -m venv .venv
.\\.venv\\Scripts\\Activate.ps1
python -m pip install .
Copy-Item .\\.env.example .\\.env
```

按 [`docs/WINDOWS_LAN_DEPLOYMENT_AND_TEST.md`](docs/WINDOWS_LAN_DEPLOYMENT_AND_TEST.md) 配置 `.env`、媒体目录和片单，然后启动：

```powershell
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

打开 `http://127.0.0.1:8000/docs` 可查看自动生成的接口文档。Android 模拟器访问 Windows 本机服务时使用 `http://10.0.2.2:8000`；真机应在应用“我的—局域网内容服务”中填写 `http://<Windows电脑IPv4地址>:8000`。完整的防火墙与网络步骤见 Windows 手册。

若要执行核心冒烟测试，先在已激活的 PowerShell 虚拟环境中安装可选测试依赖：

```powershell
python -m pip install ".[test]"
```

随后运行：

```powershell
python .\tests\smoke.py
python .\tests\catalog_loader_smoke.py
python .\tests\local_media_smoke.py
python .\tests\rewards_smoke.py
```

## 载入首批自有剧测试片单

1. 在 Windows 服务目录中复制 `content\\owned_catalog.local-media.example.json` 为一个**不提交到公开仓库**的实际片单文件，例如 `C:\\DramaFlow\\dramaflow_content_server\\content\\owned_catalog.local-media.json`。
2. 将其中的剧集、地区、权益、封面和本地视频相对路径替换为你的已授权测试数据；不要在 JSON 中写入长期视频 URL 或云服务 API 密钥。封面字段必须使用 `coverUrl`，分集封面字段必须使用 `posterUrl`。完整的 `http://` 或 `https://` 地址会原样返回；如果填写相对文件名，例如 `cover.png`，请将文件放入 `content/covers/`，服务会自动返回可供 Android 访问的 `/v1/covers/cover.png`。
3. 在 `.env` 中设置 `OWNED_CATALOG_PATH=C:/DramaFlow/dramaflow_content_server/content/owned_catalog.local-media.json`；使用相对封面时再设置 `LOCAL_COVER_ROOT=C:/DramaFlow/dramaflow_content_server/content/covers`，然后重启内容 API。
4. 服务启动时会校验片单格式，并将自有剧 ID（建议以 `owned:` 开头）替换内置示例目录；Android 的 Feed 即可读取真实剧集元数据。
5. 当前播放会话会返回短时测试 URL。连接 Cloudflare Stream、Mux 或对象存储 CDN 后，只需替换服务端播放令牌签发器，不改变 Android 接口。

本地自有剧测试请使用 `content/owned_catalog.local-media.example.json` 作为片单模板。封面无法显示时，先访问服务返回的 `/v1/feed`，确认 `cover_url` 是否为预期的外部地址或 `/v1/covers/...`；再用手机浏览器直接访问该封面 URL。如需配置 Windows 本地 MP4、短时播放 URL、PowerShell 防火墙规则和 Android 真机联调，请按 `docs/WINDOWS_LAN_DEPLOYMENT_AND_TEST.md` 操作。本地文件模式只能在 `ENVIRONMENT=development` 中启用，服务会拒绝在其他环境启动该模式。

## 动态奖励与轻量运营配置

奖励、任务、签到、广告解锁、圆环与钱包规则默认由 `runtime/reward_rules.json` 版本化管理，用户权益和流水写入 `runtime/reward_ledger.sqlite3`。Docker Compose 会把宿主机 `./runtime` 挂载到容器，重启不会丢失测试账本；该目录含用户权益数据，已被 `.gitignore` 排除，不得提交或公开下载。

1. 复制 `runtime/reward_rules.example.json` 为运行时规则文件，或让服务首次启动自动生成默认开发规则。
2. 设置不可预测的 `ADMIN_CONFIG_TOKEN`；未设置时管理写接口会返回不可用，避免默认暴露规则修改能力。
3. 用 `Authorization: Bearer <ADMIN_CONFIG_TOKEN>` 调用管理接口读取/替换完整规则。新规则的 `version` 必须变化，账本会记录每次结算使用的规则版本。
4. Android 使用 `POST /v1/rewards/bootstrap` 拉取状态。广告 SDK 完成回调后，客户端先请求广告权益令牌，再调用结算接口；不得在客户端直接改金币、现金或已解锁集数。
5. `ENVIRONMENT=development` 仅支持 `TEST_PANGLE_REWARD` 联调证明。生产环境缺少 Pangle 服务端回调/验签时，广告解锁和广告翻倍应失败关闭，不能乐观发奖。

详细字段、幂等约束和安全边界见 `docs/DYNAMIC_REWARDS_CONTRACT.md`；运营规则样例见 `runtime/reward_rules.example.json`。

## Android 接入顺序

1. 使用 `GET /v1/feed?region=<ISO-3166-1 Alpha-2>` 替换当前 `DemoRepository` 的内容目录。
2. 使用统一 `source` 和 `playbackMode` 显示剧集；`SIGNED_URL` 走自有播放器，`PANGLE_SDK` 走合作方内容 SDK。
3. 调用详情接口时附带已认证的用户身份；生产环境不要把可修改的 `user_id` 直接暴露为查询参数。
4. 在播放前调用 `playback-session`，并仅使用服务端签发的短时播放令牌。
5. 广告解锁或金币解锁后，由服务端校验最终结果并写入权益账本，再刷新详情。

## 部署选择

| 方式 | 适合场景 | 优点 | 需要注意 |
| --- | --- | --- | --- |
| 托管应用后端 + 数据库 | 首次试运营、标准 API、内容运营后台。 | 运维较轻、可扩展用户和内容管理。 | 若采用托管模板，需要将本项目的 Python 逻辑重写/迁移到相应后端运行环境。 |
| 此 FastAPI 容器框架 | 已有云主机、容器平台或需要使用 Python 视频/版权工具。 | 代码可移植，能接入自定义 CDN、DRM、队列和运维监控。 | 需自行配置 HTTPS、数据库、密钥、日志、自动扩容与备份。 |

当前只交付了可在开发机或容器平台运行的框架，**没有启动常驻公网服务**。上线前应根据你的云资源、预算、目标地区和数据合规要求选择部署方式。

## 上线前必做项

| 范畴 | 必做事项 |
| --- | --- |
| 版权 | 对每部自由剧保存地区、语言、期限、窗口（AVOD/SVOD/TVOD）、推广素材和下架条件。 |
| 安全 | 迁移到数据库；采用真实用户鉴权、短时 CDN/DRM 播放令牌、密钥管理与服务端广告奖励验证。 |
| 地区 | 按服务端地区、合同和内容分级过滤；不要依赖客户端自行判断。 |
| Pangle | 取得内容 SDK、内容应用配置和目标地区授权后再开启 `PANGLE_CONTENT_ENABLED` 并实现适配器。 |
| 观测 | 记录 Feed 请求、播放成功/失败、完播、解锁、下架和来源维度指标；避免记录不必要的个人数据。 |

详细契约请见 `docs/CONTENT_CONTRACT.md`；Pangle 的接入边界请见 `docs/PANGLE_ADAPTER.md`。
