## Why

现有 LsmStore 要求前台串行调用，互斥锁使慢磁盘点查阻塞其他查询。用户已确认先采用读写锁支持单实例并发访问，学习查询、写入、文件发布和生命周期的同步边界。

## What Changes

- 多个 get 全程持读锁并行执行；WAL 写入、MemTable 修改、轮换、发布及生命周期变化持写锁，保持单键读写线性一致性。
- SSTable Reader 共享文件和索引，点查、scan 和多个独立 Cursor 可以并行；每个 Cursor 自有缓冲和进度，scan 复用独立 Cursor。关闭等待当前读操作结束后使所有 context 失效。
- flush 固定调用取得锁时的目标内存代，等待其发布，不追逐后来的内存代。
- compact 支持并发调用并保留各自完成结果，先完成本次 flush 目标；并发写入时不承诺返回瞬间仅有一张有效 SSTable。
- 多个 close 调用协调为一次资源释放，唤醒等待者；读错误释放读锁后再以写锁登记失败，避免锁升级死锁。
- 非目标：查询期间并行写入、文件集引用计数、MVCC、快照、数据库级范围扫描、批量提交、多进程访问和磁盘格式变更。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `lsm-store`: 并发点查、串行写入、固定目标 flush 与并发关闭。
- `sstable`: 并发点查、独立扫描 context 及共享资源关闭的协调。
- `lsm-compaction`: 并发前台调用及同步手动请求完成边界。

## Impact

修改 LsmStore、SsTableReader、相关测试和学习文档；Java 21 与现有依赖保持不变。正式规范在最终复盘归档时同步，本次只实现已确认的读写锁方案。
