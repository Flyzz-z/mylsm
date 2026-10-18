## MODIFIED Requirements

### Requirement: Wait for explicit flush completion

存储 SHALL 提供显式等待刷盘的操作，成功返回时调用前的全部内存记录均已发布为有效 SSTable。操作 SHALL 固定取得协调权时的非空活跃代或已有冻结代为目标，等待该代及更早代发布，不因之后产生的代扩大目标；已有冻结任务 SHALL 先完成，再处理目标活跃表；空表 SHALL 不生成无意义的空 SSTable。等待 SHALL 释放发布所需的锁，后台失败 SHALL 唤醒等待者并报告错误。普通等待中断 SHALL 保留中断信息且不丢弃仍有效的数据。

#### Scenario: Flush with two memory generations
- **WHEN** 存在冻结表及非空活跃表并调用显式刷盘
- **THEN** 等待两代依次发布，重启后这些记录可由 SSTable 读取，任意时刻最多一个冻结表

#### Scenario: Empty or interrupted flush
- **WHEN** 对没有待刷数据的存储调用刷盘，或等待中调用线程被中断
- **THEN** 空刷盘不创建 SSTable；中断等待报告中断且不移除未发布数据或错误地宣称成功

#### Scenario: Later generations do not extend the target
- **WHEN** 两个 flush 等待同一目标代，而其他线程已产生更晚且尚未发布的冻结代
- **THEN** 目标代发布后两次调用均可成功返回，无须等待更晚代

### Requirement: Coordinate lifecycle and recovery with the worker

存储 SHALL 明确要求单实例独占目录，允许多个线程并发调用同一实例，内部唯一工作线程串行执行刷盘与压缩。打开 SHALL 按有效文件集恢复活跃表及可选冻结表，成功前不暴露部分状态，恢复冻结表后 SHALL 恢复唯一刷盘任务。关闭 SHALL 拒绝新操作，等待已启动后台任务停止后释放其可能访问的资源，允许剩余活跃数据由 WAL 恢复。重复关闭 SHALL 安全，关闭失败后 SHALL 不恢复服务。

#### Scenario: Reopen two pending generations
- **WHEN** 进程在冻结表刷盘期间退出，元信息引用 frozen WAL 与 active WAL
- **THEN** 重启分别恢复两代表，查询遵守新旧顺序，重新启动至多一个刷盘任务

#### Scenario: Close during background construction
- **WHEN** 后台正在写 SSTable 时前台关闭
- **THEN** 不提前关闭后台使用的资源或死锁，任务结束后完成资源释放，活跃数据仍能重启恢复

#### Scenario: Concurrent close callers
- **WHEN** 多个调用者在关闭期间调用 close
- **THEN** 资源只释放一次，参与同次关闭的调用者等待其结束并观察同一结果，等待中断不提前释放资源

## ADDED Requirements

### Requirement: Serve concurrent point reads with ordered writes

存储 SHALL 允许多个点查同时读取磁盘，点查期间 SHALL 保持内存及有效文件集稳定，写入和发布 SHALL 与点查互斥。单键读写 SHALL 线性一致：写入成功后开始的查询读取该状态或更新状态，重叠写入按实际执行顺序生效。读错误 SHALL 报告并停止后续服务，不通过等待自身持有的共享访问权转换为独占访问而死锁。

#### Scenario: Overlapping disk lookups
- **WHEN** 两个调用者查询已发布表中的键且读取被暂停
- **THEN** 两次磁盘读取可同时进入，写入、替换发布和关闭不能回收它们仍使用的资源

#### Scenario: Concurrent writers and recovery
- **WHEN** 多个线程交错写入、覆盖和删除，随后关闭并重开
- **THEN** 查询状态符合实际日志顺序，成功写入不丢失，删除标记不变成缺失

#### Scenario: Read failure with another reader in progress
- **WHEN** 一个查询失败而其他查询尚未结束
- **THEN** 错误登记和后续拒绝无锁升级死锁，关闭与恢复仍能完成
