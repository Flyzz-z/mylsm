# SSTable 多路有序归并

本步实现 SsTableMerger，用多个[可暂停游标](sstable-cursor.md)输出严格递增、每键唯一且保留最新状态的记录流。它还没有接入 LsmStore，不提交 Manifest，不发布或删除输入文件，也不提供自动/手动 compaction 调度。

## 接口与输入顺序

```java
// oldest、middle、newest 是调用者已打开的独立 Reader。
try (SsTableMerger merger = SsTableMerger.open(List.of(oldest, middle, newest))) {
    while (merger.next()) {
        SsTableRecord record = merger.record();
        // 消费这个键的最新状态；Tombstone 仍作为记录输出。
    }
}
```

输入列表必须按旧到新排列，以后由 LsmStore 按固定的 Manifest 顺序提供。列表位置称为 generation，仅在这次归并内表达新旧，不是文件编号，也不写入记录格式。相同键取 generation 最大的一条。

open 获取各 Reader 的独占游标，但不读数据块；第一次 next 才取得各输入头。next 返回 true 后 record 可重复读取，推进前、耗尽后、失败及关闭后都不能取当前位置。耗尽后的 next 仍返回 false。归并器和 Reader 都是单线程使用。

## 最小堆维护什么

sources 为每路保存 generation、游标和头记录。heads 最小堆只保存尚未耗尽的输入，每路最多一个元素，按头记录的 ByteKey 排序。不会把全表记录加入堆。

例如：

| 输入（从旧到新） | 有序记录 |
| --- | --- |
| 旧表 | a=1，b=1，d=1 |
| 中表 | a=2，b=删除，c=2 |
| 新表 | a=删除，b=3，e=3 |

初始化后，三路头都是 a。一次 next 的步骤为：

1. 固定当前最小键 a。
2. 逐一出堆所有头为 a 的输入，按 generation 选择胜者 a=删除。
3. 每路出堆后推进游标；有下一条则更新头并重新入堆，耗尽则不再入堆。
4. a 的全部输入都成功推进后，把胜者设为 current，返回 true。

此时三路头都是 b。下一次 next 输出最新表的 b=3；最后完整输出为 a=删除、b=3、c=2、d=1、e=3。

堆只按键排序，同键之间的出堆顺序没有保证，因此不能直接把“最后出堆”当作最新版本。实现显式比较 generation。Source 的头会变化，所以必须先从堆取出再推进；如果修改仍在堆内的排序键，PriorityQueue 不会自动修复堆序。

## 错误与资源所有权

归并器拥有自己创建的游标，但借用输入 Reader。关闭归并器释放全部游标、头和 current，不关闭 Reader。调用方随后可以点查、scan 或建立新游标，最后负责关闭 Reader。归并期间不要操作或关闭这些输入 Reader。

获取中途遇到已占用、已失效或重复 Reader，会释放此前本次获得的游标；调用者原本持有的游标不被释放。null 列表或元素先完整校验，不会因末尾 null 而取得前面 Reader 的占用。

推进发生 IOException 时直接传播原错误，使归并器失效并释放所有自有游标，不再返回旧 current；健康 Reader 可再次使用，发生文件错误的 Reader 仍遵守已有 FAILED 契约。正常耗尽、提前关闭和失败时都不修改输入文件。

即使某条旧记录已被新表覆盖，旧输入也必须推进。若它的下一块损坏，这次 next 仍失败，不能直接跳过该输入。为了完整处理同键组，归并器会预先把参与输入推进到下一条，所以错误可能在返回当前胜者前发生；已经返回的合法前缀不回滚，调用方不得把部分结果发布为成功输出。

本步保持 checked IOException，没有把归并器实现为标准 Iterator。后续接入 Iterable Writer 时再做局部异常适配，并保留原始错误与清理异常。

## 成本与验证

输入共 N 条记录、K 路，归并约 O(N log K) 时间（K 为 0/1 时为常数处理或线性遍历）。堆和 Source 数组约 O(K)，每个输入游标保留当前块，另有本次胜者记录；Reader 常驻索引和读块临时分配另计。实现不收集全库记录；测试为了比较小样本结果使用列表和独立 TreeMap 模型。

新增 16 个用例：空输入/空表、重复键覆盖和删除后重写、输入顺序决定新旧、无符号二进制键与空键/值、大记录和跨块、7 代确定性写入模型、按需读块、提前/重复关闭、获取失败清理、原始 I/O 错误、被覆盖输入的损坏，以及外部错误关闭输入后的失败传播。还验证输出记录可写成合法 SSTable 并重新打开，不把这一单文件往返测试当作文件集发布。

`./mvnw test` 和 `./mvnw clean verify` 均通过，共 560 个用例、0 失败；`openspec validate --all --strict --no-interactive` 通过。

后续已实现 [Manifest 的受限替换转换](manifest-compaction.md)；后台任务、请求等待和文件回收仍待后续接入。
