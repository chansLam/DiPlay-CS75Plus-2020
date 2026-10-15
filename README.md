# DiPlay

**把 iPhone 的 CarPlay，无线投到长安 CS75 Plus 2020 车机。**

开源无线 CarPlay 接收端，专为长安 CS75 Plus 2020 原厂车机（OS_S311-WT · Android 4.4.2 / API 19）打造。Wi‑Fi Direct 直连、全屏沉浸，内置 MFi 认证密钥，无需越狱、转接盒或账号。

> 独立实现，非 Apple 认证，与 Apple / 长安无隶属关系。请在安全停车后操作。

---

## 亮点

- **CarPlay 认证** —— 内置 MFi 认证密钥，无线直连即用
- **全屏沉浸** —— CarPlay 铺满车机宽屏，适配左侧系统导航栏，约 30 fps
- **悬浮小白点** —— 在 CarPlay 里点一下返回 DiPlay 主页，长按回到 CarPlay
- **车机应用** —— 直接在 CarPlay 里打开车机原生应用，长按可卸载
- **方控 / Siri** —— 方向盘媒体键直连 CarPlay（切歌 / 音量）；可选 Siri 键，支持窗口按键学习与 root(su) 自动启用无障碍（兼容 API 19，免 adb 弹窗）
- **Wi‑Fi Direct 直连** —— iPhone 连上即用；也可用车机热点（SoftAP）
- **隐藏导航栏** —— 全局隐藏系统导航栏（需 root / su）
- **内置终端** —— 车机上执行排查命令（实验性）
- **系统设置** —— 拨号 `*#518200#*` 打开原生系统设置

## 安装

1. 若装过其他签名的 DiPlay，请先卸载。
2. 从 [Releases](https://github.com/chansLam/DiPlay-CS75Plus-2020/releases) 下载 APK，**安装到车机**（不是 iPhone）。
3. 打开 DiPlay，iPhone 连上车机热点并保持蓝牙开启。
4. 点「连接手机」，在 iPhone 上允许 CarPlay。

完整步骤与设置见 [docs/INSTALL.md](docs/INSTALL.md)。

## 文档

| 文档 | 说明 |
| --- | --- |
| [安装与使用](docs/INSTALL.md) | 安装、连接、设置速览 |
| [构建与发布](docs/BUILD.md) | 环境、签名、Actions、应用内更新 |
| [安全与隐私](SECURITY.md) | 密钥与隐私说明 |
| [版本说明](CHANGELOG.md) | 各版本功能记录 |

## 反馈

遇到问题请在 [Issues](https://github.com/chansLam/DiPlay-CS75Plus-2020/issues) 反馈，附上「设置 → 诊断」导出的报告、固件版本与复现步骤。

## 协议

GPL-3.0。CarPlay 归 Apple Inc. 所有。
