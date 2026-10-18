## ADDED Requirements

### Requirement: Pull records through a bounded ordered cursor

读取器 SHALL 提供可暂停、逐块推进的有序游标，保留 Value 与 Tombstone 并在块完整验证后逐条产生记录，不要求复制全表记录；创建游标 SHALL 不读取数据块，空表与正常耗尽 SHALL 可与 I/O 错误区分。推进成功才具有当前记录，推进前或耗尽后 SHALL 拒绝读取当前位置，耗尽后再次推进 SHALL 继续报告正常结束。一个读取器 SHALL 同时至多存在一个活跃游标，持有期间拒绝其他读操作；耗尽仍 SHALL 持有占用直到显式关闭。提前关闭游标 SHALL 释放块缓冲和占用且不关闭读取器。读取器关闭或文件错误失效后，已有游标 SHALL 拒绝继续读取或返回旧的当前记录，关闭 SHALL 可重复。

#### Scenario: Interleave independent inputs
- **WHEN** 调用方轮流推进不同读取器的游标，暂停在块中或块边界
- **THEN** 各输入按原始键序恰好产生一次全部记录，不读取尚不需要的后续数据块

#### Scenario: Release a partially consumed cursor
- **WHEN** 游标仅读取部分输入后关闭
- **THEN** 读取器可重新点查或遍历，重新遍历不受前次位置影响，重复关闭安全

#### Scenario: Reject conflicting reader operations
- **WHEN** 同一读取器的游标仍活跃时请求点查、回调遍历或另一游标
- **THEN** 拒绝冲突操作且不破坏当前游标；读取器关闭后游标不能继续

#### Scenario: Corruption during cursor advancement
- **WHEN** 前面块合法而游标推进到损坏数据块
- **THEN** 报告 I/O 错误，坏块不产生记录，读取器失效，不返回正常耗尽

#### Scenario: Position before first advance and after exhaustion
- **WHEN** 游标刚创建或推进已报告耗尽
- **THEN** 读取当前位置被拒绝，正常耗尽可重复查询，显式关闭后读取器可以重新执行读操作
