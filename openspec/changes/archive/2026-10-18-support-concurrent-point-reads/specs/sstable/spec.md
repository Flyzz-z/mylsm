## MODIFIED Requirements

### Requirement: Pull records through a bounded ordered cursor

读取器 SHALL 提供独立、可暂停、逐块推进的有序游标，保留 Value 与 Tombstone 并在块完整验证后逐条产生记录，不要求复制全表记录；创建游标 SHALL 不读取数据块，空表与正常耗尽 SHALL 可与 I/O 错误区分。推进成功才具有当前记录，推进前或耗尽后 SHALL 拒绝读取当前位置，耗尽后再次推进 SHALL 继续报告正常结束。同一读取器 SHALL 允许多个独立游标、点查和扫描共享文件及索引，每个游标由单一调用线程使用并维护自己的缓冲和位置。提前关闭游标 SHALL 释放其局部缓冲，不关闭读取器或影响其他游标；耗尽仍须显式关闭以释放局部状态。读取器关闭或文件错误失效后，已有游标 SHALL 拒绝继续读取或返回旧的当前记录，关闭 SHALL 可重复。

#### Scenario: Interleave independent inputs
- **WHEN** 调用方轮流推进同一或不同读取器的独立游标，暂停在块中或块边界
- **THEN** 各游标按原始键序恰好产生一次全部记录，互不改变位置，不读取尚不需要的后续数据块

#### Scenario: Release a partially consumed cursor
- **WHEN** 调用方关闭一个仅读取部分输入的游标
- **THEN** 只释放本次读取状态，其他游标、点查和扫描继续可用，重复关闭安全

#### Scenario: Reject conflicting reader operations
- **WHEN** 读取器已经关闭或发生文件错误后，已有游标或新的读取继续请求数据
- **THEN** 操作被拒绝，不返回缓存的旧当前位置；关闭游标仍可释放其局部缓冲

#### Scenario: Corruption during cursor advancement
- **WHEN** 前面块合法而某个游标推进到损坏数据块
- **THEN** 报告 I/O 错误，坏块不产生记录，读取器及其全部 context 失效，不返回正常耗尽

#### Scenario: Position before first advance and after exhaustion
- **WHEN** 游标刚创建或推进已报告耗尽
- **THEN** 读取当前位置被拒绝，正常耗尽可重复查询，游标显式关闭只影响自身

## ADDED Requirements

### Requirement: Coordinate independent read contexts and resource lifetime

读取器 SHALL 共享不可变索引及文件资源，多个点查、游标和扫描 SHALL 使用各自独立的块缓冲与明确文件偏移读取，保持原有校验及三态语义。关闭 SHALL 等待正在执行的读取结束后才释放资源，并使暂停的 context 后续操作拒绝；不要求等待游标整个生命周期。文件错误 SHALL 使后续读取被拒绝，参数或用户回调错误 SHALL 不使健康实例失效。扫描 SHALL 使用独立读取状态，回调 SHALL 在资源生命周期锁外执行。

#### Scenario: Read two blocks concurrently
- **WHEN** 两个独立 context 读取同一文件不同块
- **THEN** 读取可以重叠且各自返回正确结果，不共用可变读取位置或块缓冲

#### Scenario: Close waits for active lookups
- **WHEN** 点查或游标推进暂停在底层读取且其他调用者关闭读取器
- **THEN** 关闭等待当前读取结束，底层资源只释放一次，后续全部 context 拒绝操作

#### Scenario: Callback starts an independent read
- **WHEN** 一个扫描暂停在回调中，同时进行点查、其他游标或扫描
- **THEN** 独立操作可以完成，不改变原扫描进度；回调关闭读取器时不发生锁升级死锁，原扫描后续操作明确失败
