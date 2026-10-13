## ADDED Requirements

### Requirement: Java 21 build environment
项目 MUST 提供以 Java 21 为目标的 Maven 工程，使用 Maven Wrapper 固定 Maven 版本，并配置 JUnit 5 测试依赖。

#### Scenario: Build the project on Java 21
- **WHEN** 开发者在 JDK 21 与可获取依赖的环境运行 `./mvnw clean verify`
- **THEN** 工程构建成功，具备后续编译与测试入口

#### Scenario: Reject a different JDK major version
- **WHEN** 使用非 Java 21 主版本执行 Maven validate 或后续构建阶段
- **THEN** Enforcer 阻止构建并提示 Java 版本要求

### Requirement: Learning-first staged development
项目 MUST 记录阶段路线与协作约定，核心算法默认由用户实现，助手承担讲解、脚手架、评审与验证。

#### Scenario: Begin a core algorithm task
- **WHEN** 开始 MemTable、WAL、SSTable 或压缩等核心步骤
- **THEN** 先讨论设计、不变量和验收场景，在用户未要求代写时保留核心实现供用户完成

### Requirement: Spec-driven and versioned workflow
项目 MUST 使用 Git 管理源码，并提供 OpenSpec 配置与阶段变更流程。

#### Scenario: Plan a new stage
- **WHEN** 准备推进下一阶段
- **THEN** 创建独立 OpenSpec change，先完善规范与设计，再按责任拆分 tasks

#### Scenario: Keep generated data out of version control
- **WHEN** 构建生成 target 目录或本地运行生成 data 目录
- **THEN** Git 忽略这些目录并保留源码、规范及 Wrapper 文件
