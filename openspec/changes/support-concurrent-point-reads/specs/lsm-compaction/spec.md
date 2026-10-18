## MODIFIED Requirements

### Requirement: Wait for manual background compaction

存储 SHALL 提供同步手动压缩入口，先等待调用前的全部内存数据发布，再提交后台请求并等待完成。请求被取走 SHALL 不视为完成；任务成功发布、因少于两张表而无操作结束、或失败时 SHALL 登记结果并通知等待者。多个调用者 SHALL 各自等待对应请求的完成结果，等待时 SHALL 释放工作线程所需的锁；等待中断 SHALL 保留中断信息并报告错误，不取消已经提交的任务。

#### Scenario: Pending memory generations
- **WHEN** 有已发布表及待刷的冻结或活跃记录时调用手动压缩
- **THEN** 先完成这些内存代发布，再等待后台处理手动请求，没有并发写入时成功后只保留一张有效 SSTable（无数据时保持空集）；后来的并发写入可以产生新的内存代及已发布表

#### Scenario: Repeated compaction without new writes
- **WHEN** 没有待刷内存且有效表少于两张时调用手动压缩
- **THEN** 成功返回且不创建额外输出或更改有效文件集

#### Scenario: Failure or interruption while waiting
- **WHEN** 手动调用等待期间后台失败，或调用线程被中断
- **THEN** 调用及时得到对应错误，不无限等待，不把请求被取走误报为成功；中断不丢弃仍有效数据

#### Scenario: Multiple manual callers
- **WHEN** 多个调用者提交手动压缩，期间其他线程继续写入
- **THEN** 每次调用先完成各自 flush 目标，再等待对应后台请求的结果，不覆盖其他请求，不要求返回瞬间全库仅一张表

### Requirement: Preserve foreground writes during background compaction

自动压缩的文件构建期间 SHALL 允许前台并发调用读写且不共用不支持并发访问的读取状态；发布 SHALL 基于最新有效日志引用，保留期间的成功写入及 WAL 轮换。关闭 SHALL 拒绝新请求、停止启动新的自动任务并等待已开始任务安全结束；尚未开始的手动请求 SHALL 完成或明确失败，不能遗留无限等待者。后台失败 SHALL 停止服务并唤醒等待者，自动任务的错误 SHALL 由后续操作或关闭报告。

#### Scenario: Foreground updates and rotation during compaction
- **WHEN** 后台暂停在构建中，前台成功覆盖、删除并轮换 WAL
- **THEN** 查询、压缩发布及重启均保留最新状态，发布不回退 active/frozen 引用

#### Scenario: Close or failure during automatic compaction
- **WHEN** 后台自动压缩期间发生失败，或前台关闭存储
- **THEN** 不提前释放仍使用的资源，失败被记录和报告，恢复所需文件保留，不再启动新自动任务
