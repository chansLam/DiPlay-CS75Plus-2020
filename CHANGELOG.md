# 更新日志

标签 `vX.Y.Z` 对应章节 `## X.Y.Z`；Release 说明取自对应章节。

---

## 1.0.0

长安 CS75 Plus 2020 车机无线 CarPlay 接收端（`OS_S311-WT` · Android 4.4.2 / API 19 · `armeabi-v7a`）。

- **CarPlay 认证**：内置 MFi 认证密钥，恢复无线 CarPlay 连接。
- **连接**：Wi‑Fi Direct 默认直连，可选车机热点（SoftAP）；混合蓝牙发现。
- **显示 / 车辆**：全屏沉浸、默认 30 fps、CarPlay 内车机应用、悬浮小白点、默认上报 GPS。
- **方控 / Siri**：方向盘媒体键直连 CarPlay HID（切歌 / 音量）；可选 Siri 键，支持窗口按键学习与 root(su) 自动启用无障碍（兼容 API 19，免 adb 弹窗）。
- **Root**：全局隐藏系统导航栏（需 root / su）。
- **终端（实验性）**：关于页连点版本号打开；修复精简 ROM 缺 `PATH`/`HOME` 导致的启动崩溃，错误提示显示异常位置。
- **系统设置**：拨号 `*#518200#*` 打开原生系统设置入口。
- **工程**：`com.shihab.diplay`，versionCode **100**；模块 `mobile` / `common` / `shared`；`minSdk` / `targetSdk` **19**。
