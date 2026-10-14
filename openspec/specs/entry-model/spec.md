# entry-model Specification

## Purpose
为后续可替换的内存表提供不可变记录模型，明确区分携带字节内容的值和表示删除的标记，确保空值与删除不混淆，且外部数组修改不会改变已创建记录的内容和身份。
## Requirements
### Requirement: Closed record variants
记录 MUST 仅表示字节值或删除标记，不将未找到作为可存储记录。调用方 MUST 能根据记录类型区分这两种状态。

#### Scenario: Distinguish an empty value from deletion
- **WHEN** 调用方分别创建空字节值和取得删除标记
- **THEN** 两者是不同的记录类型，且互不相等

### Requirement: Immutable byte values
字节值 MUST 独立持有创建时的字节内容；修改输入或导出的数组 MUST 不影响记录内容、相等结果或哈希值。

#### Scenario: Change input bytes
- **WHEN** 创建记录后修改输入数组
- **THEN** 记录仍保留创建时的全部字节

#### Scenario: Change exported bytes
- **WHEN** 修改从记录导出的数组
- **THEN** 后续读取记录得到的内容不变

### Requirement: Value content equality
两个字节值 MUST 按完整字节内容判断相等；相等的值 MUST 具有相同哈希值。字节值 MUST 不等于其他类型的对象。

#### Scenario: Equal content from distinct arrays
- **WHEN** 两个字节值由不同数组构建，数组内容一致
- **THEN** 两值相等且哈希相同

#### Scenario: Different content or length
- **WHEN** 两个值内容不同，或其中一个是另一个的严格前缀
- **THEN** 两值不相等

### Requirement: Empty value and null input
字节值 MUST 允许长度为零的数组，MUST 在收到 null 创建参数时抛出 NullPointerException。

#### Scenario: Empty bytes
- **WHEN** 从空数组创建值
- **THEN** 创建成功且导出长度为零的数组

#### Scenario: Null bytes
- **WHEN** 从 null 创建值
- **THEN** 抛出 NullPointerException

### Requirement: Shared deletion marker
删除标记 MUST 是无字节载荷的共享单例，不能被修改为字节值。

#### Scenario: Obtain deletion markers repeatedly
- **WHEN** 多次取得删除标记
- **THEN** 得到同一个标记对象，表示删除而非记录缺失
