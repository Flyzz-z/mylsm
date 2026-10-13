# project-environment Specification

## Purpose
TBD - created by archiving change initialize-project-environment. Update Purpose after archive.
## Requirements
### Requirement: Java 21 build environment
项目 MUST 提供以 Java 21 为目标的 Maven 工程，使用 Maven Wrapper 固定 Maven 版本，并配置 JUnit 5 测试依赖。

#### Scenario: Build the project on Java 21
- **WHEN** 开发者在 JDK 21 与可获取依赖的环境运行 `./mvnw clean verify`
- **THEN** 工程构建成功，具备后续编译与测试入口

#### Scenario: Reject a different JDK major version
- **WHEN** 使用非 Java 21 主版本执行 Maven validate 或后续构建阶段
- **THEN** Enforcer 阻止构建并提示 Java 版本要求

### Requirement: Learning-first staged development
项目 MUST 记录阶段路线与协作约定，由用户指挥 AI 逐步实现核心内容；用户主导节奏并参与理解、设计和关键决策，AI 负责具体代码、测试、讲解与验证。

#### Scenario: Begin a core algorithm task
- **WHEN** 开始 MemTable、WAL、SSTable 或压缩等核心步骤
- **THEN** 先讨论设计、不变量和验收场景，再由 AI 按用户指挥的小步骤实现，不要求用户手写代码或另行授权代写

#### Scenario: Review a completed step
- **WHEN** AI 完成一个关键实现步骤
- **THEN** 解释代码原理、设计取舍与验证结果，留出用户提问及调整方向的空间，不自动跨越到后续阶段

### Requirement: Spec-driven and versioned workflow
项目 MUST 使用 Git 管理源码，并提供 OpenSpec 配置与阶段变更流程。

#### Scenario: Plan a new stage
- **WHEN** 准备推进下一阶段
- **THEN** 创建独立 OpenSpec change，先完善规范与设计，再按责任拆分 tasks

#### Scenario: Keep generated data out of version control
- **WHEN** 构建生成 target 目录或本地运行生成 data 目录
- **THEN** Git 忽略这些目录并保留源码、规范及 Wrapper 文件
