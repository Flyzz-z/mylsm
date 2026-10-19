## Why

现有 TreeMapMemTable 已提供可靠的内存表语义，但有序索引由标准库维护。本阶段通过手写跳表理解多层查找、前驱路径和指针插入，并在保持已有契约的前提下增加一种 MemTable 实现。

## What Changes

- 增加 SkipListMemTable，复用 ByteKey、Entry、Value 和 Tombstone。
- 分小步实现节点、查找、插入覆盖、删除标记与稳定有序快照。
- 复用 MemTable 契约测试，以可控高度和 TreeMap 对照验证跳表。
- 先完成节点、查询、插入和覆盖，再按用户指挥的小步骤实现删除与快照；尚未满足完整契约前保持包内可见，不实现 MemTable 接口，也不接入存储引擎。
- 非目标：物理删除、并发跳表、MVCC、范围游标、性能基准及自动切换 LsmStore/WalStore 的实现。

## Capabilities

### New Capabilities

- `skip-list-memtable`：可实例化的跳表内存表，遵守现有 MemTable 契约；允许确定性地验证不同层高下的读写行为。

### Modified Capabilities

无；现有 memtable 契约保持不变。

## Impact

新增 memtable 包内实现、JUnit 测试与学习笔记，不增加依赖，不修改持久化格式。TreeMapMemTable 保留作为对照和当前存储引擎实现；接入存储引擎另行讨论。
