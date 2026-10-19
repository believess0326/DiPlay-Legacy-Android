# DiPlay v0.2.78 — 恢复无线CarPlay音频输出、全志T3重枚举死锁消除与有线TCP握手增强

## 更新内容

### 1. 恢复无线 CarPlay 强制 Opus 音频能力声明 (#244)
- **解决 Android 4.2/4.4 无线 CarPlay 无音频与控制中心无输出选项**：此前版本在构建 AirPlay InfoPlist 时将 `opusAudioOutput` 关联至底层系统解码器检测（`OpusDecoderSupport.isAvailable()`），导致缺少系统级 Opus 解码器的 Android 4.2 / 4.4 车机未在 InfoPlist 的 `audioFormats` 中声明 `0x70000000`（Opus 音频格式）。根据苹果无线 CarPlay 规范，缺少 Opus 声明会导致 iOS 直接放弃协商音频流通道，表现为车机有画面但完全无声音、iOS 控制中心无“CarPlay”音频输出选项。现已恢复为无条件强制声明 Opus 音频能力，彻底解决该问题。

### 2. 全志 T3 等老旧车机有线 CarPlay 重枚举死锁与内核卡死消除 (#243)
- **规避 Linux 3.4 内核 USB 端口复位与设备打开竞争**：在全志 T3 等低版本 Android 4.4 / Linux 3.4 架构下，USB 权限确认后如果过快调用 `openDevice`，容易与内核 `khubd` 线程的端口复位及描述符重读发生竞争，引发底层 `down(&dev->sem)` 信号量死锁与车机冻结。将低版本系统的权限建立后延迟由 150ms 适度提高至 400ms，为底层内核驱动总线重枚举预留完整时间窗口，彻底消除二次插拔卡死与看门狗重启。

### 3. NCM 网络栈全异步发包与 TCP 握手可靠性增强 (#243)
- **消除 NCM 读线程阻塞风险**：将所有 mDNS 响应帧、ICMPv6 路由器通告（Router Advertisement）及邻居通告（Neighbor Advertisement）统一送入异步 `txQueue` 队列由专门的 TX 线程发送，避免在 RX 线程中同步调用 USB Bulk OUT 写接口，防止 USB 传输锁阻塞导致接收帧丢失。
- **主动式 IPv6 默认路由通告**：在响应 mDNS 查询以及检测到 iPhone 首包到达时，主动向对端发送 ICMPv6 RA（Router Advertisement）与 NA，确保 iOS 设备在 NCM 虚拟网卡上即时配置 IPv6 默认路由与邻居缓存，避免 iOS 在 mDNS 成功解析后因等待路由通告而迟迟不发起 TCP SYN 握手（端口 7000）。

---

## 验证与发布

- **版本标识**：`versionName 0.2.78` / `versionCode 98`，最低支持 Android 4.2（API 17）。
- **签名校验**：Release APK 包含完整的 V1 + V2 签名方案，签名证书 SHA-256 与历史版本保持一致（`bca015c0cb43469fee55539b4054ff862671e7b41e6612eee0cb8566d327b7ae`）。
- **自动化测试**：全量单元测试通过，Release APK 构建与源码校验一致性确认。
