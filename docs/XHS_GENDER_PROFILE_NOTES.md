# 小红书 9.52.0 资料性别复核

后续实现已进入 0.2.1，见 [可选资料性别补全](XHS_GENDER_0.2.1.md)。下文是实现前的静态复核记录。

日期：2026-10-28（Asia/Shanghai）。范围为用户提供的 `小红书_9.52.0.apk`，包名 `com.xingin.xhs`，版本码 `9520807`。复用此前 REA 包清单身份记录 `ev_7d99df27aafc61a2daba462696de971348185e0656f48a4197df3039f84604a5`；以下字段、方法、资源分析均为普通离线 DEX/JADX/aapt2 观察，不是新的 REA Evidence。未启动 APK，未请求服务端，也未读取账号令牌。

## 结论

“应用彻底无法取得性别”不成立。评论用户模型没有直接声明性别，与应用存在用户资料性别是两件事。APK 中存在读取他人资料、解析性别、按用户 ID 复用资料预加载结果、展示性别的完整静态代码链路。当前模块没有把这条资料链路接到评论过滤。

## 已观察

1. `classes19.dex` 的 `com.xingin.account.entities.UserInfo` 继承 `BaseUserInfo`，具有 `private int gender = 2`、`getGender()`、`setGender(int)`。`BaseUserInfo` 具有 `userid`、`getUserid()`，因此拿到资料后可以按明确用户 ID 关联，不能使用昵称关联。
2. 同 DEX 的 `UserInfoRedJsonAdapter.fromJson` 明确有 `case "gender": userInfo.setGender(aVar.z())`。缺少该字段时保持模型默认值 2；不是根据界面文本或头像推断。
3. `classes8.dex` 的 `com.xingin.matrix.profile.services.UserServices.getUserInfo` 声明 `@GET("api/sns/v3/user/info")`、`@QueryMap Map<String,String>`，返回 `Observable<UserInfo>`。
4. `classes9.dex` 的 `n98.t.o(String,String)` 是他人资料加载入口，带有 `ProfileMainPageRepo-loadOtherUserInfo` 日志，构造 `user_id = 第一个参数` 及页面实验参数；先读取 `f95.b.a.a(userId,"ProfilePreloadUserInfo",...)`，没有可用预加载数据/任务时转由 `n98.r` 的 case 0 调用 `UserServices.getUserInfo(map)`。它不是只读当前登录用户资料的接口。
5. `classes9.dex` 的 `z08.d.b(h95.a,Object)` 同样把输入用户 ID 写为 `user_id` 请求资料，返回 `UserInfo`。`aa8.f0.l(String,String,Context)` 也构造目标 `user_id` 请求相同 API。
6. `classes9.dex` 的 `y38.p` 在资料标签展示路径上，只有 `getGender()!=2` 时设置对应性别的无障碍文本；表达式为 `getGender()==0 ? 2131834168 : 2131834020`。原包 `resources.arsc` 中两项分别为 `0x7f113538` → “男，” / “Male,”，`0x7f1134a4` → “女，” / “Female”。结合默认值可确定该资料模型的语义为 0 男、1 女、2 未知/不展示。不能把其他应用常见的 1 男 2 女映射套到此处；未识别值仍应按未知。

## 能力边界与设计推论

- 静态代码证明客户端存在获取他人资料性别的能力，不证明服务端对每个用户、每次请求、每种登录状态都返回有效性别。隐私设置、未填写资料、账号状态、服务端限流和实验分流等实际结果没有运行证据。
- 可设计为“被动缓存资料响应”的模式：宿主正常加载某个用户资料时，按用户 ID 缓存明确返回的性别，再供评论过滤查询。它不保证当前评论页的所有作者都已进入缓存。
- 若希望所有首次出现的评论作者都立即可按性别过滤，通常需要额外查询其资料；这属于新实现，需要异步加载、去重、缓存、超时/失败回退、避免主线程请求，以及真机验证，不能仅加一个评论字段反射就完成。
- 获取的是用户资料中声明的性别，不代表对现实身份的验证。

## 可复核的本地材料

- `.tools/com.xingin.account.entities.UserInfo.java.txt`（gender 声明、get/set）。
- `.tools/com.xingin.account.entities.BaseUserInfo.java.txt`（userid）。
- `.tools/com.xingin.account.entities.UserInfoRedJsonAdapter.java.txt`（gender JSON 分支）。
- `.tools/com.xingin.matrix.profile.services.UserServices.java.txt`（API 声明）。
- `.tools/n98.t.java.txt`（他人资料、预加载命中和网络回退）。
- `.tools/n98.r.java.txt`、`.tools/z08.d.java.txt`、`.tools/aa8.f0.java.txt`（真实请求调用）。
- `.tools/y38.p.java.txt`、`.tools/xhs-gender-profile-resources.txt`（值映射交叉核对）。

提取的 `.tools/xhs-gender-profile-resources.apk` 仅含原 APK 的 manifest 和 resources.arsc，用于 aapt2 资源解析，不是完整目标 APK，不安装或执行，也未用它冒充原包身份。
