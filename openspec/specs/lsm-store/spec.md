# lsm-store Specification

## Purpose

把现有 WAL、MemTable 与 SSTable 组合为目录级键值存储，在单实例并发调用和唯一后台工作线程之间协调日志轮换、单冻结表、写入背压与文件发布，保留最新记录和删除语义，并支持可验证的进程退出恢复。

## Requirements

### Requirement: Persist writes before exposing memory state

存储 SHALL 在完整追加并同步当前 WAL 后才更新活跃内存表和报告写入成功，保留任意二进制键值、空键、空值和 Tombstone。null、超出单条编码记录 16 MiB 上限及非法配置 SHALL 在修改文件或内存前被拒绝；参数错误 SHALL 不使正常实例失效。I/O 失败 SHALL 不报告成功，失败操作在恢复后是否出现取决于实际有效文件内容，不承诺回滚。

#### Scenario: Durable overwrite and delete
- **WHEN** 成功执行同一键的写入、覆盖及删除，随后进程未正常关闭便退出
- **THEN** 重启后恢复最终删除状态，空 Value 与 Tombstone 保持区别

#### Scenario: Invalid input
- **WHEN** 提交 null、超限记录或以非正阈值打开存储
- **THEN** 拒绝调用，不追加日志或改变查询结果，正常实例仍可使用

### Requirement: Bound background flushing to one frozen table

存储 SHALL 至多保留一个活跃表、一个冻结表和一个后台刷盘任务；冻结后 SHALL 不再修改旧表。达到正的可配置逻辑字节目标时 SHALL 在槽位可用时触发轮换和后台刷盘，允许合法单条记录跨过目标。冻结槽位忙且活跃表已达目标时，后续合法写入 SHALL 在追加其日志前等待槽位释放。逻辑字节目标 SHALL 不被描述为精确 Java 堆内存上限。

#### Scenario: Writes proceed during a blocked flush
- **WHEN** 后台暂停在冻结表的 SSTable 构建中，活跃表尚未达到目标
- **THEN** 前台仍可成功写入并查询新数据，不创建第二个冻结表

#### Scenario: Backpressure before logging
- **WHEN** 后台任务未完成且活跃表已达到目标，前台提交下一条合法写入
- **THEN** 写入等待且该操作尚未追加 WAL；后台发布后写入可继续

#### Scenario: One large record
- **WHEN** 合法单条记录大于配置的内存表目标
- **THEN** 接受完整记录并按相同轮换规则处理，不拆分或因目标较小拒绝它

### Requirement: Read the newest available record

点查 SHALL 按活跃表、冻结表、有效 SSTable 从新到旧查找，遇到 Value 或 Tombstone 即停止，仅缺失才继续。SSTable 刷盘和发布期间 SHALL 保持此顺序；文件错误 SHALL 明确失败，不通过跳过文件返回旧值或缺失。

#### Scenario: Active value shadows a flushing deletion
- **WHEN** 旧 SSTable 保存 k=A，冻结表保存删除 k，活跃表保存 k=B
- **THEN** 后台发布前后以及重启后查询 k 均为 B

#### Scenario: Tombstone shadows older files
- **WHEN** 最新有效记录是 Tombstone，而更旧的 SSTable 保存普通值
- **THEN** 返回 Tombstone，不返回旧值，也不把 Tombstone 当作当前层缺失

#### Scenario: File read failure
- **WHEN** 查询所需 SSTable 数据块发生 I/O 或格式错误
- **THEN** 查询报错并使实例停止服务，不继续查询旧文件

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

### Requirement: Fail and wake waiters on background errors

后台构建、打开输出或元信息提交失败时，存储 SHALL 记录失败、停止接受读写并唤醒全部等待者，不回收恢复仍需的 WAL。日志写入、轮换、内存应用或后台调度失败 SHALL 同样不能留下继续服务的过时状态。无引用旧文件删除失败 SHALL 只留下可忽略文件，不撤销已成功发布的数据。

#### Scenario: Background publication fails
- **WHEN** 后台 SSTable 构建或发布失败，同时有前台写入等待冻结槽位
- **THEN** 等待者及时得到失败，不无限等待；关闭后重启按有效元信息恢复

#### Scenario: Obsolete log deletion fails
- **WHEN** 新 SSTable 已发布但对应旧 WAL 删除失败
- **THEN** 已发布状态保持有效，重启不重放这份无引用 WAL

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
