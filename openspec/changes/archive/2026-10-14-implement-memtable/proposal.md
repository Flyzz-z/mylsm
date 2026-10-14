## Why

字节键和记录模型已具备，需要把它们组合成可读写的内存表。学习目标是理解“每键最新状态”、查询三态、删除遮蔽，以及通过接口替换底层数据结构。

## What Changes

- 增加 MemTable 的 put/get/delete 接口，提供 TreeMapMemTable 实现。
- 查询返回 Optional<Entry>；删除始终保存 tombstone；重新写入覆盖删除标记。
- 明确单线程与 null 拒绝约定，建立可复用的接口契约测试。
- 按职责组织 model 与 memtable 子包。**BREAKING**：基础类型从根包迁入 model，源码使用者需更新 import；本项目尚无已发布 API。

## Capabilities

### New Capabilities
- `memtable`: 单线程内存键值表的写入、三态查询和逻辑删除。

### Modified Capabilities

无。已有模型行为保持不变，包迁移属于代码组织调整。

## Impact

新增内存表接口、实现和测试；迁移四个模型类及测试包，更新学习文档。无新依赖。

## Non-goals

不实现有序遍历、容量阈值、持久化、并发、快照或压缩；当前 MemTable 数据仅在内存中有效。
