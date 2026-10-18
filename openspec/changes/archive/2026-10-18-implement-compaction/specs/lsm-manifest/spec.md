## MODIFIED Requirements

### Requirement: Record an ordered and bounded file set

元信息 SHALL 记录递增提交序号、唯一活跃 WAL、至多一个冻结 WAL 及其封存长度，以及从旧到新的 SSTable 列表；引用 SHALL 使用内部正整数编号，拒绝重复引用、非法状态和溢出。元信息 SHALL 采用有版本、长度边界和 CRC32C 的格式，单条状态载荷 SHALL 不超过 16 MiB，在分配前检查数量和长度，完整记录 SHALL 精确消耗内容。元信息 SHALL 校验相邻状态符合初始化、日志轮换、单个冻结代发布或受限全量压缩替换的合法转换，不能任意丢弃有效引用。

#### Scenario: Reject invalid complete states
- **WHEN** 完整元信息出现未知版本、CRC 错误、非法编号、重复引用、异常数量、非连续提交序号或非法状态转换
- **THEN** 打开失败，不跳过记录或退回旧文件集掩盖错误

#### Scenario: Metadata capacity limit
- **WHEN** 新增文件会使状态载荷超过上限
- **THEN** 提交在修改元信息前失败，保持旧恢复来源，不回收仍引用的 WAL

## ADDED Requirements

### Requirement: Accept only a constrained full compaction replacement

全量压缩替换 SHALL 要求旧状态至少引用两张 SSTable，新状态保持 active WAL、frozen WAL 及其封存长度完全不变，SSTable 清单恰好为一个新输出编号；输出编号 SHALL 严格大于全部输入 SSTable 编号且不与当前 WAL 重合，提交序号恰好加一且不得溢出。系统 SHALL 拒绝部分替换、空清单、历史引用编号复用或同时改变 WAL 的压缩转换，并继续验证原有轮换与发布转换。压缩期间发生 WAL 轮换时，替换 SHALL 基于最新有效日志状态发布，不回退旧 WAL 状态。

#### Scenario: Valid full replacement and later flush
- **WHEN** 多表状态替换为一个新输出，随后进行新一代 WAL 轮换与 flush 发布
- **THEN** 所有转换可恢复，新 flush 表位于压缩输出之后并具有更新的记录优先级

#### Scenario: Reject arbitrary reference removal
- **WHEN** 后继状态仅删除部分输入、清空输入、复用历史引用编号，或在替换 SSTable 的同时改变 active/frozen WAL 状态
- **THEN** 拒绝提交或恢复，不将其当作合法压缩

#### Scenario: Rotation while an output is being constructed
- **WHEN** 压缩已分配输出编号后，前台轮换为编号更大的新 active WAL，旧日志变为 frozen
- **THEN** 压缩可以发布唯一新输出，完整保留最新 active/frozen 及封存长度；恢复后继续刷出 frozen，查询不丢失期间成功写入
