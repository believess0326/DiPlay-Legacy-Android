# DiPlay v0.3.6 — 忽略Surface重建期间过渡缩小、跳过无线会话MainAudio索回避免黑屏断连

## 更新

- **忽略 Surface 重新挂载稳定窗口内的过渡尺寸缩小**（Issue #290）：
  - 修复倒车退出高度增长后重建 Surface 期间，视图经过中间过渡缩小尺寸导致 `setFixedSize` 重新将 Surface 缩小并在 3 秒稳定窗口内持续维持小尺寸、无法收敛恢复全屏的问题；
  - 过滤该窗口内的过渡缩小，保持已恢复的目标尺寸。
- **跳过无线 CarPlay 会话的 MainAudio Untake 索回请求**（Issue #293）：
  - 修复无线连接下触发 `takeMainAudio()` 时向 iPhone 发送 Untake 请求导致 iPhone 瞬间关闭视频画面流并断开会话的严重缺陷（实测发送后约 4ms 手机关闭流）；
  - 无线模式下保持协商好的 MainAudio 所有权，仅在有线模式切回原生媒体时保留 Untake 机制。

## 验证

- 版本 `0.3.6` / `versionCode 126`，包名 `com.shihab.diplay`，最低 Android 4.2（API 17）。
- 完整单元测试全部通过，发布包内嵌源码 commit 严格核验通过。
