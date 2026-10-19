# DiPlay v0.2.77 — 独立导航音频焦点适配、外置蓝牙RFCOMM流控与SSP配对增强

## 更新内容

### 1. 独立导航播报音频焦点适配与静音修复 (#214)
- **独立导航音轨获取临时音频焦点**：针对部分车机（如特定后装 Android 车机及原厂系统）在无活跃音频焦点时强制对音频硬件输出静音、导致 CarPlay 导航提示音完全无声的问题，允许单独播放导航时获取临时音频焦点（`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`），促使车机底层打开音频输出通路。
- **媒体与通话叠加无感机制**：当车机同时有媒体音乐或通话流播放时，导航音轨继续保持不抢焦点的静默叠加特性，绝不打断或压制正在播放的音乐与电话。

### 2. 外置 USB 蓝牙 RFCOMM 信用流控与 SSP 配对认证增强
- **Credit-Based 流控协商与帧分发**：借鉴 FreeAuto 开源方案，在 `RfcommLayer` 中全面引入基于信用（Credits）的滑动窗口流控机制与 PN（Parameter Negotiation）信用授予，杜绝无线 iAP2 高吞吐数据传输时对端丢包或阻塞。
- **外置蓝牙 SSP 配对代理完善**：优化 `UsbBtPairingAgent` 对 SSP（Secure Simple Pairing）数值比较与即时配对的确认流程，大幅提升主流外置蓝牙适配器（Actions、Realtek、CSR 等）与 iOS 17/18 的握手成功率。
- **自动 USB 设备授权辅助**：引入 `UsbDefaultGrant` 辅助机制，在系统支持时快速确立 USB 蓝牙设备权限。

---

## 验证与发布

- **版本标识**：`versionName 0.2.77` / `versionCode 97`，最低支持 Android 4.2（API 17）。
- **签名校验**：Release APK 包含完整的 V1 + V2 签名方案，签名证书 SHA-256 与历史版本保持一致（`bca015c0cb43469fee55539b4054ff862671e7b41e6612eee0cb8566d327b7ae`）。
- **自动化测试**：全量单元测试、Lint 扫描及多架构构建全部通过。
