## MODIFIED Requirements

### Requirement: Learning-first staged development
项目 MUST 记录阶段路线与协作约定，由用户指挥 AI 逐步实现核心内容；用户主导节奏并参与理解、设计和关键决策，AI 负责具体代码、测试、讲解与验证。

#### Scenario: Begin a core algorithm task
- **WHEN** 开始 MemTable、WAL、SSTable 或压缩等核心步骤
- **THEN** 先讨论设计、不变量和验收场景，再由 AI 按用户指挥的小步骤实现，不要求用户手写代码或另行授权代写

#### Scenario: Review a completed step
- **WHEN** AI 完成一个关键实现步骤
- **THEN** 解释代码原理、设计取舍与验证结果，留出用户提问及调整方向的空间，不自动跨越到后续阶段
