# 哔哩哔哩适配依据

本次针对用户提供的安装包进行离线静态分析，没有启动目标 APK。下列是普通 `aapt`、DEX 表解析和本地 JADX 观察结果，不是 REA Evidence。

| 应用 | 包名 | 版本 / versionCode | SHA-256 |
| --- | --- | --- | --- |
| 哔哩哔哩 | tv.danmaku.bili | 9.17.0 / 9170300 | 98e7c94552300f0aff5ed7b3455ab165950ee6b89c16227410792e6c123b5616 |
| 哔哩哔哩 HD | tv.danmaku.bilibilihd | 2.5.1 / 2051100 | 1aaa00f9a122ca82a211557d6747a652393a3f9dcb19f4363c0efc1b6f3b8f07 |

## 工具和边界

REA `inspect_android_package` 返回 `capability_unavailable`：服务端没有配置 `REA_JADX_MCP_JAR`。没有修改全局配置；改用工程已有 `.tools/jadx-headless-mcp-0.7.1-all.jar`、JBR 17 和普通 Java/JADX API。整包 3 GiB 加载超出堆限制，按 DEX 类表定位后，对相关单 DEX 提取包进行解析。缺失跨 DEX 依赖会产生 JADX 层次警告，因此只依据明确 getter、字段和可读方法体建立适配，不从警告中推断行为。

分析产物位于 `.tools/bili/`、`.tools/bilihd/`，本地分析辅助脚本 `.tools/BiliInspect.java` 和 `.tools/bili_dex.py`；这些是可重新生成的非发布工具。APK 原件没有修改。

## 观察到的实际数据路径

两个版本都包含 `com.bapis.bilibili.main.community.reply.v1.MainListReply`：

- `getRepliesList()` 返回普通主评论 `List<ReplyInfo>`。
- `getTopRepliesList()` 返回置顶列表 `List<ReplyInfo>`。
- `ReplyInfo.getRoot()/getParent()` 为 `long`，区分主评论与回复。
- `ReplyInfo.getContent().getMessage()` 提供正文。
- `ReplyInfo.getMember().getSex()` 提供服务端性别字符串。
- `ReplyInfo.getReplyControl().getLocation()` 提供显示的属地字符串。

普通版相关模型在 `classes35.dex`，评论消费者在 `classes5.dex`。`Uh.a.b(MainListReply)` 明确遍历以上两个列表，转换为 `BiliCommentCursorList.replies/topReplies`。其中 `Uh.a` 的评论转换体还将 `getRoot/getParent` 写入 `mRootId/mParentId`，并将 `getLocation` 写入 `replyControl.location`。

HD 相关 protobuf 模型在 `classes30.dex`，消费者在 `classes4.dex`。`bq.a.r(MainListReply)` 同样遍历两个 getter；新版评论列表 `com.bilibili.app.comment3.data.source.v1.e.B0(MainListReply,long,h0,boolean)` 也明确读取它们。这两个消费者共享相同响应层，适配不依赖 UI View 的混淆命名。

普通版另含 Kotlin 数据模型 `KMainListReply` / `KReplyInfo`，解析确认对应 getter 为 `getReplies/getTopReplies`，正文/用户/属地 getter 名字和 protobuf 一致；`KContent.getMessage`、`KMember.getSex`、`KReplyControl.getLocation` 都返回 String。此路径也安装过滤 Hook，但尚未对其 UI 消费者进行运行验证。

## 实现判断

适配只替换以上**主列表响应** getter 的返回列表，不 Hook `ReplyInfo.getRepliesList()` 或 `KReplyInfo.getReplies()`，不直接改动原始列表、分页游标或 protobuf 字段。仅 root 和 parent 都是 0 的对象参与规则；保留楼中楼、非评论对象、无法读取的条目。返回副本保持只读属性。

性别映射属于模块设计：`男/M` → M，`女/F` → F，其余或缺失 → UNKNOWN。不通过昵称、头像推断，不请求用户资料。缺失属地按未知属地处理；缺失正文对象视为形状不完整，整条保留。只有 memberV2 数据而旧 member 没有性别时，也按 UNKNOWN 处理。

## 验证与剩余问题

- 六项 `BilibiliCommentFilterTest` 通过：原始不可变列表保护、普通/置顶主列表过滤、root/parent 回复保护、中文性别与显示属地、缺失属性匹配未知、白名单下反射失败保留、关闭时返回原列表。
- 安装前验证所有预期符号；若 Hook 安装中途异常，撤销本适配已安装的 Hook。
- 尚未进行 Android 16 + LSPosed 真机验收。静态 getter 和消费者证据支持实现，不能证明设备上的评论页面实际走到了 Hook。
- 没有新增自动补页入口；过滤后继续使用目标应用原有翻页。不会套用快手私有分页符号。
- 精选卡片、混排卡片、单独详情/对话列表不在本次主列表过滤范围；独立单条置顶 getter 不替换为 null，以免违反 protobuf 非空约定。实际使用 `topReplies` 的主列表置顶会参与过滤。

真机验收应分别开启普通版与 HD 的 LSPosed 作用域，用不同关键词规则确认互不影响，再逐项检查属地、性别、置顶、楼中楼、翻页及关闭过滤恢复。日志入口为 `[CommentFilter] Bilibili adapter ready`。
