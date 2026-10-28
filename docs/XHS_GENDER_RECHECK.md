# 小红书性别来源复核

日期：2026-10-28，Asia/Shanghai。目标是用户提供的小红书 9.52.0 / 9520807 APK，SHA-256 `0c8f371d8eee181ef5917f051c62bebc05f5cafb6a853876eafa56ffece0dcfe`。复用包清单 REA Evidence `ev_7d99df27aafc61a2daba462696de971348185e0656f48a4197df3039f84604a5`；新增调用、字段与解析观察来自原 APK DEX 和本地 JADX，不是新的 REA Evidence。未运行目标应用或请求任何用户资料。

## 结论

**不能将“当前评论模型未携带已验证的性别字段”推导为“小红书彻底无法获取性别”。** 此 APK 中存在按 user_id 读取用户资料的接口，其结果类型包含 gender，故存在为评论作者补充用户声明性别的技术路径。当前 0.2.0 模块没有实现该资料关联，仍将小红书评论性别设为 UNKNOWN。

| 核对问题 | 结果与证据边界 |
| --- | --- |
| 当前读取的评论对象直接提供性别吗 | 原生和另一套生成评论用户模型均没有已验证的 gender/sex 字段，见评论分支复核 |
| 小红书其他用户资料模型提供性别吗 | `com.xingin.account.entities.UserInfo` 有 `int gender`、`getGender/setGender`；`UserInfoRedJsonAdapter` 明确读取 JSON 键 `gender` 后调用 setter |
| 资料路径是否只能读取登录用户 | `UserServices.getUserInfo(QueryMap)` 声明 GET `api/sns/v3/user/info`；`n98.t.o`（日志 loadOtherUserInfo）以目标 user_id 查找预加载缓存，再经 n98.r case0 调用资料接口；`aa8.f0.l` 和 `z08.d.b` 也使用传入的目标 user_id。结果为 `Observable<UserInfo>` |
| 能否关联评论作者 | 原生评论用户包含 `userid`；资料 `BaseUserInfo` 同样包含 `userid`。按 ID 关联是模块设计推论，尚未实现或运行验证 |
| 是否能稳定获取所有评论作者性别 | 未确认。尚无实际接口响应、隐藏/缺失字段、实验分流、失败或批量加载的运行证据，不能保证每个账号都返回该字段 |

`UserInfo.gender` 默认值为 2；`y38.p.G0` 在值不为 2 时按 gender==0 选择男标签，否则选择女标签，原 APK 资源文本已交叉核对。已验证的通常值为 0=男、1=女、2=未知/不展示；模块若接入应对其他值继续按 UNKNOWN 处理，不能照搬宿主的 else 分支。不能仅看到模型对象存在就认为服务端提供了有效性别。资料性别是用户声明字段，不能等同于对真实身份的识别。

## 对后续实现的影响

可考虑观察宿主已加载的用户资料并按 userid 缓存；未命中的作者如需自动补全，还需要异步资料请求、缓存、失败处理和规则刷新。不能在评论列表同步 Hook 中逐条阻塞请求。是否默认额外请求资料、覆盖哪些作者，是独立实现决策，本次仅复核能力，没有更改模块行为。

当前评论解析未读取到性别不等于原始 JSON 永远没有：原生模型保留可扩展 `additional_tags`，生成模型解析器会跳过未知键。要确认原始响应是否还有可直接使用的数据，需设备上的实际响应证据。

## 可复核材料

- [评论分支复核](XHS_GENDER_COMMENT_NOTES.md)。
- `.tools/com.xingin.account.entities.UserInfo.java.txt`：gender 默认值及 getter/setter。
- `.tools/com.xingin.account.entities.UserInfoRedJsonAdapter.java.txt`：`case "gender"` 解析。
- `.tools/com.xingin.matrix.profile.services.UserServices.java.txt`：资料接口声明。
- `.tools/aa8.f0.java.txt`、`.tools/z08.d.java.txt`：user_id 参数及调用。
- `.tools/n98.t.java.txt`、`.tools/n98.r.java.txt`：他人资料仓库、预加载缓存和请求链；`.tools/y38.p.java.txt`：性别显示逻辑，详见 [资料分支复核](XHS_GENDER_PROFILE_NOTES.md)。
- `.tools/xhs-gender-field-inventory.json`：24 个主 DEX 的字段名/类型候选扫描，不把无命中当作原始网络响应的不存在证明。
- `.tools/xhs-gender-annotations.json`：DEX 字段注解中明确的 gender/sex 键，补查混淆字段。

其他带 gender 的模型还包括聊天用户和推荐用户，不能未经关联就套用为评论作者资料。现有 UI 的 UNKNOWN 是当前适配能力，不是对整个小红书能力的否定。
