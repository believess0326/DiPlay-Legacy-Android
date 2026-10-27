# DiPlay v0.3.2 — 修复老旧车机hostapd热点误判循环退出、过滤非主IPv6链路本地地址、强制重枚举直发控制请求及无线音频索回

## 更新

- **修复老旧 ROM（如本田 MY16ADA / Android 4.2）缺失 grep 导致 hostapd 热点误判未保活死循环**（Issue #337）：
  - Android 4.2 自带 toolbox 缺少 `grep` 工具，导致 `ps | grep hostapd` 管道执行时直接报 `127 not found` 返回空输出；
  - 启动阶段误判为 hostapd 进程未保活并强制执行 `teardown()` 杀掉已正常运行的 hostapd，随后陷入循环重试退出；
  - 将 hostapd 存活检测改为纯 Kotlin 原生逐行解析 `ps` 输出，不依赖任何第三方 shell 命令，并增加 3 秒轮询启动宽限期，彻底恢复 Root hostapd 车载热点启动。
- **无线 CarPlay 过滤 0x5703 载荷中的非主 IPv6 链路本地地址**（Issue #290, #339）：
  - 修复 0.3.0 引入的全网卡地址通告导致 iPhone 优先连接无作用域绑定的 IPv6 link-local 地址、造成 SYN 无法匹配且 30 秒 TCP 超时断连的问题；
  - 仅保留主地址、全部 IPv4 地址及非 link-local 的 IPv6 地址，消除 iOS 15/16 等设备在全志 T3 及类似车机上的无线连接失败。
- **有线 CarPlay 强制重枚举直发 Vendor 0x51 控制请求**（Issue #335）：
  - 修复强制重枚举时被 fast-probe 快捷分支拦截、复用失效旧配置而未实际向 iPhone 发送 0x51 vendor 切换请求的逻辑漏洞；
  - 确保 forced 模式下必发 0x51 控制传输并保留 KitKat 硬件延时防抖。
- **恢复无线模式下原车音频 Untake 索回能力**（Issue #312, #316）：
  - 纠正无线后台保活改动中误屏蔽 `takeMainAudio()` 的问题；
  - 保持 Activity 恢复时不重复强夺屏幕所有权（`takeMainAudioAndScreen` 针对无线平滑复用），同时恢复从原车广播/原生蓝牙切回 CarPlay 时的 `Untake MainAudio` 协议索回，消除静音缺陷。
- **音频闪避（Audio Ducking）诊断数值记录**（Issue #309）：
  - 在 AirPlay 会话日志中详细记录 duckAudio 与 unduckAudio 的 `volume` 和 `durationMs`，便于排查车机音频闪避后无法恢复原音量的问题。

## 验证

- 版本 `0.3.2` / `versionCode 122`，包名 `com.shihab.diplay`，最低 Android 4.2（API 17）。
- 完整单元测试全部通过，发布包内嵌源码 commit 严格核验通过。
