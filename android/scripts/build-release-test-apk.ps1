<#
.SYNOPSIS
  使用 R1 正式 Release 配置构建、对齐并签名 APK，供真机 Release 测试。

.DESCRIPTION
  - 临时将 local.properties.release 应用于 Gradle，完成后自动恢复原 local.properties。
  - 密码由 apksigner 交互读取，不保存到脚本、命令行、日志或 Git。
  - 输出位于 android\release-artifacts\，不会加入 Git。
  - 构建前会显示当前 Git 分支、版本号与应用包名，请确认均为预期值。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\build-release-test-apk.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\build-release-test-apk.ps1 -SkipClean
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

function Get-AndroidBuildTools {
    param([string]$AndroidRoot)

    $candidates = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    $localProperties = Join-Path $AndroidRoot "local.properties.release"
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($sdkLine) {
            $rawSdkPath = ($sdkLine -replace '^\s*sdk\.dir\s*=', '').Trim()
            # local.properties escapes Windows separators and the C: colon.
            $candidates += $rawSdkPath.Replace('\\', '\').Replace('\:', ':')
        }
    }

    foreach ($sdkRoot in ($candidates | Select-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $sdkRoot -PathType Container)) { continue }
        $buildToolsRoot = Join-Path $sdkRoot "build-tools"
        if (-not (Test-Path -LiteralPath $buildToolsRoot -PathType Container)) { continue }
        $toolDirectory = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
            Sort-Object { [version]$_.Name } -Descending |
            Where-Object {
                (Test-Path -LiteralPath (Join-Path $_.FullName "zipalign.exe") -PathType Leaf) -and
                (Test-Path -LiteralPath (Join-Path $_.FullName "apksigner.bat") -PathType Leaf)
            } |
            Select-Object -First 1
        if ($toolDirectory) {
            return [pscustomobject]@{
                Zipalign = Join-Path $toolDirectory.FullName "zipalign.exe"
                Apksigner = Join-Path $toolDirectory.FullName "apksigner.bat"
                Version = $toolDirectory.Name
            }
        }
    }

    throw "Android Build Tools with zipalign.exe and apksigner.bat were not found. Set sdk.dir in local.properties.release or ANDROID_SDK_ROOT."
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
            if ($LASTEXITCODE -ne 0) { throw "Gradle Release APK build failed with exit code $LASTEXITCODE." }
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
$tools = Get-AndroidBuildTools $androidRoot

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
Write-Host "Build Tools: $($tools.Version)" -ForegroundColor Cyan
Write-Host "Signing alias: $Alias" -ForegroundColor Cyan

$gradleArguments = @()
if (-not $SkipClean) { $gradleArguments += ":app:clean" }
$gradleArguments += ":app:assembleRelease", "--no-daemon", "--console=plain"
Invoke-ReleaseGradle -AndroidRoot $androidRoot -Arguments $gradleArguments

$releaseDirectory = Join-Path $androidRoot "app\build\outputs\apk\release"
$unsignedApk = Get-ChildItem -LiteralPath $releaseDirectory -File -Filter "*.apk" |
    Where-Object { $_.Name -notmatch '-signed\.apk$' } |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
if (-not $unsignedApk) { throw "No unsigned Release APK found in: $releaseDirectory" }

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$finalApk = Join-Path $OutputDirectory "JoyeDrama-release-test-signed-$stamp.apk"
$alignedApk = Join-Path $OutputDirectory "JoyeDrama-release-test-aligned-$stamp.apk"

try {
    Write-Host "Aligning Release APK..." -ForegroundColor Cyan
    & $tools.Zipalign -p -f 4 $unsignedApk.FullName $alignedApk
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed with exit code $LASTEXITCODE." }

    Write-Host "Enter the Upload Key password when apksigner prompts. The password is not stored." -ForegroundColor Yellow
    & $tools.Apksigner sign --ks $KeystorePath --ks-key-alias $Alias --out $finalApk $alignedApk
    if ($LASTEXITCODE -ne 0) { throw "apksigner failed with exit code $LASTEXITCODE." }

    Write-Host "Verifying APK signature and alignment..." -ForegroundColor Cyan
    & $tools.Apksigner verify --verbose --print-certs $finalApk
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed." }
    & $tools.Zipalign -c -p -v 4 $finalApk
    if ($LASTEXITCODE -ne 0) { throw "APK alignment verification failed." }

    $hash = (Get-FileHash -LiteralPath $finalApk -Algorithm SHA256).Hash
    Write-Host ""
    Write-Host "SUCCESS — signed Release test APK:" -ForegroundColor Green
    Write-Host $finalApk -ForegroundColor Green
    Write-Host "SHA-256: $hash" -ForegroundColor Green
}
finally {
    if (Test-Path -LiteralPath $alignedApk) { Remove-Item -LiteralPath $alignedApk -Force }
}
