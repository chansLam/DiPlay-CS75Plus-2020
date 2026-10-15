# 安装与使用

面向 **长安 CS75 Plus 2020**（`OS_S311-WT` · Android 4.4.2 / API 19）。请在停车后操作。

## 目标与范围

| 项目 | 说明 |
| --- | --- |
| 车型 / 固件 | CS75 Plus 2020 · `OS_S311-WT` |
| ABI | 仅 `armeabi-v7a` |
| 连接 | **无线-only**（Wi‑Fi Direct，默认）；可选车机热点（SoftAP） |
| 不支持 | 有线 USB CarPlay（车机无法成功创建 TUN 虚拟网卡）、其他车机、他车 OEM 仪表 / HUD |

独立开源接收端，**非** Apple 认证。默认 **30 fps**；HEVC 等实验项默认关闭。

## 安装

1. 从 [Releases](https://github.com/chansLam/DiPlay-CS75Plus-2020/releases) 下载 APK（文件名见 [`site/config.json`](../site/config.json)，当前 `DiPlay-v1.0.0-release.apk`）。
2. 若已安装**其他签名**的 DiPlay，先彻底卸载。
3. 用车机文件管理器安装，或 `adb install -r <apk>`。**不要**装到 iPhone。
4. 打开 DiPlay；KitKat 上车机权限多为安装时授予。关掉其他投屏 / 手机互联应用。

## 连接

1. **设置 → 连接** 完成连接设置（连接方式、选择 iPhone 等）；默认 **Wi‑Fi Direct** 直连，也可选车机热点（SoftAP）。
2. 默认直连即可；若选车机热点，打开或加入**车机热点**，iPhone 连上同一热点并保持蓝牙开启。
3. 点 **连接手机**；在 iPhone 上允许 CarPlay。

**Wi‑Fi Direct 与车机热点已在 2020 款 CS75 Plus（OS_S311-WT）实车测试可用**。Existing Wi‑Fi 未作本车交付验证。播音乐时车机蓝牙可设为「仅通话」。

## 设置速览

CarPlay 内向下滑动打开设置（手指数见 **设置 → 车辆**），或回 DiPlay 主页进入设置。

| 分类 | 常见项 |
| --- | --- |
| 连接 | 热点、选手机、车机蓝牙地址 |
| 显示 | 尺寸、分辨率、帧率、昼夜、系统栏；Root 全局隐藏导航栏（需 su） |
| 音频 / 导航 | 音轨、缓冲、定位上报 |
| 车辆 | 驾驶侧、Siri 键、悬浮小白点、应用列表 |
| 诊断 / 高级 | 诊断报告；安卓终端（实验性）；HEVC 等实验项 |

## 反馈

**设置 → 诊断 → 保存诊断报告**，把 `.txt` 附到 Issue，并写明固件、iPhone/iOS 与步骤。  
**不要**写热点密码或配对材料。隐私与密钥约定见 [SECURITY.md](../SECURITY.md)。
