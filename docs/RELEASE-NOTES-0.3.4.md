# DiPlay v0.3.4 — 视频解码器重建修复反复卡顿、自动断开手机蓝牙、earpiece 扬声器修复及 hostapd 稳定性增强

## 更新

- **修复视频解码器在反复输出停滞后无法恢复问题**（Issue #346）：
  - 当视频输出端口反复停滞超过阈值时，强制重建解码器并以全新 IDR 帧冷启动，消除长时间驾驶后 CarPlay 画面定格/花屏无法自愈的问题。
- **无线 CarPlay 连接后自动断开手机蓝牙（实验性）**（Issue #343）：
  - 新增实验性开关：CarPlay 无线连接建立后，自动断开 iPhone 与车机的蓝牙配对，避免车机蓝牙与 CarPlay 同时占用音频通道导致的冲突与杂音。
- **修复 pre-API 24 车机耳机扬声器音量修复不生效**（Issue #343）：
  - 对 API 24 以下的老旧车机正确应用耳机扬声器（earpiece loudspeaker）音量修正，消除部分车型音量偏低或无声问题。
- **hostapd 启动失败时在前台运行一次以捕获原因**（Issue #337）：
  - hostapd 热点启动失败时，在前台同步执行一次以采集真实错误输出，写入诊断日志，便于在无 root 的车机上精准定位热点启动失败原因。
- **svc 无法创建 wlan0 时回退到 app_process 无线助手**（Issue #301）：
  - 当系统 `svc wifi enable` 命令未能创建 wlan0 接口时，自动回退到 app_process radio 助手路径，提升在部分 AOSP 车机上无线热点的兼容性。
- **Apple vendor IE 开关用于 root hostapd 后端（实验性）**（Issue #344）：
  - 新增实验性选项，允许在 root hostapd 模式下控制是否广播 Apple vendor IE，解决部分严格固件因 vendor IE 异常拒绝 iPhone 关联的问题。
- **保留 RFCOMM 失败原因链至引导日志**（Issue #293）：
  - 无线引导失败时完整保留 RFCOMM 原因链，而非截断为通用错误，提升无线连接诊断精度。
- **修复 AAPT 字符串编译因多余撇号报错**（Issue #343）：
  - 移除 i18n 字符串中导致 AAPT 编译失败的非转义撇号，修复受影响语言的构建错误。

## 验证

- 版本 `0.3.4` / `versionCode 124`，包名 `com.shihab.diplay`，最低 Android 4.2（API 17）。
- 完整单元测试全部通过，发布包内嵌源码 commit 严格核验通过。
