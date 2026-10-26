# DiPlay v0.2.97 — 原车蓝牙切回无声修复、外置USB蓝牙开机自启与无线交接死锁根治、倒车退出导航黑屏锁死消除

## 更新

- **原车蓝牙切回 CarPlay 静音无声问题彻底根治**（Issue #312）：
  - 修复索回主音频时控制通道 transferType 语义错误：在 Apple CarPlay 规范中，配件发送 `transferType=1` (Take) 代表车机强占音频，iOS 判定车机在使用本地音源，会完全切断向 CarPlay Wi-Fi 链路的音频推流；
  - 将索回主音频（`takeMainAudio` 及 `takeMainAudioAndScreen`）修正为正确的 `transferType=2` (Untake，交还音频所有权给 iPhone)，切回 CarPlay 界面时 iOS 即刻恢复推流，实现原车蓝牙与 CarPlay 媒体音频无缝秒切。
- **外置 USB 蓝牙适配器（绿联 CM591/CM390 等）开机自启与默认授权记忆**（Issue #321）：
  - 新增专用的 `UsbBtDeviceAttachedActivity` 与 `usb_bt_device_filter.xml` 静态设备过滤器（精确匹配无线控制器 Class `0xE0/1/1` 及 Actions ATS2851、Haylou、Realtek、CSR 等芯片 Vendor ID），使 Android 系统能真正持久化保存“默认使用此应用打开”的授权勾选，消除每次开机重复弹窗；
  - 移除开机恢复逻辑（`restoreUsbBluetooth`）中对 `hasPermission` 的阻碍门禁，冷启动检测到外置蓝牙时直接触发挂载并针对全志 T3 等带 root 固件自动静默提权；在设置页点击授权时自动同步自启偏好。
- **无线 CarPlay 控制通道运行中卡死与 USB 蓝牙交接死锁消除**（Issue #316）：
  - 根除“卡在‘无线 CarPlay 控制通道运行中’，必须手动拔下 USB 蓝牙才能继续”的互相等待死锁：在 `RfcommLayer.close()` 退出时补齐向所有活跃 DLC 及 DLCI 0 发送标准 `RFCOMM_DISC` 断开控制帧；
  - 在 `UsbBtBluetoothAdapter` 引用计数归零时无条件向蓝牙芯片发送 `OP_DISCONNECT` 注销底层物理 ACL 连接，杜绝残留假死链路；
  - 在 TCP 连接成功建立后引入主动交接看门狗：若 2.5 秒内 iPhone 未启动 RTSP 交互，车机主动触发释放蓝牙引导通道，在代码层面自动完成交接动作，打破死锁；并补充 15 秒超时自愈。
- **倒车退出后高德地图动态导航黑屏锁死全面消除**（Issue #320 / #315）：
  - 彻底解决高德地图等动态高负载推流界面挂倒挡退出后 100% 黑屏锁死的问题：在 Surface 重建挂载时立即重置 `consecutiveInputWithoutOutput` 与 `lastOutputRenderedNs`，引入 3.5 秒流水线预热宽限期，彻底消除高帧率地图突发涌入被误判为 output stalled；
  - 在 `setSurface` 挂载瞬间优先清空倒车期间队列积压的过时历史帧；
  - 将 output stalled 恢复全面调整为非破坏性（`allowRebuild = false`），只向 iPhone 请求最新 IDR 关键帧，严禁强杀销毁硬件解码器，杜绝 MTK 8227L 底层驱动崩溃死锁。
- **免 Root 车机蓝牙栈重置死锁与 RFCOMM 握手防竞争优化**（Issue #312）：
  - 在老旧 Android 4.2 / Bluedroid 底层增加取消搜索的异步停止确认等待，并在 insecure RFCOMM 端口回退前引入 100ms 链路静止延时，彻底消除 `Device or resource busy` 报错；
  - 在蓝牙连接重试时增加系统标准 API（`adapter.disable() / enable()`）兜底复位，免 root 亦可自动复位卡死的蓝牙栈。

## 验证

- 版本 `0.2.97` / `versionCode 117`，包名 `com.shihab.diplay`，最低 Android 4.2（API 17）。
- 完整单元测试全部通过，发布包内嵌源码 commit 严格核验通过。
