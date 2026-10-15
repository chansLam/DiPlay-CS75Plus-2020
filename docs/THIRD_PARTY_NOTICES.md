# 致谢与许可

## 上游

本专版的 KitKat / SoftAP 适配感谢：

| 上游 | 说明 | 跟踪分支 | 最新提交（移植对照） |
| --- | --- | --- | --- |
| [diplay-changan](https://github.com/c80361619/diplay-changan) | 长安 CS75（2018）Android 4.4.2 / i.MX6 | `kitkat` | [`129bb1f`](https://github.com/c80361619/diplay-changan/commit/129bb1f) · 2026-10-14 · 支持车机导航栏强力隐藏全屏与桌面快捷悬浮小白点 (v1.0.2-cs75) |
| [DiPlay-Legacy-Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android) | Legacy / KitKat 兼容 | `main` | [`a73dfa0`](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/commit/a73dfa0) · 2026-10-14 · fix: 修复 release 编译兼容问题 |

目标机型为 CS75 Plus **2020** SoftAP，并非上述仓库的直接 fork；合入为改写移植（port）。建议 commit：`merge(changan|legacy): port <hash> …`。

移植前：`git fetch` 上游，与表中 tip 对照；有更新则审完后把「最新提交」改成新 tip。不引入有线 USB、TUN、BYD HUD / cluster。

本仓库自研（悬浮小白点、车机应用列表、全屏沉浸、SoftAP 专版裁剪、Root 全局隐藏导航栏、安卓终端等）不记入上表。

## 谱系与依赖

- 接收端谱系：[xcertplay](https://github.com/shilapi/xcertplay) 等 — 根目录 `LICENSE` 为 **GPL-3.0**。  
- 界面：`DiPlayActivity` 等衍生自 [DiAuto](https://github.com/shihabal3amri/DiAuto)（**AGPL-3.0**，见 [`docs/licenses/DiAuto-AGPL-3.0.txt`](licenses/DiAuto-AGPL-3.0.txt)）。  
- 更早上游曾致谢 LIVI、Showcase 等；源码署名保留。  
- 依赖摘要：AndroidX / Kotlin（Apache 2.0）、Bouncy Castle、JmDNS（Apache 2.0）、SLF4J（MIT）；可选 SpeexDSP、软件 libopus（BSD）。详见 [`docs/licenses/dependencies/`](licenses/dependencies/)。  
- CarPlay 图标归 Apple Inc.；使用不代表认证。  
- offline-MFi 不随公开 Git 分发，见 [SECURITY.md](../SECURITY.md)。
