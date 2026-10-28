# 抖音 40.9.0 适配记录

用户提供 APK：`C:/Users/ShiYi/Downloads/Mobile Devices/抖音_40.9.0.apk`。

- 包名 `com.ss.android.ugc.aweme`；versionName `40.9.0`；versionCode `400901`；minSdk 23、targetSdk 34。
- APK SHA-256：`bad30e22688238a118f360138fe82da3c6346be84b43b23a16681a168680f6b0`。
- 分析为静态观察，未执行抖音。REA MCP 首次调用缺少 `REA_JADX_MCP_JAR`；随后使用本地现有 JAR 的 REA CLI 在 4096 MiB 堆下加载完整 APK 失败（Java heap space）。错误保存在 `.tools/douyin-package-evidence.json`，不将该失败包装成成功的 REA Evidence。
- 后续使用工程已有 `scripts/inspect_dex.py` 做完整 APK 声明索引，使用已有 JADX 0.7.1 JAR 与 `scripts/Decompile.java` 分析原包 `classes26.dex`、`classes32.dex` 的副本。这些是普通静态分析产物，无 REA Evidence ID。

## 已观察的符号与行为

1. `classes32.dex` 中 `com.ss.android.ugc.aweme.comment.model.CommentItemList` 的 `@SerializedName("comments") public List items`；`getItems()` 直接返回该字段。`cursor`、`total`、`hasMore` 是独立服务端分页字段。
2. 同 DEX 的 `Comment` 有 `text`、`ipLabel`（`ip_label`）、`replyId`（`reply_id`）、`level`、`commentType`、`user`、`commentUser`。宿主 `LLLLZLL()` 优先返回 `commentUser`，再返回 `user`。`CommentExtensionsKt.LJIILLIIL()` 把非空且非 `0` 的 `replyId` 作为主评论 ID；`LJIIJJI()` 将 `level >= 2` 视作回复。适配保留这些回复，仅对普通类型 0/1 判定，其他类型（含回复类型 2、305、306）保留。
3. `classes26.dex` 中 `profile.model.User.gender` 对应服务端 `gender` 整数字段。数字映射通过实际编辑页和资源交叉验证：`EditGenderAndAgeAfterLoginFragment.zM()` 中控件 `0x7f0a342d` 写 `"1"`，`0x7f0a342c` 写 `"2"`；布局 `0x7f0d2d2b` → `r/276.xml` 中两者文本分别是 `0x7f115ff1`（男）、`0x7f1135ad`（女）。其他数字/缺失作者映射 UNKNOWN。
4. `BaseCommentPageNetRequest.LJIIL(Object,Object)` 向 `Request.LJIIL` 分发首次成功响应，后者遍历观察者并传递同一响应。适配仅对精确的 `CommentListPageNetRequest`、`FetchCommentTreeListRequest` 生效，排除 `CommentReplyListPageNetRequest`。
5. 主请求和树请求直接读取 `CommentItemList.items`，仅 Hook `getItems()` 无法覆盖。加载更多另由 `PageNetRequest.LJIILJJIL()` 创建的 `Y.AObjectS309S0200000_37`（`$t=57`，`l0=request`）分发，`invoke(Object)` 的 case 57 调用观察者 `LIZJ(response,param)`，不经过首次成功方法。因此同时 Hook 这两个已核对的入口，并只向观察者传入浅拷贝的响应。

复核产物位于 `.tools/com.ss.android.ugc.aweme.*.java.txt`、`.tools/Y.AObjectS309S0200000_37.java.txt`、`.tools/douyin-model-members.jsonl`、`.tools/douyin-gender-layout.txt`、`.tools/douyin-resources2.txt`。临时 DEX ZIP 不会打入模块。

## 实现与验证边界

适配器先核对类、字段类型及 Hook 签名，再安装三个入口：首次成功分发、加载更多成功分发、响应 `getItems()`。列表只在首次命中后复制；响应复制保留继承字段、服务端游标、总数及 hasMore；嵌套回复列表不遍历、不改写。无法解析的条目保留，读取失败保留原响应，默认关闭的规则直接放行。

独立单元测试覆盖主评论/回复区分、黑白名单、属地、服务端性别映射和作者优先级、关闭与空白名单，以及响应复制后的分页元数据和原列表不变。

尚未在 Android 16 + LSPosed 真机执行抖音评论回归；未声明覆盖直播、电商评论、Hunter Web/KMP 独立面板。未增加未经验证的自动补页入口，继续使用宿主原有分页。少量评论或整页被过滤后的宿主 UI 与下一页触发行为仍需真机验收。
