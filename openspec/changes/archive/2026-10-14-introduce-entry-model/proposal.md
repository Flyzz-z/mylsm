## Why

字节键已实现，下一步需要明确内存记录中“有值”和“已删除”的区别，避免空值、删除和未找到相互混淆。学习目标是理解封闭的记录类型、字节值所有权，以及记录状态与查询结果的区别。

## What Changes

- 新增仅允许值与删除标记两种变体的记录类型。
- 值独立持有字节内容，支持空值、拒绝 null，按内容判断相等。
- 提供无载荷的单例删除标记。
- 记录未来查询以 Optional 表达记录缺失的约定。

## Capabilities

### New Capabilities
- `entry-model`: 不可变字节值与显式删除标记构成的记录模型。

### Modified Capabilities

无。

## Impact

新增 Entry、Value、Tombstone 及对应测试与学习笔记，无新依赖，不改变 ByteKey 契约。

## Non-goals

不实现 MemTable、查询方法、刷盘或磁盘文件格式。Optional 查询签名仅作为后续接口设计上下文。
