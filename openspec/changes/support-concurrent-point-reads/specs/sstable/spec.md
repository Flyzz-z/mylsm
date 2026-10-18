## ADDED Requirements

### Requirement: Coordinate concurrent point reads and exclusive operations

读取器 SHALL 支持并发点查，以各自独立的块缓冲和明确文件偏移读取，保持原有校验及三态语义。关闭 SHALL 等待已进入的点查结束后才释放资源。scan 与游标操作 SHALL 保持独占协调，游标存活时仍拒绝点查及其他遍历；游标仍由单一调用线程推进。文件错误 SHALL 使后续读取被拒绝，参数错误 SHALL 不使实例失效。

#### Scenario: Read two blocks concurrently
- **WHEN** 两个点查读取同一文件不同块
- **THEN** 读取可以重叠且各自返回正确结果，不共用可变读取位置或块缓冲

#### Scenario: Close waits for active lookups
- **WHEN** 点查暂停在底层读取且其他调用者关闭读取器
- **THEN** 关闭等待该查询结束，底层资源只释放一次，后续查询拒绝

#### Scenario: Cursor remains exclusive
- **WHEN** 游标仍存活或 scan 回调重入读操作
- **THEN** 保留已有冲突拒绝及显式关闭契约，不返回被其他操作改变的游标位置
