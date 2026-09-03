# DramaFlow 本地媒体测试与 Android 联调手册（Windows）

**适用范围：Windows 10/11 开发电脑、Android Debug 测试包和自有 MP4 局域网联调。**

原先面向 Linux/macOS Shell 的本地媒体说明已由 Windows PowerShell 流程取代。请使用以下完整手册：

> [Windows 局域网内容服务与 Android 联调手册](WINDOWS_LAN_DEPLOYMENT_AND_TEST.md)

该手册包含 Windows 下的 Python 虚拟环境、`.env` 写法、媒体目录、PowerShell 启动命令、Windows 防火墙、电脑 IPv4 地址识别、Android 应用连接地址以及故障排查步骤。

## 快速入口

在 Windows PowerShell 中，进入解压后的服务目录后执行以下基本命令：

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install .
```

完成 `media`、片单与 `.env` 配置后，使用以下命令启动服务：

```powershell
Get-Content .\.env | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
    $name, $value = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), 'Process')
}
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

真机在同一可信局域网内访问 `http://<Windows电脑IPv4地址>:8000`；在 DramaFlow 应用 **“我的 → 局域网内容服务”** 中填入该地址即可重新加载自有剧目录。

> 本地媒体模式只用于开发联调。正式上架前必须关闭 `LOCAL_MEDIA_ENABLED`，替换为 HTTPS 与正式 VOD/CDN，并移除 Windows 防火墙开发规则。
