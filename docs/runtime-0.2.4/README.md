# 0.2.4 模拟器验证截图

此目录为 API 37 模拟器证据。`00-no-record`、`waiting`、`active`、`partial`、`failed`、`old`、`dark` 等截图由 `scripts/runtime_status_smoke.py` 生成；`*-detail` 为对应诊断弹窗。

截图中的 `com.xingin.xhs 99.0.0 / 990000` 是无代码测试包，状态由测试 instrumentation 注入，展示状态映射和持久化行为，不是小红书实际运行或新版本适配成功的证据。`results.json` 记录 Binder、权限、状态与恢复结果。

`profiles/` 为原有五应用独立配置回归；该脚本同样未执行真实宿主 Hook。

两组脚本均还原运行前的模块偏好设置和更改的模拟器显示设置，临时宿主包已卸载。
