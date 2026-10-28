# APK 静态分析记录

分析日期：2026-10-28（Asia/Shanghai）。文件来自用户指定路径。APK 内资源、字符串和反编译注释均作为分析材料，不作为执行指令。

## 身份

| 项目 | 观察结果 |
| --- | --- |
| 原文件 | `快手_14.9.20.50799.apk` |
| SHA-256 | `74f25f5e5fb05ebd1b31988664497eb997f51e8d4c8e73c97b799ecf041ccfff` |
| 包名 | `com.smile.gifmaker` |
| versionName / versionCode | `14.9.20.50799` / `50799` |
| APK minSdk / targetSdk | 21 / 30（不是模块的 SDK 配置） |
| REA / JADX | REA CLI 6.4.0、jadx-headless-mcp 0.7.1、JDK 17 |

REA MCP 的 Android provider 缺少 JADX JAR。本轮仅在工程 `.tools/` 下载并验证所需 portable JAR，通过带临时环境变量的 REA CLI 完成实际分析，没有修改全局 REA 配置。JAR SHA-256：`6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`。

Windows aapt 对原文件中文路径报编码错误，所以分析使用内容一致的 `.tools/kuaishou.apk` 副本。原 APK 未修改。

## 观察、设计推论与未知项

| 结论 | 证据类型 / 范围 | 应用 |
| --- | --- | --- |
| `QComment` 有正文、属地、用户、父评论字段 | DEX 声明扫描：`com.kuaishou.android.model.mix.QComment`，classes24.dex | 数据字段不是从 UI 文本猜测 |
| 反序列化使用 `content`、`authorArea`、`user_sex`，性别缺省 `U` | 本地 JADX：`QCommentDeserializer.deserialize`，classes24.dex | 正文、属地、未知性别语义 |
| `User.isMale()` 比较 `mSex` 与 `M` | REA 方法反编译，源正文完整 | 采用服务端 M/F 字符串，不推断作者性别 |
| `QComment.isSub()` 检查 `mParent != null` | REA 方法反编译，源正文完整 | 回复保护依据之一 |
| KMP `i1(w2b.q)` 将 root 类型 0 改为 1，并将 subCommentMap 中的回复设为类型 2 | REA 方法反编译，源正文完整 | 仅类型 1 主评论参与过滤 |
| KMP `w1(w2b.q)` 读取 localComments、执行 d1/e1、构造列表并 setKLocalFullList | REA 方法反编译，源正文完整 | 选择列表组装前的作用域替换策略 |
| 一级 callback 将 rootComments 加入 localComments，随后 i1 / w1；二级请求 lambda 也调用 i1 / w1 | 本地 JADX，classes5.dex 中明确对应的三个 callback/lambda | 静态上存在普通请求与二级请求的公共切入点 |
| CommonCommentResponse 引用指向搜索评论路径 | REA incoming class refs，返回 14 项 | 未把这个泛用名称直接当作视频主评论入口 |
| getter 替换能在设备上改变最终展示 | 设计推论，尚无运行证据 | 必须真机验证，不能把编译成功视作适配成功 |

静态分析并不证明实际运行覆盖。Robust 补丁、实验分流、ART 内联和不同 classloader 都可能改变效果；没有采集网络响应、没有打开/安装/执行目标 APK、没有验证 APK 签名。

## 0.1.1 分页修订

用户报告 0.1.0 过滤有效，但剩余评论很少或为空时无法上滑续页。这是用户提供的设备反馈；本机仍未连接设备。

新增 REA 方法证据：`KMPPageList.q(KMPPageList$c)`，`ev_01d6b3c73106ffbfe4f52b724a6615fda1ff867a9b10e8c46e6fb70d11652fea`，完整源正文可用。该方法依次计算宿主分页标志、处理 response、`t(response.a())` 保存当前页，最后 `u(false)` 结束加载。因此选择该方法的 afterHook 作为补页调度点，而非在解析中的 `w1` 直接发请求。

本地补充反编译 `KMPPageList`、`jqb.g`、`jqb.e` 和加载 flow：`k()` 表示加载中，`load()` 检查加载中和 hasMore，`p(Throwable)` 为错误完成路径；快手评论 `k()` 还检查登录锁后才调用 core.load。所有新增符号的精确 DEX 方法声明已扫描，原始本地记录位于 `.tools/kmp-lifecycle-members.jsonl` 和 `.tools/paging-base-members.jsonl`。

修订以保留宿主分页语义的有界续载处理“列表太短不能触发滚动”的情况。尚未证明设备卡住时的宿主 hasMore/折叠状态；不把这个静态分析推论描述为完整的运行时根因复现。

## REA Evidence 索引

0.1.2 页脚分析还检查了 `pcl.f`、`RecyclerFragment`、`CustomRecyclerView`，并提取原 APK 内的 `assets/dva_feature/comment_detail-master.zip`，确认具体 `CommentsFragment` 继承 `AbsCommentsFragment`。本地反编译显示 `Q1(View) -> e2(view,null) -> footerGroup.add`，`E2(View)` 删除 footerGroup 中的 View，`l2(View)` 检查存在。`Q1` 的 REA 源正文完整可用；其余接口和插件字段有本地 DEX/JADX 证据，不能据此声称已验证设备布局。

精简可携带索引见 `apk-evidence.json`；以下完整 CLI 记录留在本机忽略目录 `.tools/`：

| 本地记录 | Evidence ID |
| --- | --- |
| `package-evidence.json` | `ev_4553edb61d47ca142d534b9fa009ee9370fd3d4bb07243454deddcfe150ce9fb` |
| `qcomment-issub-evidence.json` | `ev_873609326e6b056d5744d25bc4755ebe236a3c00752febd7dfacb4f93d175c75` |
| `user-sex-evidence.json` | `ev_10b65d7bdebaa475be613e1d11ffd6f4e66d847053cbc13734d78e91aa692858` |
| `common-refs-evidence.json` | `ev_2176dcd3048cdaf72f9f087380a5b64906a1342239f9aa0eb0bedd4ec4486cf0` |
| `kmp-page-evidence.json` | `ev_114d5df40cc67b96cc0552fa53536b2364cea627878a78de664c5c46a808d312` |
| `kmp-i1-evidence.json` | `ev_8d2068f469b9f55f7100087e3bf63c3cd624fbfd9dea2d48af87b0b1190589e8` |
| `kmp-w1-evidence.json` | `ev_5570329c4a0bf9c39b329f76cc0ab528aae813ed70ab9451ae60de658697299b` |
| `kmp-completion-evidence.json` | `ev_01d6b3c73106ffbfe4f52b724a6615fda1ff867a9b10e8c46e6fb70d11652fea` |
| `comment-footer-add-evidence.json` | `ev_cad86b079b05b39044b9e27f91f1380d66f362edd17f49da8c87cad9fd795a85` |

普通本地辅助脚本输出不冒充 REA Evidence。`scripts/inspect_dex.py` 用于声明扫描；`scripts/Decompile.java` 用于选定 DEX 的辅助反编译。整 APK 的一次本地 JADX 尝试内存不足，已改为选定 DEX；不把失败尝试视为分析结果。一个内层类的 REA 查询因名称格式不匹配被拒绝，适配没有依赖该失败结果。

未把完整 APK 或全部反编译源代码加入工程源码；升级时应对新 APK 重新定位并新增版本适配，不能仅修改版本白名单。
