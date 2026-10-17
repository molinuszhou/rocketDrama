$ErrorActionPreference = "Stop"

$serviceRoot = Split-Path -Parent $PSScriptRoot
$dashboardPath = Join-Path $serviceRoot "app\admin_dashboard.py"
$source = [System.IO.File]::ReadAllText($dashboardPath)
$match = [regex]::Match($source, "(?s)DASHBOARD_HTML = r'''(.*?)'''")
if (-not $match.Success) {
    throw "未找到管理仪表盘 HTML。"
}
$scriptMatch = [regex]::Match($match.Groups[1].Value, "(?s)<script>(.*?)</script>")
if (-not $scriptMatch.Success) {
    throw "未找到管理仪表盘脚本。"
}

$tempPath = Join-Path ([System.IO.Path]::GetTempPath()) ("joyedrama-admin-dashboard-{0}.js" -f [guid]::NewGuid())
try {
    [System.IO.File]::WriteAllText($tempPath, $scriptMatch.Groups[1].Value, [System.Text.UTF8Encoding]::new($false))
    & node --check $tempPath
    if ($LASTEXITCODE -ne 0) {
        throw "管理仪表盘 JavaScript 语法校验失败。"
    }
    & node (Join-Path $PSScriptRoot "check_admin_dashboard_runtime.js") $dashboardPath
    if ($LASTEXITCODE -ne 0) {
        throw "管理仪表盘 JavaScript 运行时回归失败。"
    }
    Write-Output "管理仪表盘 JavaScript 语法校验通过。"
}
finally {
    Remove-Item -LiteralPath $tempPath -Force -ErrorAction SilentlyContinue
}
