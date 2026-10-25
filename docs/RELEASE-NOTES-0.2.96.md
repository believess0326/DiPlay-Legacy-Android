# DiPlay v0.2.96 — 倒车退出黑屏死锁根治、老旧车机首帧送显与诊断兼容、全志T3内核死锁穿透及外置蓝牙优化

## 更新

- **倒车退出黑屏死锁与动态画面崩溃根治**（Issue #320 / #315）：
  - 彻底解决在地图等动态推流界面挂倒车、切回瞬间因积压帧触发异常恢复导致 1 秒内连续 187 次死循环强杀解码器的严重缺陷。
  - 增加采样窗口即时清零与 3 秒 Debounce 防抖；在 Surface 重新挂载时主动丢弃倒车期间积压的过时历史帧；管线异常恢复调整为非破坏性（`allowRebuild = false`），只重置参考链索要 IDR 关键帧，不再暴力注销解码器实例，彻底避免老旧车机底层驱动陷入 `native_configure: -2` 永久死锁。
  - 补全掌讯、AutoChips、鼎微等常见车机方案的退出倒挡广播（`com.autochips.backcar.stop`、`xy.android.intent.action.BACKCAR_OFF`、`action.backcar.stop` 等）。
- **老旧车机 API 17 诊断报告导出崩溃修复与画面响应优化**（Issue #319）：
  - 针对掌讯 AC8227L 等伪装 4.4.4 但底层实为 Android 4.2（API 17）的车机，在导出诊断报告时使用反射安全调用 `Context.getExternalFilesDirs` 并以 `Throwable` 级别兜底捕获，彻底消除 JVM 级 `NoSuchMethodError` 弹窗崩溃，平滑回退至内部存储导出。
  - 修复连接初期首帧出图慢被退化检测误杀导致的屏幕滑动无响应问题，恢复正常的触控送显响应。
- **全志 T3 篡改假版本号穿透与内核自旋锁死锁根治**（Issue #318）：
  - 针对部分全志 T3 车机第三方 ROM 篡改 `Build.VERSION.SDK_INT` 假报 Android 8.1 导致绕过退避保护的问题，改用 `realSdkInt() <= KITKAT` 结合真实内核与基带底层版本穿透假版本号。
  - 将 USB 重枚举退避延时放宽至 250ms，彻底消除 Linux 3.4 老旧 `sw_usb` 驱动自旋锁死锁、看门狗复位及 RTC 时钟重置（2018年）问题。
- **外置 USB 蓝牙适配器（绿联 CM390）无线连接阻塞与超时优化**（Issue #316）：
  - 在 iAP2 蓝牙控制通道增加 12 秒静默信道快速超时，当 iPhone 持续未在 RFCOMM 通道应答时快速中断阻塞信道，使已建立就绪的 Wi-Fi 控制通道能够快速完成接管并进入 CarPlay 运行状态。
- **蓝牙音频切回无声与先连蓝牙卡死握手问题修复**（Issue #312）：
  - 修复先连原车蓝牙时 CarPlay 握手阻塞问题及音频路由切回恢复。

## 验证

- 版本 `0.2.96` / `versionCode 116`，包名 `com.shihab.diplay`，最低 Android 4.2（API 17）。
- 完整单元测试全部通过，发布包内嵌源码 commit 严格核验通过。
