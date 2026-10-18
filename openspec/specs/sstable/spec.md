# sstable Specification

## Purpose

提供支持独立读 context 的不可变有序磁盘文件，保留字节键对应的值与删除状态，支持新文件构建、重新打开、点查及有序遍历，并对无效输入和可检测的文件损坏明确失败，为后续 LSM 刷盘与多文件读取建立独立基础。

## Requirements

### Requirement: Preserve sorted record states

系统 SHALL 按无符号字节字典序保存严格递增且唯一的键，完整保留二进制键、Value 与 Tombstone，允许空表、空键和空值；SHALL 拒绝 null、重复键及逆序输入，不自动排序或覆盖重复记录。单条编码记录 SHALL 限制为最大 16 MiB，包含 9 字节记录头。

#### Scenario: Binary and deletion states round trip
- **WHEN** 已排序输入包含空键、非文本字节键、空值、普通值和删除标记，并成功写入后重新打开
- **THEN** 点查和遍历保留输入字节与记录状态，空 Value 不等于 Tombstone

#### Scenario: Reject invalid ordered input
- **WHEN** 输入包含 null、相同键、下降的键或超限记录
- **THEN** 构建拒绝输入且不报告写入成功

#### Scenario: Empty table
- **WHEN** 使用空输入成功构建并打开文件
- **THEN** 任意合法键的点查返回缺失，遍历不产生记录

### Requirement: Build a new immutable file

系统 SHALL 仅创建新的目标文件，拒绝覆盖已有路径；只有文件内容完整写出、同步且资源关闭成功后才报告构建成功。构建失败 SHALL 释放资源并尝试清理本次创建的文件，保留主错误与清理错误；进程崩溃遗留文件不具有已发布语义，且不承诺新建目录项断电持久性。

#### Scenario: Preserve an existing target
- **WHEN** 构建目标路径已存在
- **THEN** 操作失败且原文件内容保持不变

#### Scenario: Reopen a completed file
- **WHEN** 构建成功且调用方随后以只读方式打开文件
- **THEN** 可读取全部输入记录，查询和遍历不修改文件

#### Scenario: Failed construction
- **WHEN** 创建后输入验证、写入、同步或关闭失败
- **THEN** 操作不报告成功，尝试释放资源并删除本次创建的文件；清理异常不掩盖原错误

### Requirement: Bound and validate the versioned file format

系统 SHALL 使用带版本识别、块长度边界与 CRC32C 的文件格式；数据块 SHALL 不切分记录，索引 SHALL 精确描述连续的数据块区间，且具有严格递增的块最大键。系统 SHALL 在按文件声明长度分配内存前检查长度上限、数量与文件边界，拒绝溢出、非法状态、尾随字节和不支持的版本。数据块载荷 SHALL 不超过 16 MiB + 4 字节，索引载荷 SHALL 不超过 64 MiB。

#### Scenario: Multi-block lookup boundaries
- **WHEN** 文件包含多个数据块以及一条超过普通分块目标但仍合法的记录
- **THEN** 记录完整保存在某个块中，每个块的首键和末键均可正确查询

#### Scenario: Reject invalid metadata
- **WHEN** 文件头、Footer 或索引出现不支持的版本、校验错误、越界偏移、非法长度、异常数量、非连续块区间或不递增索引键
- **THEN** 打开失败并释放资源，不修改文件

#### Scenario: Index capacity limit
- **WHEN** 构建所需索引超过规定载荷上限
- **THEN** 构建以 I/O 错误失败，不输出成功文件

### Requirement: Point lookup preserves three states

系统 SHALL 支持只读点查，分别返回值、删除标记或缺失；SHALL 根据索引定位候选块，仅在候选块完成完整性与内容验证后返回其查询结果。不存在候选块时 SHALL 返回缺失；不要求每次查询校验所有无关块。

#### Scenario: Present value and tombstone
- **WHEN** 查询文件中分别保存 Value 和 Tombstone 的键
- **THEN** 分别返回原值和删除标记，不将删除标记转换成缺失

#### Scenario: Missing keys around boundaries
- **WHEN** 查询小于最小键、大于最大键、块内间隙或两块之间间隙的键
- **THEN** 返回缺失且不误返回相邻记录

#### Scenario: Corrupt candidate block
- **WHEN** 查询所需块发生截断、校验失败、非法记录状态、键顺序错误或与索引键边界不符
- **THEN** 报告 I/O 错误，不返回缺失或部分解析结果

### Requirement: Scan in key order with bounded block buffering

系统 SHALL 按键顺序逐块遍历记录，包含删除标记，每条记录恰好产生一次；遍历 SHALL 无须先将全表记录汇集到内存。每个块 SHALL 在向调用方产生记录前完成该块的完整性、语义与键边界验证。

#### Scenario: Ordered scan across blocks
- **WHEN** 遍历含多个数据块的合法文件
- **THEN** 得到与原始有序输入相同的记录序列

#### Scenario: Corruption later in a scan
- **WHEN** 前面块合法而后续块损坏
- **THEN** 遍历报告 I/O 错误，坏块不产生记录，先前合法块已经产生的记录不回滚

#### Scenario: Callback failure
- **WHEN** 遍历回调抛出运行时异常
- **THEN** 该次遍历停止并传播异常，不回滚已有回调副作用；合法文件仍可供后续操作使用

### Requirement: Fail explicitly on file errors and close safely

系统 SHALL 区分缺失键与 I/O 或文件格式错误，完整 SSTable 的截断 SHALL 作为错误处理，不修复尾部、不跳过损坏块。打开时 SHALL 验证元信息，数据块按访问时验证；打开成功不代表所有未访问块均已校验。打开失败 SHALL 释放资源；已打开实例发生文件 I/O 或格式错误后 SHALL 拒绝继续查询和遍历，仅允许关闭。关闭 SHALL 可重复，关闭报错后也不得恢复使用。

#### Scenario: Lazy block validation
- **WHEN** 元信息合法但某个尚未读取的数据块校验错误
- **THEN** 打开可以成功，读取该块或完整遍历时报告错误，不承诺无关查询发现此错误

#### Scenario: Truncated completed file
- **WHEN** 已完成文件被截断后打开或读取
- **THEN** 当元信息或所需数据无法完整验证时报告错误，不截尾、不修改原文件

#### Scenario: Closed or failed reader
- **WHEN** 已关闭或发生文件错误的实例再次执行查询或遍历
- **THEN** 操作被拒绝；重复关闭不重复释放底层资源

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
