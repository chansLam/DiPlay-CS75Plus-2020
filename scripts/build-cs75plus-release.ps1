# Build a signed standalone Release APK for Changan CS75 Plus 2020.
# Requires environment variables (never commit keystores or passwords):
#   ANDROID_HOME or ANDROID_SDK_ROOT
#   JAVA_HOME (optional; uses PATH java if unset)
#   DIPLAY_AUTH_ASSETS_DIR  -> runtime-assets/offline-mfi/{identity.pk8,certificate.p7b}
#   ANDROID_KEYSTORE_PATH
#   ANDROID_KEYSTORE_PASSWORD
#   ANDROID_KEY_ALIAS
#   ANDROID_KEY_PASSWORD
# Optional:
#   DIPLAY_RELEASE_OUT  -> destination APK path (default: dist/ under repo root)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root

function Require-Env([string]$Name) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Missing required environment variable: $Name"
    }
    return $value
}

$authDir = Require-Env "DIPLAY_AUTH_ASSETS_DIR"
$keystore = Require-Env "ANDROID_KEYSTORE_PATH"
Require-Env "ANDROID_KEYSTORE_PASSWORD" | Out-Null
Require-Env "ANDROID_KEY_ALIAS" | Out-Null
Require-Env "ANDROID_KEY_PASSWORD" | Out-Null

if (-not (Test-Path -LiteralPath (Join-Path $authDir "offline-mfi\identity.pk8"))) {
    throw "DIPLAY_AUTH_ASSETS_DIR must contain offline-mfi/identity.pk8"
}
if (-not (Test-Path -LiteralPath (Join-Path $authDir "offline-mfi\certificate.p7b"))) {
    throw "DIPLAY_AUTH_ASSETS_DIR must contain offline-mfi/certificate.p7b"
}
if (-not (Test-Path -LiteralPath $keystore)) {
    throw "ANDROID_KEYSTORE_PATH does not exist: $keystore"
}

if ([string]::IsNullOrWhiteSpace($env:ANDROID_HOME) -and -not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
    $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
}
if ([string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
    throw "Set ANDROID_HOME or ANDROID_SDK_ROOT to your Android SDK"
}
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

Write-Host "Building CS75 Plus Release from $Root"
if ($IsWindows -or $env:OS -match "Windows") {
    & .\gradlew.bat :mobile:assembleStandaloneRelease --no-daemon
} else {
    & ./gradlew :mobile:assembleStandaloneRelease --no-daemon
}
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Join-Path $Root "mobile\build\outputs\apk\release\mobile-release.apk"
if (-not (Test-Path -LiteralPath $apk)) {
    throw "Expected APK missing: $apk"
}

$out = $env:DIPLAY_RELEASE_OUT
if ([string]::IsNullOrWhiteSpace($out)) {
    $dist = Join-Path $Root "dist"
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    $out = Join-Path $dist "DiPlay-v0.2.40.1-release.apk"
} else {
    $parent = Split-Path -Parent $out
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
}
Copy-Item $apk $out -Force
Write-Host "APK: $out"
Get-Item $out | Format-List FullName, Length, LastWriteTime
