# Manifest 状态与编解码

这是阶段 4 的第一个代码小步，介绍内存中的文件集快照、文件头/单帧编解码和状态转换校验。后续已实现[文件追加与恢复](manifest-file.md)、[目录恢复](lsm-store.md)及[后台刷盘](lsm-background-flush.md)；阶段 5 又增加了[全量压缩替换](manifest-compaction.md)。

## 状态如何表示

包内不可变 record `ManifestState` 保存 revision、activeWalId、frozenWalId、frozenWalLength 和从旧到新的 sstIds。构造器复制列表，拒绝 null、非正提交序号或有效编号、引用重复，以及不一致的 frozen 编号/长度。无冻结 WAL 用 `(0, 0)` 表示；有冻结 WAL 时封存长度至少为 8 字节文件头。封存长度是否等于真实文件长度，将由后续文件层验证。

SSTable 清单不按编号重排：它表达版本顺序。构造器用独立的 long[] 副本排序查重，保持原清单不变，相比额外装箱哈希集合减少校验大清单时的对象开销；代价是排序需要 O(n log n) 时间和 O(n) 临时空间。

构造单个状态不等于它可以出现在历史中的任意位置。`requireInitial()` 验证首状态必须是 revision=1、没有 frozen 且 SSTable 列表为空；`previous.requireSuccessor(next)` 验证相邻状态。

## 三个状态的字节示例

所有整数采用大端序。8 字节文件头是：

```text
4d 59 4d 46 | 00 00 00 01
   MYMF    | version = 1
```

每个帧包含 `payloadLength(4) | CRC32C(4) | payload`，CRC 覆盖原始长度四字节与 payload，跳过 checksum 字段。

下面的偏移均从单帧开头计算，与文件头无关：

| 帧偏移 | 长度 | 字段 |
| --- | --- | --- |
| 0 | 4 | payloadLength |
| 4 | 4 | CRC32C |
| 8 | 8 | revision |
| 16 | 8 | activeWalId |
| 24 | 8 | frozenWalId |
| 32 | 8 | frozenWalLength |
| 40 | 4 | sstCount |
| 44 | 每项 8 | sstIds |

用于测试的三次提交为：

| 状态 | revision | active | frozen | 封存长度 | SSTable | payload/帧长度 | CRC32C |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 初始 | 1 | 1 | 0 | 0 | [] | 36 / 44 | d4a0f081 |
| 轮换 | 2 | 2 | 1 | 12000 | [] | 36 / 44 | 203cf6ce |
| 发布 | 3 | 2 | 0 | 0 | [3] | 44 / 52 | 0bd9cd9a |

所以“8 字节头 + 初始帧”一共是 52 字节。这里尚未执行真实 WAL/SSTable 操作，封存长度 12000 只是格式样例。

阶段 4 的状态转换接受两类；阶段 5 保留它们，并增加受限全量替换：

- 轮换：旧 active 成为 frozen，换一个更大的 active 编号，SSTable 清单完全不变。
- 发布：active 不变，清除 frozen 及封存长度，SSTable 清单保留原前缀并追加一个更大的新编号。
- 全量替换：active、frozen 及其封存长度不变，至少两张 SSTable 替换为一张输出；输出大于所有输入 SST 编号，允许小于期间新建的 WAL 编号。

revision 必须恰好加一，Long.MAX_VALUE 后不能继续。轮换和 flush 发布的新编号仍必须大于全部当前引用；压缩输出则只要求大于全部输入 SST 编号，且与当前 WAL 不重合。全体有效引用上界沿合法历史不下降，压缩发布时可以不变。已移除历史引用不超过 SST 上界，因此仍拒绝历史引用编号复用，无须保留无界历史集合，证明及交错示例见[全量压缩替换](manifest-compaction.md)。实际分配器还需考虑目录孤儿及运行时预留编号，纯内存校验无法代替路径检查。

## 单帧解析与恢复的边界

`ManifestCodec.encodeHeader/decodeHeader` 处理文件头，`encode/decode` 处理恰好一个完整状态帧。decode 的顺序是：长度范围与对齐 → 精确帧长 → CRC → 数量与剩余字节精确匹配 → 构造状态并校验字段。

最大 payload 为 16 MiB，但实际长度必须是 `36 + 8*N`。所以最大有效载荷是 16 MiB - 4 字节，最多 2,097,147 个 SSTable 编号。构造器先检查列表容量再复制；解码先检查数量及字节数再分配列表，避免 count 乘法溢出或按损坏数量分配。大小上限仅限制编码载荷，Java 列表、Long 对象、校验数组仍有额外内存成本。

非法调用参数抛 NullPointerException/IllegalArgumentException；文件头或帧字节中的非法内容抛 IOException，并保留状态构造失败原因。合法帧字节不足抛 EOFException。完整帧内部数量错误属于 IOException，不能被解释成可以修复的残缺尾部。

decode 只验证当前帧，不承诺它是合法首状态或合法后继。未来恢复器须依次调用 requireInitial/requireSuccessor，将历史语义错误转换成文件错误；遇到完整损坏不能跳过或静默退回旧状态。当前 codec 不会自动截尾，也不会访问或验证清单中的真实文件。

## 后续运行语义

活跃表达到逻辑字节目标且冻结槽位可用时会轮换并启动后台刷盘；槽位忙且活跃表已满时，下一条写入在追加 WAL 前等待。目标不是精确堆内存上限。

计划中的显式 flush 会先等待已有冻结表发布，再轮换并等待非空活跃表，成功返回表示调用前的数据已发布。close 会等待已启动后台任务结束后关闭资源，但不强制刷剩余活跃表，后者由 WAL 恢复。这些接口尚未实现。

## 验证

本步新增 40 个测试用例，包含独立逐位 CRC32C 脚本生成的固定字节、初始/轮换/发布、多次代际转换、不可变性、long 边界、逐字节截断与修改、重算 CRC 后的非法字段、数量乘法溢出输入、最大对齐载荷及再加一项超限。

本步完成时 `./mvnw test` 全项目 399 个用例通过。后续文件 I/O 验证见 [Manifest 文件笔记](manifest-file.md)；进程退出恢复和线程协调仍待后续组合层实现与验证。
