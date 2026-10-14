# byte-key Specification

## Purpose
为有序键值存储提供稳定的二进制键语义，明确键按内容相等、按无符号字节字典序排序，且不受外部数组修改影响，使未来不同内存索引与磁盘索引能够共享同一比较规则。
## Requirements
### Requirement: Immutable key contents
字节键 MUST 在创建时保留输入字节的独立内容，并保证向调用方导出字节后，调用方的修改不会改变键的内容、排序或哈希。

#### Scenario: Mutate the original array
- **WHEN** 从数组创建键后修改原数组
- **THEN** 键仍表示创建时的原始字节内容

#### Scenario: Mutate exported bytes
- **WHEN** 调用方修改从键导出的字节数组
- **THEN** 键内容及后续导出的内容保持不变

### Requirement: Content equality
字节键 MUST 根据字节序列内容判断相等，相等的键 MUST 具有相同哈希值，且比较结果为零当且仅当键相等。

#### Scenario: Equal contents from distinct arrays
- **WHEN** 两个键来自不同数组但内容相同
- **THEN** 两键相等、哈希相同且比较结果为零

### Requirement: Unsigned lexicographic ordering
字节键 MUST 按从左到右首个不同字节的无符号值（0 至 255）排序；共同前缀相同时短键在前。此规则 MUST 构成一致的全序。

#### Scenario: Cross the signed byte boundary
- **WHEN** 比较十六进制字节序列 [7F] 与 [80]
- **THEN** [7F] 小于 [80]

#### Scenario: Compare a shared prefix
- **WHEN** 比较十六进制字节序列 [01] 与 [01, 00]
- **THEN** [01] 小于 [01, 00]

#### Scenario: Compare the first different byte
- **WHEN** 比较十六进制字节序列 [01, FF] 与 [02]
- **THEN** [01, FF] 小于 [02]，长度不优先于首个不同字节

### Requirement: Empty and null inputs
字节键 MUST 支持空字节序列作为合法键，并拒绝 null 输入；与 null 比较 MUST 抛出 NullPointerException。

#### Scenario: Empty key
- **WHEN** 创建空字节序列的键并与任意非空键比较
- **THEN** 创建成功且空键在前

#### Scenario: Null input
- **WHEN** 尝试从 null 创建键
- **THEN** 抛出 NullPointerException
