## Why

项目需要可复用的 Java 21 构建环境及学习协作约定，避免一次性生成完整 LSM Tree，失去用户亲自实现关键步骤的机会。

## What Changes

- 初始化 Maven Wrapper、JUnit 5 和标准源码目录。
- 初始化 OpenSpec、Git 与编辑器约定。
- 明确分阶段路线、用户参与方式及规范驱动流程。

## Capabilities

### New Capabilities
- `project-environment`: Java 21 工程构建、版本管理和学习协作约定。

### Modified Capabilities

无。

## Impact

仅影响工程环境和项目文档，无存储引擎业务实现。

## Non-goals

不实现 MemTable、WAL、SSTable 或压缩，不提前确定键值模型与文件格式。
