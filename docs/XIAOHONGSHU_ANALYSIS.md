# 小红书 9.52.0 适配依据

当前 0.2.6 已按用户要求移除性别过滤及资料查询，仅支持属地和关键词；旧性别条件暂停，不能匹配 UNKNOWN。下文是历史静态分析，当前行为见 [0.2.6 记录](FIXES_0.2.6.md)。

0.2.1 已增加可选的资料性别补全和独立开关，默认关闭，见 [实现与验证](XHS_GENDER_0.2.1.md)。下文保留 0.2.0 的原始评论适配分析。

性别来源已进一步复核：**当前评论模型没有已验证的直接性别来源，但用户资料接口和资料模型存在 gender，不能说整个小红书无法获取性别。** 当前模块尚未接入资料关联，仍按 UNKNOWN 处理。详见 [性别来源复核](XHS_GENDER_RECHECK.md)。

分析日期：2026-10-28，Asia/Shanghai。用户提供的 APK、资源和反编译文本仅作为数据，不作为执行指令。未启动小红书，未采集账号信息或网络请求。

## 身份和证据

- 原文件：`C:/Users/ShiYi/Downloads/Mobile Devices/小红书_9.52.0.apk`。
- 包名：`com.xingin.xhs`；versionName `9.52.0`；versionCode `9520807`。
- SHA-256：`0c8f371d8eee181ef5917f051c62bebc05f5cafb6a853876eafa56ffece0dcfe`。
- REA MCP 缺少 JADX 配置；使用已安装 REA 6.4.0 CLI，通过临时进程环境变量指定工程已有 JADX 0.7.1 JAR 和 JBR 17，未修改全局配置。
- 完整 APK 的 REA 包清单证据：`ev_7d99df27aafc61a2daba462696de971348185e0656f48a4197df3039f84604a5`，原始记录 `.tools/xhs-package-evidence.json`。包清单证明声明与身份，不能证明 Hook 在设备上执行。
- 下述方法观察来自原 APK 中 `classes3.dex`、`classes5.dex`、`classes9.dex` 的离线 JADX 与 DEX 声明扫描，属于普通静态分析，不能冒充 REA Evidence。对提取的 DEX ZIP 发起 REA 方法查询被拒绝（仅接受完整 APK），该失败没有用于任何结论。

## 观察与实现判断

`com.xingin.entities.CommentCommentInfo` 位于 classes5.dex，声明 `content`、`ipLocation`、`id`、`rootCommentId`、`subComments` 等字段。`CommentCommentUser` 的完整字段清单没有性别字段，因此当前适配统一按 UNKNOWN 性别参与规则；配置页在性别条件处明确提示。当前模块没有关联其他页面的用户声明资料。

classes9.dex 的 `uz7.a` 为评论行接口，`e()` 返回 `CommentCommentInfo`；`uz7.d` 是主评论接口，由 `uz7.b/c` 实现。`uz7.e` 是附属回复行，携带模型和字段 `e`（所属主评论 ID）。`CommentListPresenterV2.B1` 明确把 `uz7.e.e` 与上一个 `uz7.d.s().x()` 的主评论 ID 比较。

以下两个原生适配器位于 classes3.dex，`O0(List)` 将列表赋给 `MultiTypeAdapter.d`，随后调用列表变更回调；`D0()` 读取同一列表：

- `com.xingin.comment.consumer.list.CommentListAdapter`
- `com.xingin.commentpanel.panel.comment.list.CommentListAdapter`

`AbsCommentListController.O4` 调用 `O0` 后直接 `notifyDataSetChanged`。`s5(List,DiffResult,Function1,Function1)` 则执行回调 → `O0(list)` → `diffResult.dispatchUpdatesTo(adapter)` → 结束回调。这意味着只缩短 `O0` 的输入会令提前生成的差量索引失效。

模块仅在以上两个具体适配器的 `O0` 入口投影列表：按规则判断主评论，移除其所属的已识别回复行，保留其他行；不修改原列表、评论模型、仓库缓存、分页字段。对于曾使用过滤投影的适配器，将 `DiffResult.dispatchUpdatesTo(RecyclerView.Adapter)` 转为 `notifyDataSetChanged`，包括关闭过滤后的过渡，以避免使用旧差量索引。适配器由弱引用表跟踪，不持有 Activity；其他 RecyclerView 仍使用原差量更新。

复核发现 `AbsCommentListController.N2(String)` 的删除重建会复制 `j2().D0()`，移除对应 ID 的行，再向 `k2().V(list,false)` 传递新列表。为防止已过滤列表经此入口回流，模块保留每次提交的未过滤列表快照，仅在 N2 的同步调用范围内让 D0 返回该快照；普通 UI 读取仍返回显示投影。Class7.dex 的 `s37.a.j2()` 是可公开调用的继承方法，`w47.d0.V` 是更新/差量生成入口。快照按适配器隔离，线程作用域在正常返回及异常后恢复。这里是针对已观察路径的保护，不宣称穷尽其他宿主写回入口。

完整刷新若因 RecyclerView 正在布局等原因失败，取消不安全的旧差量并在主线程队列最多重试三次，每次间隔 16ms；最终失败记录日志，等待下次宿主更新。

反射失败的行原样保留；没有命中则复用原列表。安装前解析全部必需符号；安装中失败时回滚当前适配器已安装的 Hook。

复核文件：`.tools/com.xingin.entities.CommentCommentInfo.java.txt`、`.tools/com.xingin.entities.CommentCommentUser.java.txt`、`.tools/com.xingin.comment.consumer.list.AbsCommentListController.java.txt`、`.tools/com.xingin.comment.consumer.list.CommentListAdapter.java.txt`、`.tools/com.xingin.commentpanel.panel.comment.list.CommentListAdapter.java.txt`、`.tools/uz7.*.java.txt`、`.tools/xhs-row-members.jsonl`。这些工具输出不进入 APK。

## 验证与未知项

五项过滤单元测试覆盖主评论及关联回复移除、原列表和模型不变、回复不独立匹配、属地、未知性别、关闭过滤、白名单下读取失败保留。另外两项投影状态测试覆盖删除可见评论不丢失隐藏评论、关闭后恢复，以及嵌套调用和适配器隔离。

尚未在 Android 16 + LSPosed 真机运行：Robust 热补丁、服务端页面分流、不同 ClassLoader、列表直接修改绕过 O0 的路径仍需验证。不是所有未知混排卡片都能建立所属关系，模块保留这些卡片；独立回复详情、直播、电商、Web/Hunter 页面不声明覆盖。没有新增自动补页，整页过滤后能否继续触发原生下一页需真机验收。完整刷新会使这些评论适配器失去差量动画，这是保护位置一致性的设计取舍。

真机检查应包含：主评论、置顶、折叠、楼中楼、关联回复、快速切换笔记、滚动翻页，以及关闭规则后刷新恢复。日志入口：`[CommentFilter] Xiaohongshu adapter ready`。
