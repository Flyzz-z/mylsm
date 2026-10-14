## Why

内存表已有点读写能力，后续刷盘还需要按键顺序取得完整记录。用户已选择复制式不可变快照，学习重点是固定记录映射与共享不可变对象的区别。

## What Changes

- 为 MemTable 增加 snapshot()，返回按键升序排列的不可修改记录列表。
- 新增包含 ByteKey 与 Entry 的 MemTableRecord，拒绝 null 组成部分。
- 快照包含删除标记，每键只有获取时的最新记录，且不受后续表修改影响。
- 增加可复用契约测试和复制快照的学习记录。

## Capabilities

### New Capabilities

无。

### Modified Capabilities
- `memtable`: 增加有序且固定的记录快照，用于遍历。

## Impact

扩展 MemTable 接口与 TreeMap 实现、共享契约测试及学习文档。未来其他实现需提供 snapshot()；无新增依赖。

## Non-goals

不实现冻结、后台刷盘、磁盘存储、并发、MVCC、范围查询或惰性迭代器。
