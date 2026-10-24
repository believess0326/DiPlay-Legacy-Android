# DiPlay v0.2.92 — Bonjour 广播与蓝牙并发引导、CarPlayStartSession 容错重发与 hostapd 状态探测优化

## 更新

- **Bonjour 广播与蓝牙 Bootstrap 并发引导加速（#293）**：
  - 优化无线 CarPlay 启动时序，将 mDNS / Bonjour 局域网服务广播与蓝牙引导流程并行拉起，消除以往串行等待的耗时。
  - iPhone 经由蓝牙接收到连接信息后，可在第一时间无缝探测到车机 AirPlay 服务，无线连接建链速度得到明显提升。
- **iPhone 静默无响应时自动重发 CarPlayStartSession（#293）**：
  - 针对部分手机或弱信号信道下初次握手帧偶发丢失导致手机无应答的问题，引入重发检测机制。
  - 在检测到手机暂未响应时自动重传 CarPlayStartSession，有效避免无线连接卡在等待界面的超时现象。
- **消除 hostapd 存活检测中的 shell 管道依赖（#308）**：
  - 去除活跃状态检测脚本中对 `wc -l`、`tail` 等复杂管道的依赖，直接基于进程 PID 与退出码进行精准探测。
  - 完美兼容各类裁剪版 busybox / toybox 的底层 shell，杜绝误判 hostapd 掉线引发的异常重连。

## 验证

- 版本 `0.2.92` / `versionCode 112`，包名 `com.shihab.diplay`，最低 Android 4.2（API 17）。
- 完整单元测试全部通过，发布包内嵌源码 commit 严格核验通过。
