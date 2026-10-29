# 0.2.2 包名与启动图标

日期：2026-10-29（Asia/Shanghai）。版本 0.2.2 / versionCode 10。

## 新身份

- 安装包 ID 与 Android namespace：`io.github.appcommentfilter`。
- Java 源码/测试包：`io.github.appcommentfilter.*`。
- Gradle 工程名：`AppCommentFilter`。
- Provider：`content://io.github.appcommentfilter.config/rules/<宿主包名>`，调用 UID 和独立配置校验保留。
- LSPosed 入口：`io.github.appcommentfilter.hook.ModuleEntry`。
- 应用名称仍为“评论过滤”，支持的五款宿主版本不变。

## 图标

沿用界面的森林绿 `#176B54`，以浅色评论气泡和三条递减的筛选线表示“过滤评论”，去掉原来只表示漏斗的图形。使用 Android 原生矢量资源，支持系统自适应形状、圆形和 Android 13+ 单色主题。

前景位于 108×108 画布的中央安全区域；筛选线是透明镂空，系统单色着色后仍可识别。最低 SDK 28 的默认自适应图标与 API 33 单色图标使用同一气泡几何，单色层只放在 v33 资源以保持版本隔离；默认资源的对应 Lint 提示按此明确原因局部忽略。没有引入位图图标依赖或网络资源。

预览与模拟器截图在 `branding-0.2.2/`。`icon-preview.svg/png` 从 Android 前景路径直接渲染；`launcher.png` 是实际 API 37 模拟器的桌面截图，可看到新评论气泡图标。

## 安装与旧规则

Android 把不同包名视作两个应用。新包可以与 `io.github.kuaishou.filter` 共存，不会覆盖或读取旧包私有配置；Provider 新 authority 也不会与旧包冲突。

迁移时，在旧模块逐个选择宿主，通过“更多 → 高级配置”复制 JSON；在新模块对应宿主粘贴、点“完成”并“保存规则”。确认规则已转入后，在 LSPosed 停用旧模块、启用新模块、重新勾选作用域，随后重启宿主。确认迁移完成之前保留旧应用。

同一个新包后续版本可以正常覆盖升级。仓库中 0.2.1 及更早的记录和截图保留当时的旧包名，避免改写历史证据；当前源码、入口和测试脚本已使用新包名。

## 验证

执行完整 Gradle 单元测试、Debug 构建和 Lint；检查产出 APK 的 package/version、Manifest Provider、LSPosed 入口和 DEX 类定义，不把文本替换成功等同于打包正确。DEX 中不再声明旧 `io.github.kuaishou.filter` 类，测试报告也使用新包名。

在已有旧包的 API 37 模拟器安装新包，验证二者能共存；运行 `scripts/per_app_smoke.py` 检查新包下五款宿主的配置隔离、手动保存、重启恢复、旧规则格式兼容以及 Provider 拒绝无关 UID。测试仅写入新包的隔离数据，结束后恢复原偏好和设备设置，不卸载旧包或擦除 AVD。

本轮没有重测 Android 16 + LSPosed 的五款宿主实际过滤行为；相关功能仍以原来的适配范围和真机验证边界为准。
