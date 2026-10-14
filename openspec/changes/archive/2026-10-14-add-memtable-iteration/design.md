## Context

参见 proposal.md。用户已了解复制快照与冻结的区别，并选择本轮实现复制式有序快照。现有模型不可变，MemTable 仅供单线程使用。

## Goals / Non-Goals

**Goals:** 通过独立列表固定记录映射，按键顺序遍历，不丢失删除信息。

**Non-Goals:** 不冻结表、不做深层字节复制，不承诺并发获取快照的原子性，不实现 MVCC。

## Decisions

1. MemTable 增加 `List<MemTableRecord> snapshot()`。显式命名 snapshot，避免把固定结果误解为原表实时视图；List 便于学习和验证。保留原单线程约束。
2. `memtable.MemTableRecord` 使用 `record(ByteKey key, Entry entry)`，紧凑构造器检查两者非 null。组件均不可变，record 自动生成的内容相等与哈希行为合适；不存储可变的 Map.Entry。
3. TreeMap 按自然顺序遍历，逐条创建新的 MemTableRecord 并加入预分配容量的 ArrayList，最后 List.copyOf 返回不可修改列表。不直接返回 entrySet 或原表的包装视图，避免后续覆盖泄漏进快照。
4. 共享 ByteKey/Entry 引用，不复制内部字节数组。旧 Value 在表中被替换后，仍被旧快照引用，因此内容保持稳定。
5. 继续扩展 MemTableContractTest；排序、唯一性、删除保留、快照隔离与不可修改等约定适用于未来所有实现。仅记录构造的 null 边界单独测试。

## Risks / Trade-offs

- 快照创建消耗 O(n) 时间与额外空间，包括临时和最终列表引用数组 → 当前选择清晰语义；未来刷盘阶段再讨论冻结以避免整表复制。
- 旧快照延长旧键值的存活时间 → 使用完毕后释放引用，暂不增加 close 或资源管理接口。
- 误以为只读列表等于不可变内容 → 同时依赖独立配对和不可变组件，测试覆盖后续表修改及导出数组修改。
