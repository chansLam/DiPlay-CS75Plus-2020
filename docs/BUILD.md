# 构建与发布

在仓库根目录执行。密钥与 offline-MFi **不要**提交进 Git（见 [SECURITY.md](../SECURITY.md)）。

## 模块

| 模块 | 用途 |
| --- | --- |
| `:mobile` | APK 壳（`com.shihab.diplay`） |
| `:common` | 设置 UI、CarPlay Host |
| `:shared` | 协议、SoftAP、媒体、JNI |

## 环境

JDK 25 · Android SDK Platform 37 · NDK 25.2.9519653 · `ANDROID_HOME` 或 `local.properties` 的 `sdk.dir=`。

## 调试包

```sh
./gradlew :mobile:assembleDebug
```

产物：`mobile/build/outputs/apk/debug/mobile-debug.apk`（无 MFi 时不能独立完成认证）。

CI 同款检查：

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest \
  :mobile:lintDebug :mobile:assembleDebug
```

## 含 offline-MFi 的独立调试包

```text
<DIPLAY_AUTH_ASSETS_DIR>/offline-mfi/{identity.pk8,certificate.p7b}
```

也可用 `.private/runtime-assets/`（已 gitignore）。

```sh
export DIPLAY_AUTH_ASSETS_DIR=...
./gradlew :mobile:assembleStandaloneDebug
```

PowerShell：`$env:DIPLAY_AUTH_ASSETS_DIR="..."` 后执行 `.\gradlew.bat`，必要时加  
`"-PDIPLAY_AUTH_ASSETS_DIR=$env:DIPLAY_AUTH_ASSETS_DIR"`。

## 本机正式签名

环境变量：`ANDROID_KEYSTORE_PATH` / `PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` / `DIPLAY_AUTH_ASSETS_DIR`。

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-cs75plus-release.ps1
```

产物默认：`dist/DiPlay-v<version>-release.apk`。

## 应用内更新

- 打开主界面时检查本仓库 stable Release（成功后约 24h 内不重复）；也可在 **关于 → 检查更新** 手动触发。
- Release 须同时提供 `DiPlay-vX.Y.Z-release.apk` 与同名 `.sha256`，且通过包名、`versionCode`、签名证书校验后才调安装器。

## 打 tag 发布（Actions）

Secrets（6 个）：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`、`DIPLAY_AUTH_IDENTITY_PK8_BASE64`、`DIPLAY_AUTH_CERTIFICATE_P7B_BASE64`。

本机生成（勿提交）：`scripts\generate-cs75plus-ci-secrets.ps1` → `.private/cs75plus-ci-secrets/`。

发布前：提高 `versionName` / `versionCode` → 更新 `CHANGELOG.md` 与 `site/config.json` → `python scripts/build_site.py` →  
`git tag vX.Y.Z && git push origin vX.Y.Z`。

改仓库名时同步 `site/config.json` 的 `github_repository` 与更新检查用的仓库常量。
