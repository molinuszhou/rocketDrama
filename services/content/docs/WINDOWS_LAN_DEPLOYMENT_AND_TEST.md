# DramaFlow Windows 局域网内容服务与 Android 联调手册

**文档版本：1.2.0**
**适用范围：Windows 10/11 开发电脑、Android Debug 测试包和已获授权的自有 MP4 测试素材**
**不适用范围：公网分发、商店正式上架、生产 VOD**

> **安装修订（服务端 v0.1.1）：** 已限制 setuptools 只发现 `app` 服务包，因此在已存在 `content`、`media`、`runtime` 目录的 Windows 解压目录中，`python -m pip install .` 可以正常运行，不再出现 “Multiple top-level packages discovered” 错误。

> **推荐：统一 Docker 启动。** 请先进入仓库的 `services/` 目录，再运行 `docker compose up -d --build`；该命令会同时启动内容服务（8000）、PostgreSQL 和积分服务（8100），无需再单独打开 PowerShell 运行 `uvicorn`。请优先阅读仓库根目录 `README.md` 的“Windows 一键启动测试环境”。本手册其余 Python 步骤仅用于不安装 Docker Desktop 时的内容服务单独调试。

## 1. 目标与前置条件

本手册将 Windows 电脑作为临时内容服务器：FastAPI 从本机 `media` 目录读取测试视频，向 Android 应用提供剧目目录、分集详情、解锁接口和短时播放 URL。Android 手机不会把视频打进 APK；每次播放都会先请求服务端会话，再把返回的 `stream_url` 交给 Media3 播放器。

> 本地媒体模式只允许在 `ENVIRONMENT=development` 下运行。它使用内存态短时令牌，重启服务后已签发 URL 失效，因此仅适用于同一可信局域网的开发联调，绝不能暴露到公网。

| 必备项 | 要求 |
|---|---|
| Windows 系统 | Windows 10 或 Windows 11，建议使用 Windows Terminal 的 PowerShell |
| Python | Python 3.11 或以上，并在安装时勾选 **Add Python to PATH** |
| 网络 | 手机和 Windows 电脑连接到同一个可信 Wi-Fi/LAN；关闭访客网络或 AP 隔离 |
| 内容服务 | 解压 `DramaFlow-LAN-Content-Server.zip` 到不含中文、空格或 OneDrive 同步限制的路径，例如 `C:\DramaFlow\dramaflow_content_server` |
| 测试素材 | 已获授权的 H.264/AAC MP4 文件；不要使用无分发许可的视频 |
| Android 包 | 安装包名为 `com.dramaflow.app` 的 Debug APK |

Python 的 `venv` 可以创建相互隔离的轻量虚拟环境，避免系统 Python 与项目依赖冲突。[1]

## 2. 备用方案：不使用 Docker 时创建 Python 环境

以下所有命令均在 **PowerShell** 中执行。先进入内容服务目录：

```powershell
cd C:\DramaFlow\dramaflow_content_server
python --version
```

创建并激活虚拟环境，然后安装项目依赖：

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install .
```

以上命令只安装运行内容服务所需的依赖。若还要执行本项目的 Python 冒烟测试，再额外安装可选测试依赖：

```powershell
python -m pip install ".[test]"
```

如果 PowerShell 阻止执行激活脚本，只在当前窗口临时放宽执行策略后重试：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\.venv\Scripts\Activate.ps1
```

激活成功后，命令行前方会出现 `(.venv)`。每次重新打开 PowerShell 并继续测试时，都需要再次运行：

```powershell
cd C:\DramaFlow\dramaflow_content_server
.\.venv\Scripts\Activate.ps1
```

## 3. 创建媒体目录与测试片单

目录成功加载但视频播放显示 `ERROR_CODE_IO_BAD_HTTP_STATUS` 时，最常见原因是**片单中的 `videoAssetId` 与 Windows `media` 目录不一致**。必须先决定使用哪一种片单，并将视频文件按该片单的精确目录名和文件名放置。

### 方案 A：三部测试剧片单（推荐）

如果首页显示 `Love in Cyberpunk 2077`、`Storage War They Sold Me Trash. I Bought Their Kingdom` 或 `Wasteland Wolf King`，请使用三部剧片单，并按如下目录放置 MP4：

```powershell
Copy-Item .\content\owned_catalog.three-test-dramas.json .\content\owned_catalog.local-media.json
New-Item -ItemType Directory -Force -Path ".\media\Love in Cyberpunk 2077"
Copy-Item "C:\Path\To\ep01.mp4" ".\media\Love in Cyberpunk 2077\ep01.mp4"
Copy-Item "C:\Path\To\ep02.mp4" ".\media\Love in Cyberpunk 2077\ep02.mp4"
```

该片单要求的完整相对路径如下；文件名必须从 `ep01.mp4` 开始，不能写成 `ep-001.mp4`：

| 剧目 | 第 1 集所需相对路径 |
|---|---|
| Love in Cyberpunk 2077 | `Love in Cyberpunk 2077/ep01.mp4` |
| Storage War They Sold Me Trash. I Bought Their Kingdom | `Storage War They Sold Me Trash. I Bought Their Kingdom/ep01.mp4` |
| Wasteland Wolf King | `Wasteland Wolf King/ep01.mp4` |

每部剧后续分集使用同一目录，依次为 `ep02.mp4`、`ep03.mp4` 等。

### 方案 B：两集最小示例片单

如果只想验证一部两集示例剧，使用下列布局：

```powershell
New-Item -ItemType Directory -Force -Path .\media\owned\local-test-drama
Copy-Item "C:\Path\To\authorized-episode-1.mp4" .\media\owned\local-test-drama\ep-001.mp4
Copy-Item "C:\Path\To\authorized-episode-2.mp4" .\media\owned\local-test-drama\ep-002.mp4
Copy-Item .\content\owned_catalog.local-media.example.json .\content\owned_catalog.local-media.json
```

片单中的 `videoAssetId` 必须是相对于 `LOCAL_MEDIA_ROOT` 的安全路径。服务端 v0.1.2 同时兼容 `media\<剧目录>\...` 和 `media\owned\<剧目录>\...` 两种布局，但不会猜测错误的剧目录或错误的文件名。不要填写 `C:\` 绝对路径、`..` 路径或长期公开视频 URL。

## 4. 配置 `.env`

复制示例环境文件：

```powershell
Copy-Item ..\.env.example .\.env
```

用记事本打开：

```powershell
notepad .\.env
```

将以下配置加入或替换到 `.env`。请将示例路径改为你的实际解压目录；Windows 路径建议使用正斜杠 `/`，避免反斜杠转义歧义。

```dotenv
ENVIRONMENT=development
PANGLE_CONTENT_ENABLED=false
LOCAL_MEDIA_ENABLED=true
# Docker 模式：以下是 Windows 主机路径，Compose 会分别映射到 /app/media 和 /app/content/owned_catalog.json。
LOCAL_MEDIA_ROOT=C:/DramaFlow/dramaflow_content_server/media
OWNED_CATALOG_PATH=C:/DramaFlow/dramaflow_content_server/content/owned_catalog.local-media.json
```

| 变量 | Windows 本地测试值 | 作用 |
|---|---|---|
| `ENVIRONMENT` | `development` | 本地媒体模式的强制安全开关；其他值会拒绝启动本地媒体服务 |
| `PANGLE_CONTENT_ENABLED` | `false` | 关闭尚未配置的外部短剧内容源；不影响 Pangle 广告 SDK |
| `LOCAL_MEDIA_ENABLED` | `true` | 开启本地 MP4 和短时播放 URL |
| `LOCAL_MEDIA_ROOT` | Windows 主机上的 `media` 绝对路径 | Docker Compose 映射到容器 `/app/media` |
| `OWNED_CATALOG_PATH` | Windows 主机上的片单绝对路径 | Docker Compose 映射到容器 `/app/content/owned_catalog.json` |

`.env`、`media`、`runtime` 下的账本文件和实际片单不应上传到 Git、网盘公开链接或源码压缩包。

## 5. 备用方案：手动启动服务与本机验证

在已激活的虚拟环境中，载入 `.env` 并启动服务：

```powershell
Get-Content .\.env | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
    $name, $value = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), 'Process')
}
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

保持这个 PowerShell 窗口开启。另开一个 PowerShell 窗口，激活虚拟环境后进行本机检查：

```powershell
cd C:\DramaFlow\dramaflow_content_server
.\.venv\Scripts\Activate.ps1
Invoke-RestMethod http://127.0.0.1:8000/health
Invoke-RestMethod "http://127.0.0.1:8000/v1/feed?region=SG"
```

健康检查应显示 `status` 为 `ok`，并且 `local_media` 为 `enabled`。也可打开浏览器访问 `http://127.0.0.1:8000/docs` 查看 API 文档。

## 6. 获取 Windows 局域网 IP 并配置防火墙

在 PowerShell 执行：

```powershell
ipconfig
```

在当前 Wi-Fi 或以太网适配器下寻找 **IPv4 Address**，例如 `192.168.1.25`。不要使用 `127.0.0.1`、Docker、WSL、蓝牙或虚拟网卡的地址。

以**管理员身份**打开 PowerShell，为仅限专用网络的 8000 端口添加入站规则：

```powershell
New-NetFirewallRule `
  -DisplayName "DramaFlow LAN Dev 8000" `
  -Direction Inbound `
  -Action Allow `
  -Protocol TCP `
  -LocalPort 8000 `
  -Profile Private
```

`New-NetFirewallRule` 会向本机 Windows 防火墙新增入站或出站规则；本手册只开放 TCP 8000 和 Private 配置文件。[2]

若不再测试，可删除规则：

```powershell
Remove-NetFirewallRule -DisplayName "DramaFlow LAN Dev 8000"
```

先用手机浏览器访问：

```text
http://<你的电脑IPv4地址>:8000/health
```

若能看到 JSON 响应，说明网络通路正常。若失败，请确认两台设备在同一网络、Windows 网络配置为“专用”、没有开启 VPN/代理隔离，并检查安全软件的入站拦截。

## 7. 在 Android 应用中连接服务

进入 DramaFlow 应用底部 **“我的”** 页面，点击 **“局域网内容服务”**，填写：

```text
http://<你的电脑IPv4地址>:8000
```

例如 IP 为 `192.168.1.25`，则填写：

```text
http://192.168.1.25:8000
```

保存后应用会重新拉取目录。Android 模拟器测试时，不填写 Windows IP，而使用默认的 `http://10.0.2.2:8000`。

| 验收动作 | 预期结果 |
|---|---|
| 打开首页 | 读取 `/v1/feed` 并显示自有剧卡片 |
| 进入剧集 | 读取详情、分集和简介 |
| 播放第 1 集 | 服务签发短时 `stream_url`，Media3 播放本地 MP4 |
| 播放锁定集 | 免费集数用完后，应用先提示用户确认；确认观看插屏后调用解锁接口并重新请求播放会话 |
| 完播 | 下一集仍免费时自动续播；下一集锁定时显示确认，不主动请求解锁广告 |
| 点击悬浮球领取宝石 | 先请求一次插屏，关闭后由独立奖励服务一次性结算全部待领取宝石 |

## 8. Windows 常见故障

| 现象 | 原因与处理 |
|---|---|
| `python` 不是内部命令 | 重新安装 Python 并选择 Add Python to PATH；关闭并重新打开 PowerShell |
| 激活 `.ps1` 被禁止 | 执行本手册的 `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`，无需永久改变系统策略 |
| 手机无法加载目录 | 使用实际 Wi-Fi/以太网 IPv4 地址；服务必须带 `--host 0.0.0.0`；确认防火墙端口和同一局域网 |
| 服务启动时报本地媒体只能在开发环境启用 | 将 `.env` 中的 `ENVIRONMENT` 改为 `development`，然后重启服务 |
| 播放接口返回 404 或 App 显示 `ERROR_CODE_IO_BAD_HTTP_STATUS` | 先执行 `docker compose config`，确认 `LOCAL_MEDIA_ROOT` 和 `OWNED_CATALOG_PATH` 指向 Windows 主机上真实存在的目录/文件；再确认容器内 `/app/media` 下存在与 `videoAssetId` 对应的 MP4。若首页显示三部测试剧，请按本节的 `Love in Cyberpunk 2077/ep01.mp4` 等路径放置文件，然后重建服务。 |
| 播放接口返回 402 | 当前分集超过免费集数；先完成激励广告解锁流程 |
| 播放接口返回 403 | 播放 URL 已过期或服务重启；在 App 中重新进入该集以获取新会话 |
| Android 显示明文网络错误 | 必须使用本次 Debug APK；Release 默认禁止 HTTP 局域网地址 |
| 视频下载但无法播放 | 用 H.264/AAC MP4 验证；检查文件没有损坏，并记录设备型号和 Android 版本 |

## 9. 可选：执行服务端冒烟测试

在服务目录且虚拟环境已激活的 PowerShell 中运行：

```powershell
python .\tests\catalog_loader_smoke.py
python .\tests\local_media_smoke.py
python .\tests\rewards_smoke.py
python .\tests\smoke.py
```

如果提示缺少测试客户端依赖，请确认已经运行 `python -m pip install ".[test]"`。运行测试会在 `runtime` 下创建临时规则和 SQLite 账本；这些运行时文件无需提交，也不应公开。

## 10. 安全与上架边界

局域网 HTTP 仅用于调试。Android 的网络安全配置允许按构建或域设置明文流量；面向生产的应用应关闭全局明文流量并使用 HTTPS。[3] 明文传输可被网络观察者读取或篡改，因此不能承载生产用户数据、支付信息、长期播放 URL 或奖励凭据。[4]

正式上架前必须迁移到 HTTPS、正式 VOD/CDN、真实用户鉴权、服务端广告验签和持久化数据库；关闭 `LOCAL_MEDIA_ENABLED`，删除防火墙开发规则，并以正式签名重新构建 Release/AAB。外部短剧 SDK 后续接入时，License 和密钥只能放入受控 CI Secret 或不提交的本地文件。

## References

[1] [Python Documentation — venv: Creation of virtual environments](https://docs.python.org/3/library/venv.html)
[2] [Microsoft Learn — New-NetFirewallRule](https://learn.microsoft.com/en-us/powershell/module/netsecurity/new-netfirewallrule)
[3] [Android Developers — Network security configuration](https://developer.android.com/privacy-and-security/security-config)
[4] [Android Developers — Cleartext communications](https://developer.android.com/privacy-and-security/risks/cleartext-communications)
