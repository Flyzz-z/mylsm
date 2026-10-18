## MODIFIED Requirements

### Requirement: Coordinate lifecycle and recovery with the worker

存储 SHALL 明确要求单实例独占目录、前台串行调用，内部唯一工作线程串行执行刷盘与压缩。打开 SHALL 按有效文件集恢复活跃表及可选冻结表，成功前不暴露部分状态，恢复冻结表后 SHALL 恢复唯一刷盘任务。关闭 SHALL 拒绝新操作，等待已启动后台任务停止后释放其可能访问的资源，允许剩余活跃数据由 WAL 恢复。重复关闭 SHALL 安全，关闭失败后 SHALL 不恢复服务。

#### Scenario: Reopen two pending generations
- **WHEN** 进程在冻结表刷盘期间退出，元信息引用 frozen WAL 与 active WAL
- **THEN** 重启分别恢复两代表，查询遵守新旧顺序，重新启动至多一个刷盘任务

#### Scenario: Close during background construction
- **WHEN** 后台正在写 SSTable 时前台关闭
- **THEN** 不提前关闭后台使用的资源或死锁，任务结束后完成资源释放，活跃数据仍能重启恢复
