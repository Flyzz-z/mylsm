## Purpose

提供可替换的单线程内存键值表，依据字节键内容保存每键最新状态，明确区分值、显式删除和当前表未找到，为后续有序遍历及磁盘层读取建立可靠的读写语义。

## ADDED Requirements

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
