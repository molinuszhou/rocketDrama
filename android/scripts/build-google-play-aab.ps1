<#
.SYNOPSIS
  使用 R1 正式 Release 配置构建并签名 Google Play 上传 AAB。

.DESCRIPTION
  - 临时将 local.properties.release 应用于 Gradle，完成后自动恢复原 local.properties。
  - Google Play App Signing 场景下，上传本脚本生成的 Upload Key 已签名 AAB。
  - 密码由 jarsigner 交互读取，不保存到脚本、命令行、日志或 Git。
  - 输出位于 android\release-artifacts\，不会加入 Git。
  - 构建前会显示当前 Git 分支、版本号与应用包名，请确认均为预期值。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\build-google-play-aab.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\build-google-play-aab.ps1 -SkipClean
#>

[CmdletBinding()]
param(
    [string]$KeystorePath = "",
    [string]$Alias = "joyedrama-upload",
    [string]$OutputDirectory = "",
    [switch]$SkipClean
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RequiredFile {
    param([string]$Path, [string]$Label)
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label not found: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Get-JarsignerPath {
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $fromJavaHome = Join-Path $env:JAVA_HOME "bin\jarsigner.exe"
        if (Test-Path -LiteralPath $fromJavaHome -PathType Leaf) { return $fromJavaHome }
    }
    $command = Get-Command jarsigner.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "jarsigner.exe was not found. Install a JDK or set JAVA_HOME."
}

function Invoke-ReleaseGradle {
    param([string]$AndroidRoot, [string[]]$Arguments)

    $releaseProperties = Resolve-RequiredFile (Join-Path $AndroidRoot "local.properties.release") "Release properties profile"
    $activeProperties = Join-Path $AndroidRoot "local.properties"
    $hadActiveProperties = Test-Path -LiteralPath $activeProperties -PathType Leaf
    [byte[]]$activePropertiesBackup = if ($hadActiveProperties) { [System.IO.File]::ReadAllBytes($activeProperties) } else { @() }

    try {
        Copy-Item -LiteralPath $releaseProperties -Destination $activeProperties -Force
        Push-Location $AndroidRoot
        try {
            & (Join-Path $AndroidRoot "gradlew.bat") @Arguments
            if ($LASTEXITCODE -ne 0) { throw "Gradle Release AAB build failed with exit code $LASTEXITCODE." }
        }
        finally {
            Pop-Location
        }
    }
    finally {
        if ($hadActiveProperties) {
            [System.IO.File]::WriteAllBytes($activeProperties, $activePropertiesBackup)
        }
        elseif (Test-Path -LiteralPath $activeProperties) {
            Remove-Item -LiteralPath $activeProperties -Force
        }
    }
}

$androidRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$repositoryRoot = Split-Path -Parent $androidRoot
$checkoutRoot = Split-Path -Parent $repositoryRoot
$workspaceRoot = Split-Path -Parent $checkoutRoot
Resolve-RequiredFile (Join-Path $androidRoot "gradlew.bat") "Gradle wrapper" | Out-Null

if ([string]::IsNullOrWhiteSpace($KeystorePath)) {
    $keyCandidates = @(
        (Join-Path $checkoutRoot "joyedrama-upload-key-and-sign-scripts\joyedrama-upload-keystore.jks"),
        (Join-Path $workspaceRoot "joyedrama-upload-key-and-sign-scripts\joyedrama-upload-keystore.jks")
    )
    $KeystorePath = $keyCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
}
$KeystorePath = Resolve-RequiredFile $KeystorePath "Upload keystore"
$jarsigner = Get-JarsignerPath

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $androidRoot "release-artifacts"
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path

$branch = (& git -c "safe.directory=$($repositoryRoot.Replace('\', '/'))" -C $repositoryRoot branch --show-current 2>$null).Trim()
$versionLine = (Get-Content -LiteralPath (Join-Path $androidRoot "app\build.gradle.kts") | Where-Object { $_ -match '^\s*versionName\s*=' } | Select-Object -First 1).Trim()
$applicationIdLine = (Get-Content -LiteralPath (Join-Path $androidRoot "app\build.gradle.kts") | Where-Object { $_ -match '^\s*formalApplicationId\s*=' } | Select-Object -First 1).Trim()
Write-Host "Current branch: $branch" -ForegroundColor Cyan
Write-Host "Current Gradle version: $versionLine" -ForegroundColor Cyan
Write-Host "Release package: $applicationIdLine" -ForegroundColor Cyan
Write-Host "Signing alias: $Alias" -ForegroundColor Cyan

$gradleArguments = @()
if (-not $SkipClean) { $gradleArguments += ":app:clean" }
$gradleArguments += ":app:bundleRelease", "--no-daemon", "--console=plain"
Invoke-ReleaseGradle -AndroidRoot $androidRoot -Arguments $gradleArguments

$bundleDirectory = Join-Path $androidRoot "app\build\outputs\bundle\release"
$unsignedAab = Get-ChildItem -LiteralPath $bundleDirectory -File -Filter "*.aab" |
    Where-Object { $_.Name -notmatch '-signed\.aab$' } |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
if (-not $unsignedAab) { throw "No Release AAB found in: $bundleDirectory" }

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$finalAab = Join-Path $OutputDirectory "JoyeDrama-google-play-upload-signed-$stamp.aab"

Write-Host "Enter the Upload Key password when jarsigner prompts. The password is not stored." -ForegroundColor Yellow
& $jarsigner -keystore $KeystorePath -storetype JKS -sigalg SHA256withRSA -digestalg SHA-256 -signedjar $finalAab $unsignedAab.FullName $Alias
if ($LASTEXITCODE -ne 0) { throw "jarsigner failed with exit code $LASTEXITCODE." }

Write-Host "Verifying AAB signature..." -ForegroundColor Cyan
& $jarsigner -verify -verbose -certs $finalAab
if ($LASTEXITCODE -ne 0) { throw "AAB signature verification failed." }

$hash = (Get-FileHash -LiteralPath $finalAab -Algorithm SHA256).Hash
Write-Host ""
Write-Host "SUCCESS — Google Play upload AAB:" -ForegroundColor Green
Write-Host $finalAab -ForegroundColor Green
Write-Host "SHA-256: $hash" -ForegroundColor Green
