## Context

空目录起步，本机已有 JDK 21.0.11、Maven 3.9.16、OpenSpec 1.7.0。用户要求分阶段学习，并深度参与核心实现。

## Goals / Non-Goals

**Goals:** 建立可验证的构建入口、可追踪的变更流程和学习优先的协作约定。

**Non-Goals:** 本阶段不定义或实现任何 LSM 数据结构与存储行为。

## Decisions

- 使用 Maven 单模块与 Wrapper 固定 3.9.16：足以覆盖当前规模，减少学习存储原理之外的工具复杂度；暂不采用 Gradle 或多模块。
- 使用 release 21 和 Enforcer 校验 JDK 21：保证本地编译语言与运行环境一致。
- 使用 JUnit 5：为后续边界、持久性与恢复测试准备依赖，本阶段不添加无业务意义的测试。
- OpenSpec 采用 spec-driven schema：每阶段先 proposal/specs/design，再拆任务与实现；环境初始化作为首个可归档变更。
- AGENTS.md 和 OpenSpec context 同时记录用户参与约定，核心算法默认由用户实现；下一阶段由用户共同确定键值语义和 API。
- Git 使用 main 和阶段主题分支；提交身份只配置在本仓库。

## Risks / Trade-offs

- 首次 Wrapper 及依赖下载需要网络；下载后可复用本机缓存。
- 无业务实现时产物仅含包说明与构建元数据、没有业务测试属于预期；环境构建成功不能证明 LSM 正确性。
- 路线图会随理解加深调整；正式 specs 仅描述已经交付的能力。

## Migration Plan

从空仓库初始化，不涉及历史数据迁移。后续以小步提交和独立变更推进。

## Open Questions

键值类型、持久性保证和文件格式留到对应阶段共同设计。
