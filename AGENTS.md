# AGENTS.md

给在本仓库工作的编码助手的约定。项目：**DiPlay CS75 Plus 2020 SoftAP**（v1.0.0）。

## 产品边界

- 目标车机：Android **4.4.2 / API 19**，`armeabi-v7a`，**SoftAP 优先**无线。
- **不要**加入：BYD HUD、仪表 cluster、有线 USB CarPlay、多 OEM 车辆数据设置。
- `minSdk` / `targetSdk` 均为 **19**。不以手机高版本为目标；`SDK_INT` 分支仅保留 KitKat 侧行为或车机假 SDK 校正（见 `realSdkInt()`），不要再堆 Android 6+ 运行时权限 / FGS type / Overlay type 等手机路径。
- 上游致谢与移植对照 tip 只维护在 `docs/THIRD_PARTY_NOTICES.md`（各上游一条最新提交，不记明细表）。
- 文档默认中文：`README.md`、`docs/INSTALL.md`、`docs/BUILD.md`、`SECURITY.md`、`CHANGELOG.md`。

## 提交前检查

与 `.github/workflows/android.yml` 一致：

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest \
  :mobile:lintDebug \
  :mobile:assembleDebug
```

配置 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 或 gitignored 的 `local.properties`。  
**禁止**提交密钥库、密码、`DIPLAY_AUTH_ASSETS_DIR` / `.private/` 内容。  
Release 用的六个 Actions Secrets 见 `docs/BUILD.md`。

## 增加或移动设置项

设置按**司机目标**分组，不按实现分组。  
`SettingsCategory` / `SettingsSection` / `SettingsInformationArchitecture` 在  
`common/src/main/java/com/shihab/diplay/settings/SettingsArchitecture.kt`。  
各分类卡片在 `common/.../settings/*SettingsSection.kt`。  
`SettingsLayoutPolicyTest`：每个 section 必须恰好属于一个 category。

### 选分类

按司机意图选**第一行能对上的**：

| 分类 | 放入条件 | 示例 |
| --- | --- | --- |
| Connection | 如何连上 iPhone、如何启动 | 连接设置、开机启动、热点、选手机、权限 |
| Display | CarPlay 在车机屏上长什么样 | 昼夜、画质、尺寸、分辨率、帧率、Dock、系统栏 |
| Audio | 听到什么 | 媒体 / 导航音轨、音乐缓冲 |
| Navigation | 定位与导航引导 | 定位上报给 iPhone |
| Vehicle | 贴合本车与驾驶习惯 | 驾驶侧、Siri 键、车机按钮、悬浮球、应用列表、手势 |
| Diagnostics | 排障证据 | 诊断报告 |
| Advanced | 实验 / 机型相关 / 有风险 | HEVC、音频焦点、声道映射、旋转 |
| Overview | 不放新设置 | 见下文 Overview |

实验项、依赖特定固件、或默认就可能搞坏声画/连接的，进 Advanced。  
常见且默认安全的（如音轨选择）留在对应分类。  
例外：仅特定用户可见、但完成日常目标的控件可留在该目标分类（如 SoftAP 控件留在 Connection）。

实验标题后缀用 ` (experimental)`（各语言既有形式：`（实验性）` 等），不要用 `· experimental` 或 `Experimental …` 前缀。

### 写代码

1. 扩展已有卡片：在正确分类的 `filteredSection(...)` 里加控件。  
2. 新卡片：增加 `SettingsSection`，并在 `sectionsByCategory` 中映射到**唯一**分类。  
3. 在 `allSettingsSections()` 里用 `filteredSection(...)`；禁止在那里用会渲染到所有分类的裸 `section(...)`。  
4. 同一设置不得出现在两个分类。Overview 快捷项是唯一允许的重复，且必须与完整控件同一持久化；Overview 快捷项不宜超过四个。  
5. 若需重连或下次连接才生效：说明文字写清楚；仅下次生效的保存后调用 `markReconnectNeeded()`（显示「立即重连」条），**不得**未经同意断开会话。立即重连只用在「应用并重连」类按钮，或文案已写明会重连时。  
6. 新字符串写入 `common/src/main/res/values/` 及全部 `values-xx`。设置文案宜用 `settings_` 前缀（`SettingsTranslationsTest`）。  
7. 打开选择的控件用 `button()`，文案形如 `"标题 · 当前值"`。  
8. 跨分类移动控件时更新 `AdaptiveSettingsUiTest.settingsLiveWhereDriversLookForThem`。

### Overview

Overview：连接状态、分类入口、快捷设置、关于、语言。  
**不要**把新设置只加在 Overview；先加到正确分类，再视常用程度提升为快捷项。
