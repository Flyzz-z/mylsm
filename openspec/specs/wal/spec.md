# wal Specification

## Purpose

通过持久化写入和删除的操作历史，在单进程单线程使用条件下重建内存键值状态，并明确同步成功、失败结果不确定、日志截尾及可检测损坏的处理边界。

## Requirements

### Requirement: Versioned bounded binary records
WAL MUST 采用有格式标识和版本号的二进制文件，记录 PUT 与 DELETE 并保留任意字节键值、空键及空值。记录 MUST 带长度与 CRC32C 校验；版本 1 的单条载荷 MUST 限制为最多 16 MiB（含操作类型及键值长度字段），读取时 MUST 在按声明长度分配载荷内存前检查上限。

#### Scenario: Binary operation round trip
- **WHEN** 编码再解码包含零字节、高位字节、空键、空 PUT 值和 DELETE 的合法记录
- **THEN** 键值字节与操作类型保持一致，空值不变成删除

#### Scenario: Invalid record structure
- **WHEN** 解码出现负长度、超限长度、长度不匹配、未知操作或带值载荷的 DELETE
- **THEN** 拒绝记录，不将其作为有效操作

#### Scenario: Corrupted complete record
- **WHEN** 一条完整记录的校验和与长度及载荷计算结果不一致
- **THEN** 报告损坏，不返回已解码操作

### Requirement: Initialize or validate file header
打开不存在或零字节日志时，系统 MUST 写入并同步合法文件头。已有非空文件的文件头 MUST 完整且具有支持的格式标识和版本；校验失败 MUST 拒绝打开且不修改文件。父目录 MUST 已存在。

#### Scenario: Initialize empty log
- **WHEN** 在已有父目录内打开不存在或零字节日志
- **THEN** 同步文件头并返回空的可用存储，后续可追加并重新打开

#### Scenario: Reject unsupported or damaged header
- **WHEN** 打开具有不完整文件头、错误格式标识或未知版本的非空日志
- **THEN** 打开失败，原始文件内容保持不变

### Requirement: Validate before logging
写入和删除 MUST 拒绝 null 键，写入 MUST 拒绝 null 值，并抛出 NullPointerException。超过单条载荷上限的操作 MUST 抛出 IllegalArgumentException。这些参数失败 MUST 在追加前发生，不修改日志或内存，不使原本可用实例失效。

#### Scenario: Invalid arguments leave store usable
- **WHEN** 对可用存储提交 null 参数或超限操作
- **THEN** 得到相应参数异常，文件与查询结果保持不变，之后合法操作仍可成功

### Requirement: Sync before applying successful writes
系统 MUST 在完整追加一条操作记录并成功执行文件同步之后才更新内存状态并返回写入成功。成功写入 MUST 无需等待正常关闭即可从同一可访问日志恢复；此承诺依赖平台正确实现文件同步，不包含新建文件父目录项的断电持久性。

#### Scenario: Successful operation order
- **WHEN** 执行一次合法 PUT 或 DELETE
- **THEN** 完整追加先于同步，同步成功先于内存更新及返回成功

#### Scenario: Process exits without closing
- **WHEN** 独立进程成功完成写入和删除后，未正常关闭存储便退出
- **THEN** 另一个进程打开同一日志可恢复这些操作的最终状态

### Requirement: Recover operations in file order
系统 MUST 按文件顺序将每条完整有效记录重放到新的内存表，保留每键最后一次操作及删除标记。重放 MUST 不重复追加 WAL；查询 MUST 保留值、删除与未找到三种状态。打开失败 MUST 不返回部分恢复的可用实例。

#### Scenario: Overwrite and delete replay
- **WHEN** 日志依次包含 PUT(k, A)、DELETE(k)、PUT(k, B) 以及 DELETE(other)
- **THEN** 打开后 k 的值为 B，other 为删除标记，未出现的键为未找到，日志不因重放增长

### Requirement: Repair incomplete final record before append
存在合法文件头与有效记录前缀时，若最后记录前缀不足 8 字节，或其完整前缀中的合法载荷长度超过剩余文件字节，系统 MUST 将其视为不完整尾部，恢复有效前缀并截断到最后有效偏移，成功同步截断后才允许继续追加。此规则 MUST 不被解释为可区分所有尾部损坏与真实截断。

#### Scenario: Truncated tail at each byte boundary
- **WHEN** 在合法记录序列最后一条记录内任意位置截断文件并重新打开
- **THEN** 仅恢复此前完整记录，截去残留尾部；若截断恰在记录边界则无需修复

#### Scenario: Append after repairing tail
- **WHEN** 成功修复不完整尾部后追加一条新操作，再重新打开
- **THEN** 恢复原有效前缀及新操作，新操作不会被旧尾部遮蔽

### Requirement: Fail on detectable corruption and recovery I/O errors
完整记录前缀的非法载荷长度、完整记录的校验失败或非法内容 MUST 导致打开失败，并报告记录偏移；系统 MUST 不跳过、搜索下一条或自动截断这些错误。底层读取、截断和同步失败 MUST 作为 I/O 异常传播，不得作为正常文件结束处理。只读扫描遇到可检测损坏时 MUST 不修改文件。

#### Scenario: Bad complete record at middle or end
- **WHEN** 中间或末尾完整记录出现校验失败、非法操作或长度结构错误
- **THEN** 打开失败并指出偏移，不修改日志或暴露部分恢复状态

#### Scenario: Recovery I/O failure
- **WHEN** 日志读取或尾部修复的截断、同步发生 I/O 异常
- **THEN** 打开失败并关闭持有的文件资源，不返回可写实例

### Requirement: Stop using instance after write I/O failure
追加或同步发生 I/O 异常时，当前操作 MUST 不更新内存、不报告成功，实例 MUST 进入失败状态并拒绝后续读写，只允许关闭。系统 MUST 明确失败操作结果不确定：若日志中留下完整有效记录，恢复时仍重放它，不承诺失败操作必定回滚。

#### Scenario: Append or sync failure
- **WHEN** 追加发生短写之后报错，或完整追加后的同步报错
- **THEN** 当前内存保持原状，调用抛出 I/O 异常，后续读写抛出状态异常；重新打开依据实际有效日志恢复

#### Scenario: Failed operation may recover
- **WHEN** 一次操作完整写入日志但同步报错，随后关闭并重新打开时该记录仍完整有效
- **THEN** 该操作被重放，即使先前调用未报告成功

### Requirement: Close lifecycle and exclusive single-thread scope
存储 MUST 支持关闭并允许重复关闭，关闭后 MUST 拒绝读写；关闭失败也 MUST 不允许恢复使用同一实例。接口使用范围 MUST 明确为单线程、单实例独占日志，不提供并发或跨进程安全保证。

#### Scenario: Repeated close and use after close
- **WHEN** 关闭一个存储，再次关闭并尝试读写
- **THEN** 重复关闭安全，读写得到状态异常

#### Scenario: Close reports I/O failure
- **WHEN** 关闭文件资源发生 I/O 异常
- **THEN** 异常向调用方传播，该实例仍不允许读写
