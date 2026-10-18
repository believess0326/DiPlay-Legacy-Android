# DiPlay v0.2.76 — 全志T3有线连接二次打开死锁与配置复用修复、拔出重置保护及蓝牙日志抑制

## 更新内容

### 1. 全志 T3 有线连接二次打开死锁与配置复用修复 (#221, #227)
- **底层 USB 打开 settle 延时缓冲**：针对全志 T3（Android 4.4.2 内核 3.10）在关闭探查连接与立即重新打开底层 USB 连接时，`sunxi-ehci` 与 Linux 内核 usbfs 驱动锁竞争导致线程进入不可中断 D-state 死锁的问题，在底层连接打开前加入 100ms 驱动释放缓冲延时，彻底根除系统看门狗触发软重启问题。
- **CarPlay 配置直接复用**：将探查阶段已获取的 `CarPlayUsbConfiguration` 透传至 `openDataPaths` 与 `openIap2UsbSession`，直接复用已有配置，跳过重复的 EP0 控制传输与描述符请求，大幅降低 USB 总线开销与竞争风险。

### 2. 拔出重置异常抑制 (#227)
- **拔出状态感知与 reset 保护**：在 iPhone 物理拔出触发批量写传输失败时，严格判定当前会话是否处于 `closed` 或已中断/拔出状态；严禁向物理断开的设备发送 `resetDevice` (`ioctl(USBDEVFS_RESET)`)，避免内核驱动崩溃。

### 3. 未插外置蓝牙适配器车机日志刷屏抑制 (#227, #229)
- **精准活跃状态判定**：针对未插入外置 USB 蓝牙适配器的车机，在执行有线重试或模式切换时，静默跳过周期性 `USB Bluetooth shut down` 日志打印，保持车机运行与调试日志清爽。

### 4. Android 4.2 (API 17) 有线连接 TLS KeyManagerFactory 兼容与系统蓝牙异常过滤 (#234, #235)
- **KeyManagerFactory 算法多级回退**：解决 Android 4.2.2（API 17）缺失 `PKIX` KeyManagerFactory 算法导致在 `Lockdown pairing` 阶段抛出 `NoSuchAlgorithmException` 并误判配对记录失效的问题；新增平台默认算法（`X509`）、`PKIX` 及 `SunX509` 动态回退机制，并在底层 SSLEngine 上显式启用 TLSv1.2 协议套件，彻底疏通 Android 4.2 车机有线 CarPlay 通道。
- **系统蓝牙禁用状态过滤**：在系统自带蓝牙处于关闭状态时，禁止回退调用 `android.openRfcommStream`，避免抛出 `Bluetooth is off` 覆盖外置 USB 蓝牙适配器的真实建链错误。

### 5. 在线更新下载 IPv4 优先与镜像容灾 (#236)
- **IPv4 DNS 优先与超时连接重试**：针对全志 T3 等老旧 Android 4.4 车机因 Wi-Fi 或热点 IPv6 路由不可达导致 `ghproxy.net` 在 IPv6 (AAAA) 地址上阻塞 12 秒超时的缺陷，增加 DNS IPv4 (A 记录) 优先排序并在 OkHttp 上启用连接故障自动重试，消除更新下载阻塞报错。
- **扩展国内镜像与域名安全白名单**：增加 `gh-proxy.com` 备用国内镜像，并在白名单中支持其域名，确保在单一镜像波动时无缝自动回退。

---

## 验证与发布

- **版本标识**：`versionName 0.2.76` / `versionCode 96`，最低支持 Android 4.2（API 17）。
- **签名校验**：Release APK 包含完整的 V1 + V2 签名方案，签名证书 SHA-256 与历史版本保持一致（`bca015c0cb43469fee55539b4054ff862671e7b41e6612eee0cb8566d327b7ae`）。
- **自动化测试**：全量单元测试、Lint 扫描及多架构构建全部通过。
