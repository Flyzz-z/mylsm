# 读写锁下的并发点查

本步让多个线程共享同一个 LsmStore：get 可以同时读磁盘，写入、内存代轮换、Manifest 发布和生命周期变化持独占写锁。用户已确认先使用读写锁；锁外点查、文件集引用计数及 MVCC 留在未来单独讨论。当前 change 为 [support-concurrent-point-reads](../openspec/changes/support-concurrent-point-reads/design.md)，尚未做最终阶段复盘和归档。

## 两层锁分别保护什么

| 对象 | 共享读锁 | 独占写锁 |
| --- | --- | --- |
| LsmStore | get 全程，包括查询 active、frozen 及全部候选 SST | WAL 追加和同步、内存修改、轮换、文件发布、状态及资源列表变化 |
| SsTableReader | get、openCursor、Cursor.next/record，读取进度由独立 context 维护 | 关闭共享文件资源 |

采用公平 ReentrantReadWriteLock，避免持续新读者越过排队写者；不承诺线程调度时间上界。调用顺序为 store → reader，Reader 不回调 store。内存表和 WAL 独立类依然按原约定由外部串行保护。

```text
查询 A：store 读锁 → Reader 读锁 → 读取块 A ─────→ 释放
查询 B：store 读锁 → Reader 读锁 → 读取块 B ─────→ 释放
写入：             等待全部读者退出 → store 写锁 → WAL 同步及内存更新
发布：             等待全部读者退出 → store 写锁 → 提交并安装文件集
```

查询持锁期间，active/frozen 内容及 SST 文件集稳定。压缩发布取得写锁时，旧查询已退出；安装后新查询只取得新集，所以旧 Reader 仍可在安装后关闭，不需要文件集引用计数。后台构建继续用独立 Reader 在 store 锁外执行。

同键操作的实际执行顺序决定结果。put/delete 在 WAL 完整同步后才更新内存，写入成功返回后开始的 get 读取该状态或更新状态；并发重叠的写入由取得执行权的顺序排序。没有新增记录版本号、多键事务或快照语义。

## Reader 的可变状态

FileChannel.read(buffer, offset) 不修改共享文件位置，每次 get 的缓冲和解析结果是局部变量，索引和字节键值不可变。get 之间只可能共同修改文件错误状态：state 为 volatile，读取持共享锁时只能从 OPEN 单向写入 FAILED；关闭持独占锁，等 get 全部退出后才变为 CLOSED 并释放文件，所以 FAILED 不会覆盖已完成的关闭。

用户进一步选择[独立读 context](sstable-read-context.md)：Reader 不保存 scanning 或唯一 cursor，scan 复用自己的 Cursor。next/record 持共享读锁，不同 context 可以并行；Cursor.close 只清理局部缓冲，不取得 Reader 锁。scan 回调在锁外执行，可点查或创建其他游标；Reader.close 等当前读操作退出，使暂停游标的后续操作失败。每个游标仍由一个调用线程推进。

## flush 等待目标，不等待全局变空

在写锁内固定目标：优先选非空 active，否则选已有 frozen，没有数据立即返回。目标直接使用 MemoryTable 对象身份，每代增加一个受写锁保护的 published 标志，无须生成记录版本或修改磁盘格式。

后台成功提交并安装该代 SST 后设置 published=true，再清除 frozen 并 signalAll。flush 只检查目标 published；若目标仍是 active 且冻结槽位空闲，就轮换，否则释放写锁等待。其他写者或 flush 可以代为推进轮换。

```text
目标：B（此时 A 正在刷盘）
A 发布 → B 冻结并刷盘 → 新写入进入 C
B 发布 → 即使 C 已被冻结且尚未刷完，原 flush 也可以返回
```

两个 flush 可以等待同一个 B。调用确定目标后，写入同代的记录可能顺带刷出，进入更新代的写入不扩大本次等待范围；成功返回不要求 active/frozen 都为空。中断仍保留标志并报 InterruptedIOException，不取消已提交的刷盘。

## 手动压缩和关闭

compact 先完成自己的 flush 目标，再等待手动请求槽位，登记本次请求并等待 done/error。槽位保留到任务真正结束，多个调用者不会覆盖彼此的请求。期间新写入可能产生新内存代或 SST，所以并发场景下 compact 返回不保证当时全库只有一张表；没有并发写入时仍保持原单输出行为。

closeStarted 指定一次关闭的负责人。负责人在写锁内拒绝新操作并取消尚未执行的手动请求，释放锁后 join worker，最后独占释放资源、保存 closeFailure、变为 CLOSED 并通知。其他在此期间调用 close 的线程通过 Condition 等待并观察相同结果，等待中断延后恢复标志。关闭完成后再次调用仍无操作。

## 读错误为什么不能直接调用 fail

get 出错时还持读锁，而 fail 需要修改存储状态并通知 Condition，必须持写锁。持读锁直接申请写锁会等待自己释放访问权。

实现先保存读错误、释放读锁，再取得写锁登记 FAILED 并通知，最后传播原错误；参数和生命周期检查在记录读错误的范围外，不把无效参数变成存储故障。失败登记前已经接纳的操作可以完成，错误返回调用者前完成失败登记；若 close 已结束，不把 CLOSED 改回 FAILED。

## 验证与限制

读写锁初版新增 13 个用例：Reader 两个不同块的底层读取真实重叠、并发失败与关闭、暂停游标与点查；store 多读重叠、写入/发布/关闭等待、读失败无升级死锁、120 次并发写入与查询/flush/compact 交错后按实际 WAL 顺序核对并重开、多手动请求、两次 flush 等待目标且更新冻结代仍阻塞、并发关闭的共享错误结果和中断保留。所有文件位于 @TempDir，关键交错使用锁存器及有上限的等待控制。

后续独立 context 又新增 6 个用例并调整旧占用契约，详见[读 context 笔记](sstable-read-context.md)。当前 ./mvnw test 与 ./mvnw clean verify 通过 638 个测试，0 失败、0 错误；严格 OpenSpec 校验通过。既有独立 JVM 退出恢复测试继续通过，文件格式未变。

这是并行读、串行写的方案：慢磁盘查询仍会延迟 WAL 写入、文件发布及关闭；WAL 同步也会阻塞新查询。测试证明允许的交错及正确性，没有声称性能收益或吞吐上界。目录仍须单实例独占，没有新增跨进程锁或断电一致性保证。
