# 快手两版共用的原生 Footer 绑定

## 输入与静态证据

| APK | SHA-256 |
| --- | --- |
| `快手_14.9.20.50799.apk` | `74f25f5e5fb05ebd1b31988664497eb997f51e8d4c8e73c97b799ecf041ccfff` |
| `快手_14.9.30.50933.apk` | `ec3caca3406f264c7c6fba3ebd0fa95a0dfd3af92dfd2d242b0ec679b24f215f` |

两版均使用原生 `RecyclerFragment` 的包装 Adapter 挂载 footer，以下差异由 APK 声明和选定类反编译核对，未执行宿主：

| 边界 | 14.9.20 | 14.9.30 | 选择办法 |
| --- | --- | --- | --- |
| 包装 Adapter 类型 | `pcl.f` | `tdl.f` | 来自 `RecyclerFragment` 的 Adapter 子类字段，并验证 footer anchors 和 `h` Adapter 字段 |
| 评论参数 getter | `Tq()` | `Wq()` | 唯一无参、返回稳定 `CommentParams` 的实例方法 |
| 原生 RecyclerView getter | `j0()` | `j0()` | 两版共用边界 |
| 原生 pager getter | `n()` | `n()` | 两版共用边界 |
| pager 最新响应 getter | `j0()` | `k0()` | pager 接口上唯一无参 `Object` 返回方法 |
| footer 包含/移除 | `l2(View)` / `E2(View)` | 同左 | 两个方法共同读取的实例 group 字段 |
| footer group | `b:pcl.f$c` | `b:tdl.f$c` | DEX `iget-object` 读取交集，而非任选同形字段 |
| footer 添加入口 | `Q1(View)` | `c2(View)` | 唯一 `View -> void` 方法，调用读取 footer group 的 `(View,LayoutParams) -> void` helper |
| 添加 helper | `e2` | `e2` | DEX 调用与 group 读取指纹 |

同一 wrapper 还有对称的 header group `a` 和 header 添加链。选择器根据 `l2/E2` 确定 footer group，只接受读取该 group 的 helper，拒绝把 header 操作当 footer。未来如果有多个符合者或缺少证据，会拒绝绑定；不通过随意猜测混淆名称继续挂载。

`mq` 在两版中返回类型从 `void` 改为 `boolean`。它原来只用于触发 readiness，现已取消该 Hook；RecyclerView getter 和共有 `onResume` / `onPageSelect` 提供激活信号，`onPause` / `onPageUnSelect` / `onDestroyView` 继续负责退出。

## 生命周期

`CommentFooterController(loader, pageList, apks, onReady)` 只绑定和预解析精确 wrapper 类，不在评论展示或 footer 更新回调中读取 APK。

显式 `install()` 安装 Hook，部分安装失败会回滚；`uninstall()` 撤销 Hook，清空列表绑定，并在主线程清理已挂载的原生 footer。原来的宿主 footer group、布局检查、可见评论页与 photo/响应身份校验保持不变，没有创建 overlay 或新窗口。

Footer 作为可选分页 UI 由调用方捕获绑定失败，不能因此阻止独立的主评论过滤 Hook。

## 验证

新增 6 项纯 Java 测试覆盖改名入口选择、header 排除、多个候选拒绝、错误 group/写入或静态读取排除、wrapper 结构唯一性，以及参数 getter/pager getter 的改名与歧义。

使用实际 APK 和运行时同一 `DexMethodIndex` / `KuaishouFooterBindings` 代码执行离线探针，输出为：

```text
owner=Lpcl/f; add=Q1 helper=e2 group=b groupType=Lpcl/f$c;
owner=Ltdl/f; add=c2 helper=e2 group=b groupType=Ltdl/f$c;
```

可用 `scripts/KuaishouFooterProbe.java` 与上述两个源文件一同编译后，传入 APK 路径和精确 wrapper descriptor 复核。该探针只解析 APK，不能证明 LSPosed 挂钩、真实评论区 footer 显示和连续分页在设备上已经通过；这些仍需真机验证。
