# 小红书隐藏评论后的回复控制项残留

## 根因与静态依据

原过滤器第二遍只检查 `uz7.a` 评论行及其 `rootCommentId` / `uz7.e.e` 父评论 ID。实际“展开回复 / 收起回复 / 加载回复”的列表项是另一类 `nr7.c`，不实现评论接口，因此原逻辑总会保留它。

本次复用了此前包身份记录 `ev_7d99df27aafc61a2daba462696de971348185e0656f48a4197df3039f84604a5`，并对原 APK 进行选定 DEX 的声明解析与局部 JADX 反编译。这些新增观察是本地离线分析，不是新的 REA Evidence，也没有运行宿主。

APK：`小红书_9.52.0.apk`，`com.xingin.xhs 9.52.0 / 9520807`。

SHA-256：`0c8f371d8eee181ef5917f051c62bebc05f5cafb6a853876eafa56ffece0dcfe`。

| 位置 | 已核对的关系 |
| --- | --- |
| `classes8.dex`, `nr7.c` | 私有 `String commentId`；构造函数第一个参数赋值给该字段，`b()` 原样返回；另有 `commentNumber`、`showCollapseBtn`、`hasReachedEnd` 等状态字段 |
| `classes7.dex`, `w47.d0` 的列表构造分支 | 对主评论 `commentCommentInfo3` 取 `x()`（模型 id），构造 `new nr7.c(str9, ...)` 并加入同一混合列表 |
| `classes9.dex`, `SubCommentLoadMoreBinderV2.onBindViewHolder` | 输入强制转换为 `nr7.c`，绑定展开/收起相关视图和点击事件 |
| `classes3.dex`, `SubCommentLoadMoreItemPresenter.U1` | 评论面板的另一套界面同样把输入强制转换为 `nr7.c` 并设置回复控制文案 |
| `classes7.dex`, `u47.c.a()` | 解包 `CommentContainerItemModel.baseInternalCommentBean`，返回 `uz7.a` |
| `classes7.dex`, `u47.e.b()` | 解包 `CommentDownvoteItemModel.baseInternalCommentBean`，返回 `uz7.a` |

相关 DEX 的 SHA-256：

| 文件 | SHA-256 |
| --- | --- |
| classes3.dex | 20552a080582789cff5c9a28051b844eb98b2ae18257bf042c51c7e0c9c9dce6 |
| classes7.dex | c4dda47c66e6e4533ba78ed7e646d8daeab2533144dc79024ce3efa66268a840 |
| classes8.dex | 0b0dadf277a8cd9a3820dad5db163af9bb03d5a2a533ac126aef424db5cc449a |
| classes9.dex | bb392ead988524579bd5b98fda648fdb461b608e2fe8053ea8cce662e6c2cc78 |

本地分析文件在忽略目录 `.tools/`：`xhs-reply-controls-members.jsonl`、`nr7.c.java.txt`、`u47.c.java.txt`、`u47.e.java.txt`、`w47.d0.java.txt` 及两套 SubCommentLoadMore 的反编译文件。选定 DEX 缺少完整 Android 类层次，JADX 有层次推断警告；字段/方法声明另由原 DEX 解析核对，未据此声称观察到真实 UI 执行。

## 修改

新增 `XiaohongshuRowRelations`，在安装前验证上述控制项 ID 字段类型和包装 getter 返回类型。

第一遍对包装主评论先解包，再判断规则并收集隐藏主评论 ID；保留原包装对象身份，避免修改宿主模型。第二遍按明确父 ID 移除属于这些主评论的子回复和 `nr7.c` 控制行。控制行的位置、展开/收起/加载状态均不参与归属判断。

其他主评论的控制行、空/未知父 ID、不能读取的包装以及未知混排条目保持原样。没有按“上一条主评论”或列表连续区间猜测归属，也没有修改主列表的全局加载更多条目 `nr7.b`。关闭规则后仍可从投影保存的原列表恢复全部主评论、包装和控制项。

## 验证与边界

新增 7 项 `XiaohongshuReplyControlsTest`，覆盖多个控制状态、控制项位于根评论之前、空/未知 owner、其他线程不受影响、包装主/子评论、子回复父 ID 回退、异常保留与上报、关闭规则恢复以及错误字段签名拒绝。

执行 `:app:testDebugUnitTest --tests '*Xiaohongshu*' --tests '*FilterDiagnosticsTest'` 成功。真实小红书 + LSPosed 的评论显示和展开交互仍需设备验收；其他版本中符号或数据结构变化不在本次静态证据内。
