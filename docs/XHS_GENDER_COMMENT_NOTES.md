# 小红书 9.52.0 评论分支性别复核

日期：2026-10-28（Asia/Shanghai）。目标为用户提供的原 APK，身份沿用 `XIAOHONGSHU_ANALYSIS.md` 及 REA 包清单 Evidence `ev_7d99df27aafc61a2daba462696de971348185e0656f48a4197df3039f84604a5`。本页新增结论来自原包选定 DEX 的普通 JADX 分析，不是新的 REA Evidence；没有运行 APK、请求接口或读取账号数据。

## 已确认的观察

1. 原生 `com.xingin.entities.CommentCommentUser`（classes5.dex）继承 Object，字段包括用户 ID、昵称、图片、直播状态、`additionalTags`、`statusWordCache` 等；没有 `gender/sex` 字段。`m()/k()` 提供 userid，可用于与别处用户资料关联，但当前评论对象本身没有已验证的性别。
2. `additionalTags` 是 `@SerializedName("additional_tags") Map<String,String>`。这个类中观察到的读取键为 `avatar_pendant`、`status_word`、`nickname_style`、`nickname_type`。`statusWordCache` 只是 `additional_tags.status_word` 字符串反序列化后的 Gson JSON 对象，并非另一份用户资料。
3. 原生列表 `CommentListPresenterV2.Z0(CommentCommentUser)` 使用 `j()` 读取上述 status_word，已观察到读取键 `is_guess_king`，用于状态相关的埋点/展示逻辑；该方法没有读取 gender/sex。
4. 原生 `CommentExtraInfo` 只含 `unfriend_score/static_score`；`CommentBizLabels` 含 `group_invite/product_review/reward_note_type`，这两个扩展对象没有已验证的性别载荷。
5. 另一套生成模型 `com.xingin.net.gen.model.CommentCommentUser`（classes9.dex）及 Moshi JsonAdapter 也没有性别字段。JsonAdapter 明确识别 16 个键：`level/id/name/desc/image/image_size_large/followed/group_id/red_id/userid/nickname/images/red_official_verify_type/show_red_official_verify_icon/red_official_verified/live`，未知键进入跳过分支。
6. 同组 `CommentCommentInfoJsonAdapter` 的 25 个键和 `CommentNoteCommentListDataJsonAdapter` 的 5 个键没有 gender/sex，也没有额外用户性别字典；生成扩展模型 `CommentCommentInfoCommentExtraInfo` 同样只有两个 score 字段。

## 结论边界

可确认：**当前模块读取的原生评论模型，以及复核的另一套生成评论模型，没有可直接使用的性别字段或已验证的性别解析路径。** 因而当前模块将该评论分支的性别设为未知是符合已掌握证据的。

不能据此确认：小红书整个应用或服务端“彻底无法提供性别”。尤其 `additional_tags` 是可扩展字符串映射，静态客户端没有读取某个键，不等于服务端永远不返回该键；生成 JsonAdapter 会丢弃未知键，也不能证明原始 JSON 不含未知字段。未抓取该版本的真实评论响应，未验证热补丁/实验分流。因此“所有评论响应绝不会带性别”仍是未知项。

其他用户资料、已有缓存或独立用户接口是否提供声明性别，是单独问题，不能用评论模型的缺项替代该调查；应将评论 userid 与实际资料证据关联，且未提供/隐藏资料仍须按未知处理。

## 可复核的本地材料

- `.tools/com.xingin.entities.CommentCommentUser.java.txt`：声明、`b()` 的 avatar_pendant、`j()` 的 status_word、`n()` 的 nickname 两个键。
- `.tools/com.xingin.comment.consumer.list.CommentListPresenterV2.java.txt`：`Z0`。
- `.tools/com.xingin.entities.CommentExtraInfo.java.txt`、`.tools/com.xingin.entities.CommentBizLabels.java.txt`。
- `.tools/com.xingin.net.gen.model.CommentCommentUser.java.txt`、`CommentCommentUserJsonAdapter.java.txt`、`CommentCommentInfoJsonAdapter.java.txt`、`CommentNoteCommentListDataJsonAdapter.java.txt`、`CommentCommentInfoCommentExtraInfo.java.txt`。
- `.tools/xhs-gender-comment-user-uses.txt`：在选定 classes3+5 DEX 范围内的使用关系；该范围不是整个 APK 的调用穷举。
