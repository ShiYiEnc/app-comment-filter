# 抖音 40.9.0 的 KMP 评论分支

## 真实设备确认的遗漏

在 Android 16 的 OPD2404 设备上，0.2.5 模块成功安装 4 个经典响应消费者，配置加载成功且 `enabled=true`，但读取计数和最近评论时间始终为 0，没有运行异常。

短期诊断构建进一步记录到：经典 consumer、dispatch、getter 均完成安装，却没有任何命中。活动 Fragment 栈显示 `PadSplitCommentListPageFragment` 下的实际评论子 Fragment 为：

`com.ss.android.ugc.aweme.comment.commentlist.kmp.KmpCommentListFragment`

因此这次遗漏有明确的分支证据，不应归因于关闭过滤或直接猜测为 ART 内联问题。设备安装的抖音 APK 与用户提供的 APK SHA-256 一致：`bad30e22688238a118f360138fe82da3c6346be84b43b23a16681a168680f6b0`，版本为 `40.9.0 / 400901`，没有 split APK。

原始 Activity dump 可能包含私人界面信息，不加入 Git；这里只保存必要类名和诊断结论，不保存评论、作者、具体规则值或设备序列号。

## APK 中的实际调用边界

KMP 并非只有另一套布局：`KmpCommentListFragment` 不继承经典评论列表 Fragment，使用 `KCmtListViewModel`、独立数据源和事件链处理评论。

在 APK 的 `classes9.dex` 中：

| 声明 | DEX 签名 | codeOffset |
| --- | --- | --- |
| `KCmtListViewModel.NA` | `()LX/0SPh;` | 3496928 |
| `X.0SPh.LIZ` | `(LX/0RzE;Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;)V` | 5559508 |

`X.0RzE` 的父类为 `java.lang.Enum`。数据源消费真实 `CommentItemList` 并读取 `items`，按请求类型发出首屏/刷新事件或加载更多事件，之后才转换成评论树和 UI 条目。它没有经过 0.2.5 安装的经典主列表消费者。

以上方法名称和混淆类只用于解释静态证据，生产代码没有硬编码 `NA`、`X.0SPh`、`LIZ` 或请求枚举名称。

## 修复方法

1. 以稳定类 `com.douyin.kmp.comment.comment.commentpanellist.list.KCmtListViewModel` 为起点，检查其无参实例 getter 的返回类型。
2. 只接受唯一的非静态、非抽象、非桥接 `(enum, CommentItemList) -> void` 消费方法；重复 getter 返回同一个类型不算多个候选。
3. 在该入口执行现有规则。绑定对象明确响应位于参数索引 1，参数索引 0 的请求枚举保持原样。
4. 隐藏条目时复制响应及继承字段，只替换复制品的 `items`。模块不改输入响应、原列表、游标、总数、hasMore 或配置字段；未命中或过滤关闭时保持响应对象身份。
5. 保留已完成的经典消费者、旧模型以及树管理器兼容路径。宿主没有 KMP 类时明确记录 classic-only；如果有 KMP 类却无法唯一定位，则报告适配失败，不把该问题吞掉后继续宣称正常安装。

成功读取状态仍由真实的 `DouyinCommentReader.filter` 上报，不通过伪造读取数解除“等待评论”。

## 短期诊断边界

`[CommentFilter][DYProbe]` 输出精确安装 Member、命中次数、开关状态、owner/参数的实际类名、列表大小及有限的条目结构统计。KMP 入口会明确记录 `install kmp responseArg=1`，命中记录使用 `kmp` 标签。

每个入口只在第 1、2、4、8、16、32、64、128、256 次打印，最多 9 行；单次最多检查 256 条，最多记录 8 种 `commentType` 数字和 3 种外来类名。`replyId` 只统计是否非空的数量，不输出实际 ID；不调用响应或评论对象的 `toString()`，不输出正文、属地值、作者资料、令牌或具体配置内容。

## 验证范围

新增 `DouyinKmpBindingsTest` 4 项，覆盖混淆改名、枚举/响应参数约束、缺失或歧义拒绝、正确响应参数索引、首屏/刷新/加载更多枚举保留、源响应/列表/分页元数据保留，以及关闭过滤/无命中时对象身份不变。连同经典消费者和诊断测试，抖音相关 17 项测试通过。

修复构建已在同一真实设备上验证：新 KMP 入口连续命中，第 1、2 次均记录 `enabled=true, items=20, main=20, commentTypes={0=20}`，对应的屏幕评论由原先混杂列表变为只保留符合当前白名单的少量条目。这里不保存规则的具体属地、评论内容或作者信息。这证明新入口确实在该设备的活动评论链执行过滤，而不是仅安装成功或伪造状态。

聚合计数及进一步原生追加翻页由主任务的设备验证记录保存。不能据单个设备、单个 APK 的成功扩大声称所有新版本、独立详情、直播/电商及其他热更新实现均兼容。
