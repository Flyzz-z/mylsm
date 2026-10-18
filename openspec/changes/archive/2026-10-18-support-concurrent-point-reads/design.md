## Context

动机见 proposal.md。现有状态互斥锁保护所有前台 API 与发布，Condition 等待释放锁。单 worker 使用独立 Reader 锁外构建，点查 Reader 有单线程生命周期状态。用户已理解并确认读写锁方案及固定目标 flush。

## Goals / Non-Goals

**Goals:** 单实例并发点查，写入及文件集切换串行；保持已有三态、恢复、背压和失败传播。

**Non-Goals:** 见 proposal.md；本步不把磁盘点查移到状态锁外，不提供快照或多键事务。

## Decisions

### 存储读写锁

采用公平 ReentrantReadWriteLock：get 全程读锁，其余共享状态修改使用写锁，Condition 从写锁创建。锁序始终为 store → reader；Reader 不回调 store。公平模式避免持续读者插入已排队写者之前，不承诺线程调度时间上界。WAL 同步仍在写锁内，查询期间写入与发布等待。

读失败先保存错误并释放读锁，再取得写锁登记 FAILED 和通知；绝不持读锁升级写锁。失败登记前已进入的操作可以结束，错误返回调用者前完成登记。若 close 已完成，不把 CLOSED 改回 FAILED。参数和生命周期检查错误不使正常实例失败。

### Reader 并发边界

Reader 自有公平读写锁，用于保护共享文件资源的生命周期；get、openCursor、Cursor.next/record 使用共享读锁，close 持独占写锁。并行读取可以单向写入 volatile FAILED，close 等读操作退出后才变为 CLOSED，避免失败覆盖关闭。

用户进一步要求将扫描状态独立为每次读取的 context。复用现有 Cursor 类型：每个游标自己持有 records、nextBlock、recordIndex 和 closed，Reader 不再保存 scanning 或唯一 cursor。不同游标、点查和 scan 可以交错或并行；同一 Cursor 由单一调用线程使用，close 只清理其局部缓冲，不影响 Reader 和其他 context，也无须取得 Reader 锁。

scan 在内部创建独立 Cursor，循环 next/record；回调执行时不持 Reader 锁，允许回调点查、创建游标或启动另一次扫描。回调异常只终止本次扫描并释放自己的 context；回调关闭 Reader 时关闭可以完成，本次扫描的下一次操作明确失败，不死锁也不伪装耗尽。

Reader.close 等当前 get/next/record 操作退出，即使有暂停的游标也不等待其整个生命周期；设置 CLOSED、关闭文件，使所有已有游标后续 next/record 拒绝。暂停游标的局部缓冲由各自 close 释放，不使用全局 context 注册表或引用计数。一个 context 遇到文件错误会使共享 Reader 失效，其他 context 不能再返回缓存位置；参数或用户回调错误不导致共享失败。

相比每次新开 Reader，复用索引和文件句柄；相比移除全部 Reader 锁，保留了安全关闭与读取的互斥。Merger 只拥有本次创建的游标，允许调用方保留其他独立游标；重复输入同一 Reader 的既有拒绝契约改为显式参数校验。

### flush 固定目标对象

写锁内选取非空 active，否则选已有 frozen，否则立即返回。目标是 MemoryTable 对象身份，不增加记录版本号或磁盘字段。每代持有 published 标志，后台成功提交并安装 SST 后置 true。循环只等目标 published：目标仍是 active 且冻结槽可用时轮换，否则 Condition 等待。别的写者可以推进轮换，目标发布后即使 frozen 指向更新代也可返回。多个 flush 可等待同一对象。

### compact 与 close

复用单请求槽，正在执行时不释放；并发调用各自在 flush 后等槽位，登记独立请求并等待结果。新的写入可能在请求前后产生其他表，所以成功只保证目标数据已发布且本次归并完成；没有并发写入时仍保持单输出。

close 由首个调用者负责，其他已参与关闭的调用者释放写锁等待 CLOSED，并观察同次关闭结果；已 CLOSED 后再次调用仍无操作。所有关闭路径保存结果、完成资源释放并 signalAll。等待不取消关闭，恢复调用线程中断标志。后台线程在 join 时所需的锁不被持有；文件关闭前所有 get 已退出。

## Risks / Trade-offs

- [慢查询阻塞写入和发布] → 本步接受，后续单独讨论文件集引用计数。
- [两层读写锁与错误登记] → 固定锁序，故障注入验证无升级死锁。
- [刷盘代被其他调用者推进] → 按对象身份及 published 等待，测试后来 frozen 未结束时返回。
- [多个维护请求及关闭交错] → 每请求终态、单关闭负责人、Condition 循环检查；不更改恢复协议。

## Migration Plan

先实现 Reader 并行点查及 store 读写锁与等待边界，再按用户反馈拆出独立扫描 context；用锁存器证明真实重叠及发布阻塞，验证重开与故障，完成学习笔记和小步提交。当前实现、验证和阶段复盘已完成，记录见 docs/concurrency-review.md；已同步正式规范，本次收尾归档并合入 main。
