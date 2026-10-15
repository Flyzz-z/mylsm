## Why

当前 MemTable 只保存在内存中，进程退出后无法恢复。阶段 2 通过 WAL 学习操作日志、同步磁盘与顺序重放，使现有键值和删除语义获得明确的恢复路径。

## What Changes

- 引入单文件、仅追加的二进制 WAL，记录 PUT 和 DELETE，支持任意字节及空键、空值。
- 明确文件版本、记录边界、长度上限和 CRC32C 校验，区分不完整尾部与可检测的损坏。
- 采用每次操作同步磁盘后再更新 MemTable 的写入流程，定义 I/O 失败后的不可继续写入状态。
- 打开存储时从有效日志重建新的 MemTable；截去不完整尾部后允许继续追加。
- 提供单线程存储入口组合 WAL 与 MemTable，保留当前三态查询语义。
- 学习目标：理解操作历史与内存当前状态的区别、写前日志不变量、同步边界和恢复失败语义。
- 按设计、编解码、文件追加、恢复、存储组合的小步骤推进，每步解释与验证。
- 非目标：SSTable、冻结和刷盘、日志轮转或回收、压缩、并发、事务、MVCC，以及跨进程同时访问同一日志。

## Capabilities

### New Capabilities

- `wal`: 日志格式、同步追加、校验和恢复、写入失败状态及存储入口。

### Modified Capabilities

无。现有 `memtable` 仍是独立的内存组件，不增加磁盘持久性承诺。

## Impact

- 计划新增 `io.github.flyzz.mylsm.wal` 与单线程存储入口及对应 JUnit 测试，复用现有 ByteKey、Value、Tombstone 和 TreeMapMemTable。
- 使用 JDK 的 FileChannel、ByteBuffer 和 CRC32C，不增加第三方运行依赖。
- 文件测试使用 `@TempDir`，运行日志放在忽略的 `data/`；完成阶段时更新 README、路线图和学习笔记。
- 本 change 完成全部实现和验证前不更新正式 specs 或声明持久化 KV 已完成；当前进度见 tasks.md。
