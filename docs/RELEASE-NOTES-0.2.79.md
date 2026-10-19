# DiPlay v0.2.79 — CM390外置蓝牙iPhone搜索不可见修复、直角画面消除黑边与全志T3版本兼容

## 更新内容

### 1. CM390 外置 USB 蓝牙适配器 iPhone 搜索不可见与配对增强 (#246)
- **补全 EIR 经典蓝牙音频服务 UUID 广播**：针对绿联 CM390 / Actions / Realtek 系列外置 USB 蓝牙，在 Extended Inquiry Response (EIR) 广播中补充 16-bit 服务类别 UUID（Tag 2：`0x111E` Handsfree Audio 与 `0x1203` Generic Audio）。解决 Class of Device 设为免提设备（`0x200408`）时，因缺失标准音频服务标识导致 iPhone“设置 → 蓝牙”过滤隐藏该设备的问题。
- **优化查询扫描与寻呼扫描射频监听参数**：通过 `HCI_Write_Inquiry_Scan_Activity` 与 `HCI_Write_Page_Scan_Activity` 将芯片默认极低占空比的扫描窗口优化为 1.28s 间隔与 40ms 窗口（配合 Interlaced 扫描），大幅提升 iPhone 突发扫描的命中率与连接响应速度。
- **消除空配对状态下的循环寻呼超时阻塞**：当外置 USB 蓝牙已启用但尚未绑定新手机时，无线连接调度不再抓取车机原生 Android 蓝牙已配对设备发起盲目寻呼，避免单次持续 5.12 秒的 Page Timeout（`status=0x4`）独占控制器射频状态，使适配器能够持续稳定响应外界 iPhone 的查询与入站配对。
- **广播名称统一与自检恢复**：芯片本地名称（`Write_Local_Name`）、EIR 广播名与界面展示严格对齐（`DiPlay-XXXX`）；诊断中的外置蓝牙硬件自检完成后自动重新激活广播。

### 2. 直角画面显示与黑边消除
- **优化 AirPlay 视图协议层声明**：针对部分车机由于 safeArea 内边距声明导致推流画面四周产生微小圆角或多余黑边的问题，移除无额外裁切要求下的边距约束，呈现无黑边直角全屏显示。

### 3. 全志 T3 及特定 ROM 版本兼容（realSdkInt 防虚报）
- **统一系统真实版本判断**：对媒体播放、定位、加密套件和热点检测中的版本分支统一接入 `realSdkInt` 底层真实版本校验，避免部分老旧车机（如全志 T3、定制安卓 4.4 ROM）虚标高版本 Android 系统属性导致误调高版本 SDK API 引发 NoSuchMethod 崩溃。

---

## 验证与发布

- **版本标识**：`versionName 0.2.79` / `versionCode 99`，最低支持 Android 4.2（API 17）。
- **签名校验**：Release APK 包含完整的 V1 + V2 签名方案，签名证书 SHA-256 与历史版本保持一致（`bca015c0cb43469fee55539b4054ff862671e7b41e6612eee0cb8566d327b7ae`）。
- **自动化测试**：全量单元测试与 Lint 检查通过，Release APK 构建与源码校验一致性确认。
