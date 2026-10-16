## Purpose

为目录级存储记录唯一有效的文件集和代际顺序，使日志轮换与后台 SSTable 发布具有可恢复的提交边界；通过版本、长度、校验及引用验证区分有效状态、残缺尾部和损坏，防止旧日志回收过早或旧值复活。

## ADDED Requirements

### Requirement: Record an ordered and bounded file set

元信息 SHALL 记录递增提交序号、唯一活跃 WAL、至多一个冻结 WAL 及其封存长度，以及从旧到新的 SSTable 列表；引用 SHALL 使用内部正整数编号，拒绝重复引用、非法状态和溢出。元信息 SHALL 采用有版本、长度边界和 CRC32C 的格式，单条状态载荷 SHALL 不超过 16 MiB，在分配前检查数量和长度，完整记录 SHALL 精确消耗内容。元信息 SHALL 校验相邻状态符合初始化、日志轮换或单个冻结代发布的合法转换，不能任意丢弃有效引用。

#### Scenario: Reject invalid complete states
- **WHEN** 完整元信息出现未知版本、CRC 错误、非法编号、重复引用、异常数量、非连续提交序号或非法状态转换
- **THEN** 打开失败，不跳过记录或退回旧文件集掩盖错误

#### Scenario: Metadata capacity limit
- **WHEN** 新增文件会使状态载荷超过上限
- **THEN** 提交在修改元信息前失败，保持旧恢复来源，不回收仍引用的 WAL

### Requirement: Initialize only an unambiguous new database

新数据库 SHALL 仅在已有空目录初始化；先准备并同步初始 WAL，再提交初始有效文件集，成功后才提供存储实例。缺失元信息但目录非空、元信息头残缺或没有首个完整有效状态时 SHALL 拒绝自动初始化，不覆盖遗留文件。

#### Scenario: Initialize and reopen
- **WHEN** 在已有空目录创建数据库后关闭并重新打开
- **THEN** 使用相同初始文件集，可正常读写且不重复初始化

#### Scenario: Interrupted initialization
- **WHEN** 初始化中断留下 WAL 或不完整元信息而无完整初始状态
- **THEN** 下次打开明确失败并保留文件，不默认为空数据库

### Requirement: Publish both logs before accepting writes in a new generation

轮换 SHALL 先准备并同步新的 WAL，再提交同时引用旧 frozen WAL 和新 active WAL 的状态；提交成功前 SHALL 不向新 WAL 接纳用户写入。未发布冻结数据的日志 SHALL 保留引用，且在已有冻结代时 SHALL 不再轮换出第二个冻结代。

#### Scenario: Crash before rotation is recorded
- **WHEN** 新空 WAL 已创建而轮换状态尚未完整记录时进程退出
- **THEN** 恢复旧状态及旧 active WAL，不将未引用的新 WAL 当作有效数据

#### Scenario: Crash after rotation is recorded
- **WHEN** 轮换状态已完整记录，进程在后续 SSTable 发布前退出
- **THEN** 重启保留两代 WAL 的恢复来源，不遗漏新 active WAL 中的成功写入

### Requirement: Replace a frozen log reference only with a completed table

后台输出 SHALL 完整构建、同步并成功打开后，才提交包含该 SSTable 且移除对应 frozen WAL 引用的新状态；该提交 SHALL 保留当前活跃 WAL 及此前所有有效 SSTable 的顺序。只有成功提交后 SHALL 移除冻结内存数据并尝试删除旧 WAL。提交失败结果不确定时 SHALL 停止服务并保留可能仍需的文件。

#### Scenario: Unpublished complete table
- **WHEN** SSTable 已完整写出而发布状态尚未完整记录时进程退出
- **THEN** 重启仍从 frozen WAL 恢复，忽略未引用 SSTable

#### Scenario: Published table with leftover log
- **WHEN** 发布状态完整，但进程在旧 WAL 删除前退出
- **THEN** 重启使用新 SSTable 与当前 active WAL，不重放已失效旧 WAL

#### Scenario: Commit reports a sync error
- **WHEN** 状态记录完整写出但同步报错
- **THEN** 当前实例停止服务且不删除可能有效文件，重启依据实际完整有效记录恢复，不承诺此次提交必定回滚

### Requirement: Recover the last complete valid state and validate references

恢复 SHALL 校验完整元信息记录并采用最后一个有效状态；仅在已有有效状态后，对不足一个帧前缀或合法声明长度下的残缺末尾载荷进行截尾并同步，完整记录损坏 SHALL 失败。所有引用文件 SHALL 已存在且可按规定格式打开；缺失引用 WAL SHALL 不被自动创建补齐。冻结 WAL SHALL 精确符合封存长度且完整有效，不采用活跃 WAL 的残尾修复；活跃 WAL SHALL 沿用已有不完整尾部修复规则。打开失败 SHALL 关闭资源且不返回部分可用实例。

#### Scenario: Truncated final metadata frame
- **WHEN** 元信息具有有效状态前缀且最后一条追加记录在任意字节位置残缺
- **THEN** 恢复最后完整状态，修复尾部并同步后才能继续提交，不越过残缺记录查找其他内容

#### Scenario: Missing referenced file
- **WHEN** 最新有效状态引用的 WAL 或 SSTable 缺失
- **THEN** 打开失败，不创建空日志或省略文件使数据静默丢失

#### Scenario: Different log tail contracts
- **WHEN** 活跃 WAL 有合法不完整尾部，或冻结 WAL 被截断
- **THEN** 前者按已有规则修复有效前缀，后者明确失败，不丢弃已冻结的数据

### Requirement: Ignore unreferenced files without confusing publication

数据库 SHALL 只根据最新有效元信息选择数据来源，不依据文件存在、文件名大小或修改时间决定发布和版本新旧。新编号 SHALL 避开目录中仍存在的保留命名空间文件；仅在引用验证成功后才允许清理确认无引用的内部文件，未知文件 SHALL 不被删除。恢复保证 SHALL 限于约定的文件同步和进程退出模型，不宣称新目录项断电持久性。

#### Scenario: Orphan files and numbering
- **WHEN** 目录存在未发布 SSTable、废弃 WAL 或删除失败的高编号内部文件
- **THEN** 查询和重放忽略这些文件，新建不覆盖它们，清理失败不改变有效文件集

#### Scenario: Unknown file preservation
- **WHEN** 有效数据库目录内存在不属于保留命名空间的用户文件
- **THEN** 打开和内部孤儿清理不修改或删除该文件
