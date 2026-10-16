## Why

目前 WAL/MemTable 与 SSTable 各自可用，但还没有把写入、后台刷盘、多文件查询和重启恢复串成数据库入口。用户选择一个活跃 MemTable、最多一个冻结 MemTable、一个后台刷盘线程，学习如何在继续写入时安全发布 SSTable，并在刷盘跟不上时限制内存增长。

## What Changes

- 新增目录级 LSM 存储入口，复用字节模型、MemTable、WAL 与 SSTable；写入仍先同步 WAL，再更新内存。
- 以可配置的逻辑字节阈值触发后台刷盘；最多保留一个冻结表，槽位忙且活跃表已满时，后续写入等待。
- 冻结时先准备新 WAL，再提交包含旧、新 WAL 的文件集状态，之后才允许向新 WAL 写入。
- 新增 Manifest，记录有效 SSTable 的新旧顺序、活跃 WAL 和可选冻结 WAL；SSTable 发布与移除冻结 WAL 引用在同一次元信息提交中完成。
- 查询按活跃表、冻结表、SSTable 从新到旧查找，保留 Value、Tombstone、缺失三态。
- 支持等待刷盘的显式 flush、错误唤醒、关闭、文件集恢复及仅对无引用文件的清理。
- 本阶段不实现压缩、范围查询、Bloom Filter、通用多调用线程 API、跨进程锁、MVCC 或断电持久性保证。

## Capabilities

### New Capabilities

- `lsm-store`: 目录级读写、单冻结表后台刷盘、背压、三态查询和生命周期。
- `lsm-manifest`: 有效文件集的可恢复提交、日志切换与 SSTable 发布边界、文件引用验证。

### Modified Capabilities

无。现有 WAL 和 SSTable 的单组件契约保持不变；新增入口负责线程间协调与文件集协议。

## Impact

- 预计新增 `lsm` 包及对应 JUnit 测试，按小步引入 Manifest 编解码、I/O、恢复和后台任务协调。
- 复用 `WalWriter`、`SsTableWriter`、`SsTableReader`，不通过暴露 WalStore 的私有 MemTable 绕过其生命周期。
- 使用 JDK 21 同步原语与单工作线程，不新增框架依赖。旧单文件 WalStore 不自动迁移为目录级存储。
- 更新 README、路线图及学习笔记；正式 specs 仅在本阶段实现、验证和复盘完成后同步。
