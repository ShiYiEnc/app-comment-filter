# 弹窗显示与键盘适配修复记录

本目录记录 2026-10-31 工作区构建在 API 37 模拟器上的验证，截图中的版本可能仍为准备发布前的 0.2.4。这里只验证模块自己的设置界面，不代表任何真实宿主 Hook 已验证。

## 根因与实现

原 `styleDialog()` 在 `OnShow` 后才修改窗口宽度、原生按钮高度、按钮行 padding 和布局参数，且随后将 decor 显式做 `translationY(6dp → 0)` 的 180ms 动画。系统窗口自己的入场动画也同时存在，因此“出现后再移动”既有明确的位移动画，也有显示后再次布局的来源。

修复统一为 `dialog.create()` → 完成窗口/内容/按钮样式与尺寸 → `dialog.show()`，确保原生控件已创建且样式在显示之前确定；OnShow 仅绑定按钮操作。窗口入场/退场使用 120ms / 90ms 的纯 alpha 动画，不包含平移或缩放。

键盘验证发现原脚本只检查“完成”节点存在，会误认为被键盘遮挡的按钮可用。现在内容滚动区按真实可见窗口尺寸计算上限，为已测量的标题和按钮栏留出空间，保留 `WRAP_CONTENT` 和 `ADJUST_RESIZE`。不固定整个弹窗高度。`keyboard-settled.png` 是发现问题时的截图，`keyboard-settled-fixed.png` 是可见高度修复后的截图。

## 验证方式

- `scripts/xhs_gender_ui_smoke.py` 验证应用选择、性别开关、添加/编辑、手动保存、Activity/进程恢复、1.3 倍字体、软件键盘、深色模式、960×1920 窄屏、帮助和高级 JSON 弹窗。测试定位会滚动查找，适应新增运行状态卡后的可视高度。
- 软件键盘断言改为检查两个操作按钮的完整 bounds：底边不超过 IME 真实顶边，且至少有 44dp 高度可见，而非仅检查节点存在。结果见 `results.json`。
- `MotionCheck.java` 和 `MotionCheckManifest.xml` 是独立的临时 instrumentation 探针源码，不属于产品 APK。探针打开模块规则编辑器，采样首次 pre-draw 及随后 40 帧的 x/y/宽/高/translation，检测显示后的几何变化。仅在指定模拟器安装，运行后卸载。

## 实测结果

- 严格 UI 脚本全部通过，键盘按钮的完整 bounds 和 IME 顶边见 `results.json`。`06-editor-keyboard.png` 已更新为可见高度修复后的最终候选截图。
- 修复候选 APK：SHA-256 `2062bf8b27b085137891c9aa5cefd6973fa7b6e0e3901a01e41fba2089eaedb4`，当时 BuildConfig 仍为 0.2.4/code12，但已经包含本目录描述的全部弹窗及可见高度修复。41 个几何采样只有一种位置/尺寸，translationX/Y 全为 0。
- 原 0.2.4 APK（`ee0743b` 发布物）：SHA-256 `b1d7587a1e6ce93148f74a5bfdbbe2897c91c02ad78405e7c1d5876343d7288c`。同一探针的 41 个采样出现 4 种几何，translationY 最大 18px（480dpi 下等于 6dp），成功复现并检测显示后位移。
- 完整逐帧对照记录见 `motion-frames.json`。测试只采样 View 几何；系统透明度合成由纯 alpha 资源约束。此候选构建记录不冒充最终 0.2.5 包的签名或版本验证；最终发布由主任务另行构建。

测试脚本还原原有模块偏好设置、字号、输入法、深色模式与屏幕尺寸；没有清空 AVD。
