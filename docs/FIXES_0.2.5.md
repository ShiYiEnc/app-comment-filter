# 0.2.5 反馈问题修复

2026-10-31，模块包名不变，可覆盖 0.2.4 安装。安装后强制停止并重新启动相应宿主，开启并保存过滤，再刷新有主评论的评论区。

## 四项反馈

| 问题 | 已找到的原因 | 修改与证据 |
| --- | --- | --- |
| 弹窗出现后停顿并偏移 | OnShow 才改窗口/按钮几何，同时执行 6dp translation，与系统动画叠加 | 显示前 create/style，纯 alpha；原包 41 帧有 4 种几何，修复候选 41 帧只有 1 种，translation 均为 0。键盘、大字、窄屏、深色等已验证，见 [弹窗记录](dialog-fix-0.2.5/README.md) |
| 小红书留下展开回复条目 | 独立 nr7.c 回复控制模型不实现原评论接口；另有评论包装行 | 按 commentId 精确关联主评论，同时解包折叠/点踩行；保留其他线程和未知归属。见 [静态核对和测试](XHS_REPLY_CONTROLS_FIX.md) |
| 新快手 ClassNotFoundException | 响应/模型等混淆名称变化；旧 w1 在新版已是另一方法 | 使用下面的双包共享选择器和模型契约；主过滤不再受可选补页绑定失败连带影响 |
| 抖音一直等待评论 | 原分发点参数实际是包装器，直接按 CommentItemList 读会返回 null；UI 又直接访问 items 字段 | 在解包后的真实响应消费入口过滤，覆盖两种实验分支；不伪造读取成功。见 [抖音说明](DOUYIN_CONSUMER_FIX.md) |

## 快手如何共用两个版本的部分

核心入口仍以未混淆的 `com.kwai.kmp.comment.mvi.panel.pagelist.KmpCommentFeaturePageList` 为边界。只读取这个精确类的 DEX 声明和指令，选取调用以下四个响应接口方法、参数/返回类型也符合约束的**唯一**实例解析方法：

- `getKLocalComments(): List`
- `getKGodCommentCount(): int`
- `getKTransparentPayload(): String`
- `getKCommentCorrelationId(): String`

旧 APK 自动识别为 `w1(w2b.q): void`，新 APK 自动识别为 `x1(m3b.q): void`。这些名称是识别结果，不是两组硬编码版本表。新 APK 的 `w1(m3b.q)` 返回列表容器，不会因名字相同被误当解析器。

评论模型优先从保留的泛型签名确定，必要时从 page 的实际字段类型收敛，再核对共同的 `getKType/getKIsSub/getKComment/getKAuthorArea/getKUser/getKUserSex` 方法及返回类型。响应模型同时验证 `getKLocalComments/getKLocalFullList/getKPcursor`。没有多个相似候选时才安装。

分页以未混淆的 `KMPPageList` 为边界，沿 `load/release/refresh` 的共同调用关系找 loading/cancel，从唯一的最新响应 getter 找首次页状态，再从响应提交关系找 completion。首次状态 `i` 和 completion 参数 `$c` 也由关系确定，未作为固定名称选取。评论 page 的 core 字段按实际类型确定。可见评论计数从原生响应的 `getKLocalFullList` 读取，保留原生游标与每轮最多 5 页的策略。

页脚 wrapper 从 RecyclerFragment 的 Adapter 字段类型确定，再区分原生 header/footer 两组；从 footer 操作读取的同一 group 字段和调用关系找到添加入口。旧 `pcl.f/Q1`、新 `tdl.f/c2` 使用相同选择逻辑，见 [页脚说明](KUAISHOU_FOOTER_SHARED_BINDINGS.md)。少量两版共有的 Fragment/lifecycle/getter/footer anchors 仍需存在，不声称整个 UI 任意改版都能自动恢复。

所有 APK 扫描均在宿主后台线程完成；主评论回调不扫描 DEX。后台绑定期间保留当前 Activity，完成后交给分页协调器。若评论区早于绑定完成已经载入，刷新后再验证读取时间。核心过滤先独立绑定，补页/页脚结构缺失或歧义时报告部分异常，保持原生手动分页，不留下已安装一半的补页 Hook。

DEX 读取器不执行 APK、不运行 JADX、不添加 native 引擎，具备大小/索引/指令边界与 payload 检查，详见 [范围和格式限制](ks-0.2.5/DEX_INDEX.md)。完整双包身份、方法和字段证据见 [dex-fingerprints.json](ks-0.2.5/dex-fingerprints.json)。

## 验证边界

- 新增单元测试覆盖独立回复控制项、包装行、抖音实际消费边界、未来改名的快手选择、歧义拒绝、页脚 header 排除及真实 D8 fixture 的 DEX 边界。
- 两个真实快手 APK 均通过同一生产选择器的完整解析/分页 profile；页脚添加入口也通过同一选择器。
- API 37 的 `KuaishouContractProbe` 用 PathClassLoader 和 `Class.forName(..., false, ...)` 对实际 APK 做声明反射绑定，验证生产 `KuaishouBindings/Paging`、真实模型、Fragment 参数及 pager getter 的可解析性；没有调用宿主方法或启动其 Application/Activity。它不能证明 LSPosed Hook、真实连续分页和显示效果。摘要见 [android-binding.json](ks-0.2.5/android-binding.json)。
- 弹窗完整 UI 回归和对照帧采样在修复候选上完成，候选当时仍为 0.2.4/code12；最终 0.2.5 使用同样 UI 修复，最终构建与诊断回归元数据另记 [build-0.2.5.json](build-0.2.5.json)。

共同签名与调用关系不变时，未来同类混淆改名有机会直接继续使用。若长类名、接口含义、调用路径、DEX 格式或 UI 结构发生变化，仍可能需要适配更新；缺失/歧义时明确报告，不能保证自动支持所有未来版本。小红书与抖音这次依据真实 APK 静态修正并通过离线测试，三款应用的真实 LSPosed 评论区仍需设备验收。
