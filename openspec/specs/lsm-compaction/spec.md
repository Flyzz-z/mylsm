# lsm-compaction Specification

## Purpose

通过自动或手动触发后台归并全部已发布有序表，减少重复历史记录及点查所需文件数，并在维护现有三态查询语义的同时安全替换有效文件集，使中途失败与进程退出后仍能从完整有效来源恢复。

## Requirements

### Requirement: Schedule automatic full compaction

存储 SHALL 使用唯一后台工作线程串行执行 flush 与 compaction，已发布表数达到可配置阈值时自动压缩，默认 4 且最小为 2；非法阈值 SHALL 在修改文件前拒绝。恢复成功和文件集发布后 SHALL 重新判断，不以空转或定时轮询发现任务。自动压缩 SHALL 只读取任务开始时全部已发布 SSTable，不强制冻结活跃内存，少于两张表 SHALL 不创建输出。选择下一任务时 SHALL 优先 flush，其次手动请求，最后自动压缩；已开始任务 SHALL 执行到底。

#### Scenario: Threshold reached after publication or recovery
- **WHEN** 成功发布或恢复的有效 SSTable 数量达到阈值
- **THEN** 后台最终归并全部已发布表为单张；没有待处理任务时阻塞等待，不重复压缩单张输出

#### Scenario: Flush appears during a compaction
- **WHEN** 压缩已开始且前台产生 frozen
- **THEN** 不并行启动 flush，压缩结束后优先处理 frozen，活跃表达到容量限制时继续遵守已有写入背压

#### Scenario: Explicit flush does not wait for automatic compaction
- **WHEN** 显式刷盘已成功发布调用前的数据，并使有效表数达到自动压缩阈值
- **THEN** 刷盘可以返回，不要求等待随后自动压缩完成；后台继续按同一条件判断并执行压缩

### Requirement: Wait for manual background compaction

存储 SHALL 提供同步手动压缩入口，先等待调用前的全部内存数据发布，再提交后台请求并等待完成。请求被取走 SHALL 不视为完成；任务成功发布、因少于两张表而无操作结束、或失败时 SHALL 登记结果并通知等待者。前台 SHALL 继续串行调用，等待时 SHALL 释放工作线程所需的锁；等待中断 SHALL 保留中断信息并报告错误，不取消已经提交的任务。

#### Scenario: Pending memory generations
- **WHEN** 有已发布表及待刷的冻结或活跃记录时调用手动压缩
- **THEN** 先完成这些内存代发布，再等待后台处理手动请求，成功后只保留一张有效 SSTable（无数据时保持空集）

#### Scenario: Repeated compaction without new writes
- **WHEN** 没有待刷内存且有效表少于两张时调用手动压缩
- **THEN** 成功返回且不创建额外输出或更改有效文件集

#### Scenario: Failure or interruption while waiting
- **WHEN** 手动调用等待期间后台失败，或调用线程被中断
- **THEN** 调用及时得到对应错误，不无限等待，不把请求被取走误报为成功；中断不丢弃仍有效数据

### Requirement: Preserve foreground writes during background compaction

自动压缩的文件构建期间 SHALL 允许前台继续串行读写且不共用不支持并发访问的读取状态；发布 SHALL 基于最新有效日志引用，保留期间的成功写入及 WAL 轮换。关闭 SHALL 拒绝新请求、停止启动新的自动任务并等待已开始任务安全结束；尚未开始的手动请求 SHALL 完成或明确失败，不能遗留无限等待者。后台失败 SHALL 停止服务并唤醒等待者，自动任务的错误 SHALL 由后续操作或关闭报告。

#### Scenario: Foreground updates and rotation during compaction
- **WHEN** 后台暂停在构建中，前台成功覆盖、删除并轮换 WAL
- **THEN** 查询、压缩发布及重启均保留最新状态，发布不回退 active/frozen 引用

#### Scenario: Close or failure during automatic compaction
- **WHEN** 后台自动压缩期间发生失败，或前台关闭存储
- **THEN** 不提前释放仍使用的资源，失败被记录和报告，恢复所需文件保留，不再启动新自动任务

### Requirement: Merge newest records without changing three states

归并 SHALL 按统一字节键序输出严格递增且唯一的键；相同键 SHALL 取输入在有效元信息顺序中最新的一条，保留其 Value 或 Tombstone。归并 SHALL 逐块消费，不要求将全部输入记录收集到内存；所有输入块 SHALL 验证后才产生记录，错误不得当作正常耗尽。

#### Scenario: Overwrites and deletions across blocks
- **WHEN** 多个输入表跨数据块包含相同键的旧值、新值、删除及删除后重写
- **THEN** 每键仅保留最新状态，压缩前后及重启后三态点查一致，空键、空值及二进制数据保持原样

#### Scenario: Delete an absent key
- **WHEN** 最新记录为对原本不存在键的删除标记
- **THEN** 压缩后仍返回 Tombstone，不将其变为缺失

### Requirement: Publish complete output before reclaiming inputs

存储 SHALL 仅在输出完整构建、同步并成功打开后提交一次文件集替换；提交成功后 SHALL 安装新读取集并释放旧输入资源，再尝试删除无引用旧文件。构建、读取、打开或提交失败 SHALL 报错并停止服务，保留恢复所需输入及提交结果不确定的输出，不承诺失败回滚。旧文件删除失败 SHALL 不撤销有效发布。

#### Scenario: Input corruption or output failure
- **WHEN** 归并读取发生损坏，或输出构建、同步、打开失败
- **THEN** 不报告压缩成功，不发布部分结果，不回收输入文件

#### Scenario: Uncertain metadata commit
- **WHEN** 新文件集记录已完整追加但同步报错
- **THEN** 停止服务且保留新旧文件，重启依据实际完整有效元信息选择文件集

#### Scenario: Obsolete table deletion fails
- **WHEN** 替换已提交而某个旧 SSTable 删除失败
- **THEN** 查询和恢复仍使用新输出，遗留旧文件只作为孤儿处理

### Requirement: Recover across compaction process exits

进程退出恢复 SHALL 仅使用最后完整有效元信息选择旧输入全集或新输出，不依据文件存在与编号推断发布。输出未发布时 SHALL 保留旧输入恢复；新状态完整后 SHALL 无须旧输入存在。孤儿清理 SHALL 继续先验证全部有效引用，再尝试清理无引用内部文件。此保证 SHALL 限于既有进程退出模型。

#### Scenario: Exit before publication
- **WHEN** 进程在部分输出或完整输出尚未发布时退出
- **THEN** 重启使用完整旧输入集，忽略未引用输出，查询保持原结果

#### Scenario: Exit after publication
- **WHEN** 进程在替换记录完整后、旧输入删除前或部分删除后退出
- **THEN** 重启仅需新输出及当前 active/frozen WAL，旧文件不重新参与查询，可继续写入、刷盘和压缩
