# DiPlay v0.2.80 — 修复外置蓝牙配对认证超时与自检状态、消除有线模式拔插重连死机

## 更新内容

### 1. CM390 / Actions / Realtek 外置蓝牙配对认证与状态自愈
- **补全异步链路认证指令事件追踪**：HCI 事件调度器将 `OP_AUTHENTICATION_REQUESTED`（0x0411）与 `OP_SET_CONNECTION_ENCRYPTION`（0x0413）纳入异步链路指令追踪。此前认证指令因仅等待 Command Complete 事件而提前超时退出，现与 Connect / Disconnect 一致，等待 Command Status 并正确承接后续的 Link Authentication Complete 与 Encryption Change 事件，彻底解决 iPhone 连接外置蓝牙时在配对认证阶段超时失败的问题。
- **自检前后蓝牙 Dongle 状态管理与自动恢复**：修复诊断界面运行外置蓝牙硬件自检时未先断开旧连接、自检后未正确恢复无线广播状态的问题，避免自检后外置蓝牙处于未初始化离线状态。
- **提升射频扫描占空比与兼容性回退**：将 Inquiry Scan 射频活动占空比提升至 25%（640ms 间隔 / 160ms 窗口），Page Scan 提升至 80ms 窗口，显著提高 iPhone 突发扫描命中率与响应速度；对不支持 Interlaced 交错扫描的老旧蓝牙芯片自动回退至 Standard 标准扫描。

### 2. 有线模式拔插重连阻塞与车机死机消除
- **NCM 底层异步传输快速中止机制（markAborted）**：拔出 USB 数据线或有线链路发生异常时，快速将传输状态标记为 Aborted，立即清空待发送队列并中断 USB bulk transfer 挂起请求，避免底层 JNI / USB 线程死锁导致车机系统无响应甚至触发看门狗重启。
- **重构有线连接断开生命周期管理**：在车机处于等待 iPhone 有线插入重连状态时，避免无序重复注销与全量重新初始化，维持平稳监听，杜绝频繁拔插造成的系统级假死。

---

## 验证与发布

- **版本标识**：`versionName 0.2.80` / `versionCode 100`，最低支持 Android 4.2（API 17）。
- **签名校验**：Release APK 包含完整的 V1 + V2 签名方案，签名证书 SHA-256 与历史版本保持一致（`bca015c0cb43469fee55539b4054ff862671e7b41e6612eee0cb8566d327b7ae`）。
- **自动化测试**：全量单元测试与 Lint 检查通过，Release APK 构建与源码校验一致性确认。
