## Why

当前 MemTable 的有序状态仅存在于内存，WalStore 重启必须重放完整日志。下一阶段需要先建立独立的不可变有序磁盘文件组件，理解记录、数据块、稀疏索引和完整性检查，为后续刷盘与多文件读取提供基础。

## What Changes

- 新增独立 SSTable 写入与读取组件，保存严格递增且唯一的字节键，以及 Value 或 Tombstone，支持空表、空键和空值。
- 设计带版本、长度边界和 CRC32C 的二进制文件，按数据块组织记录，以每块最大键建立稀疏索引，提供点查和有序遍历。
- 显式定义无序输入、重复键、截断、损坏与 I/O 失败的错误边界；失败文件不能作为成功结果使用。
- 按编解码、文件写入、索引与读取的小步骤实施，每步结合测试解释不变量与取舍。
- 本阶段不接入 WalStore，不实现 MemTable 冻结、自动刷盘、文件集元数据、WAL 回收、压缩、Bloom Filter、缓存、并发或 MVCC。

## Capabilities

### New Capabilities

- `sstable`: 单个不可变有序文件的格式、写入、点查、遍历和错误处理。

### Modified Capabilities

无；现有 MemTable、WAL 和基础模型契约保持原样。

## Impact

- 预计新增 `io.github.flyzz.mylsm.sstable` 包及对应 JUnit 5 测试，复用 ByteKey、Entry、Value、Tombstone。
- 新文件格式与 WAL 分离，不直接复用 WAL 操作历史格式；无已有 SSTable 迁移需求。
- 使用 Java 21 标准库的文件 I/O 和 CRC32C，不引入运行时依赖。
- 更新学习笔记、README 和路线图；完成验证与复盘后才归档并同步正式规范。
