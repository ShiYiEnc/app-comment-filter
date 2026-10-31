# 抖音“已加载，等待评论”的静态排查与修复

## 已观察到的遗漏

用户提供的 `抖音_40.9.0.apk` 与历史分析副本 `.tools/douyin409.apk` 的 SHA-256 均为：

`bad30e22688238a118f360138fe82da3c6346be84b43b23a16681a168680f6b0`

对 APK 内 `classes32.dex` 的声明和选定类反编译核对发现：

1. `CommentListPageNetRequest` 的响应泛型是 `X.15do`，不是 `CommentItemList`。`X.15do` 包含 `state:int`、`data:Object`；正常列表事件用 `state=0, data=CommentItemList`，分块更新用 `state=1, data=Map`。原来的首屏/更多 Hook 在分发点直接调用 `copies.items(参数)`，对包装器返回 `null`，不会进入评论读取器。
2. `CommentItemList.getItems()` 存在，但真实消费代码大量直接访问公开的 `items` 字段。Hook getter 并不能覆盖这些字段读取。
3. 正常列表事件的消费者 `X.15da` 在首屏和加载更多回调后，把 `CommentItemList` 交给 `(CommentItemList, boolean) -> void` 方法处理。该类还持有类型为 `CommentListPageNetRequest` 的请求字段。其处理过程组装评论 UI 条目，后续再进入 RecyclerView。
4. APK 同时保留旧实验分支 `CommentFetchModel`。其 `handleData(Object)` 桥接方法对应实际的 `LJIJJLI(CommentItemList) -> void`，另一个 `LJIIIIZZ(CommentItemList) -> void` 负责列表条目投影；两者也直接访问 `items`。

这些是可以解释“Hook 安装成功，却没有读取评论”的确定代码遗漏。未采集用户设备上的 LSPosed 回调轨迹，因此不能断言用户当时必然走了哪一个实验分支，也不能把静态排查写成真机修复已验收。

## 修改

- 在响应转换为 UI 条目前过滤实际 `CommentItemList`，从而覆盖公开字段的消费路径。
- 现代入口不硬编码 `X.15da` 或方法名。以稳定的 `CommentListDataFetchHelper` 为边界，检查其无参实例方法的返回类型和实例字段类型；要求候选持有确切的 `CommentListPageNetRequest` 字段，并具有非静态、非抽象、非桥接的 `(CommentItemList, boolean) -> void` 消费方法。只有唯一候选可安装，歧义或缺失会拒绝安装并报告适配失败。
- 旧实验分支按具体的 `(CommentItemList) -> void` 实例方法结构绑定，避免依赖 JADX 显示的桥接重命名。
- 过滤后继续复制整个响应及继承字段，只替换复制品的 `items`；输入响应和原列表保持不变，分页字段、总数、配置不由模块修改。
- 生成的 `Y.AObjectS309S0200000_37` / case 57 不再承担主列表覆盖。原树管理器兼容路径保留为补充，其生成回调不存在时不会单独阻止新的主列表入口安装。
- 成功读取主评论仍由既有 `FilterDiagnostics` 汇总；不通过伪造成功状态消除“等待评论”。过滤关闭、只有子评论/装饰条目、未打开评论区等情况仍不构成成功读取证据。

## 可复核的 DEX 声明

以下偏移为 APK 内 `classes32.dex` 的代码偏移，不是源代码行号：

| 声明 | 签名 / 字段 | codeOffset |
| --- | --- | --- |
| `CommentListDataFetchHelper.A` | `LX/15da;` | — |
| `CommentListDataFetchHelper.LJIIJJI` | `()LX/15da;` | 5399392 |
| `X.15da.LIZ` 字段 | `Lcom/ss/android/ugc/aweme/comment/data/list/CommentListPageNetRequest;` | — |
| `X.15da.LIZ` 方法 | `(Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;Z)V` | 5237360 |
| `X.15da.LIZJ` | `(Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;Z)V` | 6965428 |
| `CommentFetchModel.LJIIIIZZ` | `(Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;)V` | 5236572 |
| `CommentFetchModel.LJIJJLI` | `(Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;)V` | 6963424 |
| `CommentFetchModel.handleData` | `(Ljava/lang/Object;)V`，bridge | 3369272 |

本次使用现有选定 DEX 与本地 JADX helper；输出是普通离线静态分析，未伪称 REA Evidence。可用 `scripts/inspect_dex.py 原APK 正则 --members` 复核声明。局部反编译不能证明所有动态调用、热更新或新版本的行为。

## 验证范围

`DouyinConsumerBindingsTest` 新增 4 项测试：改名后的结构解析/去重、错误所有者和歧义拒绝、包装器复现旧遗漏并在实际响应入口模拟首屏/更多过滤、旧分支的具体类型绑定。连同现有 `DouyinCommentReaderTest` 的 6 项测试通过。测试检查原输入未改、回复保留、游标/总数保留，但不执行 Android 宿主或 LSPosed。

仍需真机打开普通视频主评论、刷新和加载更多，确认最近读取时间变化及具体规则效果；独立详情、直播/电商评论、动态加载的不同实现和新的 APK 仍属于待验证范围。
