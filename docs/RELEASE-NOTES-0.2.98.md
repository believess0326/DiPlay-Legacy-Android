# DiPlay v0.2.98 — 华为海思硬件解码崩溃根治、亿咖通FE-6原厂热点自启、通话无声与倒车四周黑边全面消除

## 更新

- **华为海思（HiSilicon K3v2/Kirin 910）硬件解码致命崩溃根治**（Issue #324）：
  - 针对华为 Ascend P6/P7（`hi6620oem`）等老旧 Android 4.4.2 设备，其专有硬件 AVC 解码器（`OMX.k3.video.decoder.avc`）在底层 `ACodec` 初始化阶段存在驱动缺陷，会触发 native 断言 `CHECK(def.nBufferSize >= size) failed` 导致 `SIGABRT` 进程强退；
  - 在 `AndroidMediaSink` 中引入 `isKnownBrokenHardwareDecoder` 智能识别，当检测到系统默认解码器属于该已知缺陷海思硬件解码器时，自动优先调度稳定的系统软件解码器（`OMX.google.h264.decoder`），彻底绕开底层驱动崩溃，保障 CarPlay 画面流畅稳定输出。
- **亿咖通（ECARX FE-6 / Android 5.1）原厂车载热点自动开启支持**（Issue #322 / PR #323）：
  - 补充声明 `android.permission.CHANGE_NETWORK_STATE` 权限，满足 Android 5.1 及非 BYD 老旧车机 `ConnectivityManager.enforceTetherChangePermission` 对原厂车载热点控制的权限校验；
  - 在连接设置中为识别到的 `xea406m` / `ecarx.settings` 车机提供无需本地 ADB 授权的原厂热点自动启动开关，复用热点就绪等待与开机广播通道，并在 `docs/ECARX_STOCK_HOTSPOT.md` 记录详细配置与实车验证范围。
- **CarPlay 通话无声与短 Opus 语音包异常丢弃修复**（Issue #271, #272）：
  - 针对部分老旧车机在蓝牙/网络通话时出现单向或双向无声的问题，修复了通话音频流在解包与重采样时的短 Opus 包异常过滤逻辑；
  - 优化通话上行与下行链路的音频数据通道调度，确保通话语音即时平滑传递。
- **倒车退出 CarPlay 界面四周黑边与视口错位修复**（Issue #290）：
  - 针对挂倒挡退出后切回 CarPlay 出现画面视口四周黑边、分辨率缩放未占满屏幕的问题，在退出倒车广播与 Surface 重建流程中加入视口重计算与强制布局对齐刷新，确保画面百分百无缝铺满。
- **老旧车机原厂热点与外置蓝牙自启逻辑增强**（Issue #321）：
  - 优化了系统冷启动时的外置蓝牙驱动挂载时序与热点自启检测门禁，进一步提升无外设干预下的全自动连接成功率。

## 验证

- 版本 `0.2.98` / `versionCode 118`，包名 `com.shihab.diplay`，最低 Android 4.2（API 17）。
- 完整单元测试全部通过（通过针对 Robolectric API 22 的 Shadow 映射修复），发布包内嵌源码 commit 严格核验通过。
