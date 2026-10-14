## ADDED Requirements

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
