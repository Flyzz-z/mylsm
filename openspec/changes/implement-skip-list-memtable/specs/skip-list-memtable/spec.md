## Purpose

提供手写跳表支撑的可替换内存表，使调用方可在既有字节键及记录模型上使用相同的写入、删除、查询和稳定有序快照行为，同时通过确定性的测试配置验证不同索引高度不改变数据语义。

## ADDED Requirements

### Requirement: Compatible skip list memory table

跳表内存表 MUST 满足现有 memtable 规范，包括按键内容覆盖、三态查询、删除标记保留、参数拒绝和实例隔离。只有提供完整契约后才能作为 MemTable 实现对外使用。

#### Scenario: Replace memory table implementation
- **WHEN** 调用方通过跳表实现依次写入、覆盖、删除并重新写入相同内容的键
- **THEN** 读取始终返回该键的最新状态，行为与现有内存表一致

#### Scenario: Preserve ordered stable snapshots
- **WHEN** 调用方取得包含普通值与删除标记的快照后继续修改表
- **THEN** 快照按无符号字节字典序排列、每键只有一条最新记录、不可修改且不受后续写入影响

### Requirement: Height independent data semantics

跳表内存表 MUST 在合法节点高度配置下保持相同读写结果，包括全部节点单层、存在最高允许高度节点及混合高度的情况。测试配置 MUST 能确定性地产生这些形状；非法配置失败不得改变已有记录。

#### Scenario: Different valid heights
- **WHEN** 对不同合法高度配置的表执行相同的乱序写入、覆盖和查询
- **THEN** 所有表返回相同结果，未写入的键均为未找到

#### Scenario: Invalid generated height
- **WHEN** 为新键生成的高度超出支持范围
- **THEN** 抛出 IllegalArgumentException，已有记录保持不变且新键仍未找到
