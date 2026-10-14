# MyLSM

使用 **Java 21** 分阶段实现 LSM Tree，由你指挥 AI 逐步实现核心算法，在理解设计、代码与取舍的过程中学习存储引擎。当前已完成环境初始化、不可变字节模型和基于 TreeMap 的 MemTable（put/get/delete），尚未实现有序遍历与磁盘存储。

## 环境与构建

- JDK 21（构建会检查主版本，不启用 preview 特性）
- Maven Wrapper 固定 Maven 3.9.16，无需另外安装 Maven
- OpenSpec CLI 1.7.0；其他机器可使用 `npm install -g @fission-ai/openspec@1.7.0` 安装
- Git

```sh
java -version
./mvnw --version
./mvnw clean verify
./mvnw test
openspec validate --all --strict --no-interactive
```

首次构建需要联网下载 Maven 和依赖。Windows 使用 `mvnw.cmd`。JUnit 5 已覆盖 `ByteKey` 的数组隔离、相等、排序和集合行为，以及 `Value` 的不可变性和 MemTable 的写入、查询三态、删除及边界行为；构建通过只代表当前已实现范围通过验证。

## 目录

```text
src/main/java/io/github/flyzz/mylsm/
  model/                             ByteKey、Entry、Value、Tombstone
  memtable/                          MemTable、TreeMapMemTable
src/test/java/io/github/flyzz/mylsm/  对应子包的 JUnit 测试
docs/roadmap.md                       学习路线与每阶段节奏
openspec/config.yaml                  项目背景与规范约定
openspec/specs/                       已实现能力的正式规范
openspec/changes/                     进行中的变更与归档
.codex/skills/                        OpenSpec 生成的 Codex 工作流
AGENTS.md                            学习优先的助手协作约定
```

## OpenSpec 工作流

每阶段先设计再实现，不直接跳到代码。可以在 Codex 中使用 `$openspec-explore` 讨论问题，使用 `$openspec-propose` 起草变更；新生成的技能可能需要重启会话才能发现。执行 apply 时仍须遵守 `AGENTS.md` 中用户参与核心实现的约定。

CLI 示例（准备开始下一步有序遍历时执行）：

```sh
git switch -c feat/memtable-iteration
openspec new change add-memtable-iteration
openspec status --change add-memtable-iteration
openspec instructions proposal --change add-memtable-iteration
# 按依赖顺序完善 proposal、specs、design、tasks，完成设计讨论后再实现
openspec validate add-memtable-iteration --strict --no-interactive
./mvnw clean verify
# 完成实现、验收与学习复盘后再归档
openspec archive add-memtable-iteration
```

通过 `openspec list` 查看进行中的变更，`openspec list --specs` 查看正式规范。

## Git 与学习路线

默认分支为 `main`，每阶段使用独立主题分支、小步提交。忽略构建产物、IDE 配置与 `data/` 运行数据；本轮不配置远端或推送。

见 [学习路线](docs/roadmap.md) 和 [协作约定](AGENTS.md)。已完成的基础模型见 [ByteKey 学习笔记](docs/byte-key.md) 和 [记录模型学习笔记](docs/entry-model.md)。内存表实现见 [MemTable 学习笔记](docs/memtable.md)。下一步讨论有序遍历的结果形式及修改期间的可见性。
