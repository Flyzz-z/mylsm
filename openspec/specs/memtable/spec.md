# memtable Specification

## Purpose
提供可替换的单线程内存键值表，依据字节键内容保存每键最新状态，明确区分值、显式删除和当前表未找到，为后续有序遍历及磁盘层读取建立可靠的读写语义。
## Requirements
### Requirement: Latest value per key
内存表 MUST 按字节键内容识别同一个键，写入 MUST 覆盖此键之前的值或删除标记，且不影响其他键。

#### Scenario: Overwrite equal keys
- **WHEN** 通过两个内容相同的键对象依次写入 A 和 B
- **THEN** 查询该键得到 B

#### Scenario: Write after deletion
- **WHEN** 删除某个键后再次为它写入值
- **THEN** 查询得到新值而非删除标记

### Requirement: Three lookup states
查询 MUST 返回当前值、显式删除或未找到三种可区分的结果，不可将空字节值或删除标记转为未找到。查询 MUST 不修改内存表。

#### Scenario: Unknown key
- **WHEN** 查询尚未写入或删除的键
- **THEN** 得到未找到结果

#### Scenario: Empty key and value
- **WHEN** 以空字节键写入空字节值
- **THEN** 查询返回存在的空值，删除后查询返回删除标记

### Requirement: Retain deletion markers
删除 MUST 保存显式删除标记，包括之前不存在的键；重复删除 MUST 保持删除状态。

#### Scenario: Delete an absent key
- **WHEN** 删除当前表中不存在的键
- **THEN** 查询该键得到显式删除，而不是未找到

#### Scenario: Delete an existing key repeatedly
- **WHEN** 写入一个键后连续删除两次
- **THEN** 查询该键仍得到删除标记

### Requirement: Reject null arguments without mutation
所有操作 MUST 拒绝 null 键，写入 MUST 拒绝 null 值，并抛出 NullPointerException；失败操作 MUST 不改变原有记录或插入新记录。

#### Scenario: Invalid operations
- **WHEN** 使用 null 键调用写入、查询或删除，或使用 null 值调用写入
- **THEN** 抛出 NullPointerException，原有值及删除标记保持不变

### Requirement: Isolated in-memory state
每个内存表实例 MUST 独立保存状态，键和值 MUST 不受外部数组修改影响。此契约仅适用于单线程访问，不提供持久化保证。

#### Scenario: External array mutation
- **WHEN** 写入后修改构造键值所用数组或从查询值导出的数组
- **THEN** 原键仍可查询，值内容不变

#### Scenario: Independent instances
- **WHEN** 在一个内存表实例写入或删除键
- **THEN** 另一个实例的查询结果不受影响

### Requirement: Ordered complete snapshot
内存表 MUST 提供按字节键自然顺序升序排列的快照，每键恰好包含获取时的最新记录，包括删除标记与空值。获取快照 MUST 不修改表；空表 MUST 返回空列表。

#### Scenario: Unordered writes and deletes
- **WHEN** 乱序写入键值、覆盖相同内容的键并删除部分键后获取快照
- **THEN** 返回的键按无符号字节字典序排列，无重复键，且保留最新值和全部删除标记

#### Scenario: Empty table
- **WHEN** 从空表获取快照
- **THEN** 返回空列表，表仍为空

### Requirement: Stable snapshot contents
快照 MUST 独立于后续内存表修改。获取快照后执行新增、覆盖、删除或删除后的重写，MUST 不影响旧快照的成员、顺序和记录内容；新的快照 MUST 反映新的表状态。

#### Scenario: Mutate after obtaining a snapshot
- **WHEN** 获取快照后覆盖已有值、删除已有键、重写删除标记并新增键
- **THEN** 旧快照保持原状，新快照反映这些修改

### Requirement: Unmodifiable snapshot
调用方 MUST 无法添加、替换或移除快照列表元素；这些修改尝试 MUST 抛出 UnsupportedOperationException。通过记录导出字节后的外部修改 MUST 不影响快照及内存表。

#### Scenario: Attempt list and iterator mutations
- **WHEN** 尝试添加、替换、移除或清空非空快照，或通过迭代器删除记录
- **THEN** 修改被拒绝，快照和表中数据保持不变

#### Scenario: Mutate exported bytes
- **WHEN** 修改快照记录导出的键或值字节数组
- **THEN** 后续观察快照和查询内存表均得到原始内容

### Requirement: Valid snapshot records
快照记录 MUST 是不可变的键与记录状态配对，不能含有 null 键或 null 状态；使用 null 创建配对 MUST 抛出 NullPointerException。删除标记是合法状态。

#### Scenario: Null record components
- **WHEN** 使用 null 键或 null 记录状态构造快照记录
- **THEN** 抛出 NullPointerException
