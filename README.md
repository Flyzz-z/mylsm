# MyLSM

使用 **Java 21** 分阶段实现 LSM Tree，在亲自实现关键算法的过程中学习存储引擎。当前仅完成环境初始化，尚无 LSM 功能实现。

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

首次构建需要联网下载 Maven 和依赖。Windows 使用 `mvnw.cmd`。JUnit 5 已配置，当前没有业务测试；构建通过只代表工程环境可用。包目录当前只有说明文件，产物尚不包含存储引擎功能。

## 目录

```text
src/main/java/io/github/flyzz/mylsm/  逐阶段添加生产代码
src/test/java/io/github/flyzz/mylsm/  对应的 JUnit 测试
docs/roadmap.md                       学习路线与每阶段节奏
openspec/config.yaml                  项目背景与规范约定
openspec/specs/                       已实现能力的正式规范
openspec/changes/                     进行中的变更与归档
.codex/skills/                        OpenSpec 生成的 Codex 工作流
AGENTS.md                            学习优先的助手协作约定
```

## OpenSpec 工作流

每阶段先设计再实现，不直接跳到代码。可以在 Codex 中使用 `$openspec-explore` 讨论问题，使用 `$openspec-propose` 起草变更；新生成的技能可能需要重启会话才能发现。执行 apply 时仍须遵守 `AGENTS.md` 中用户参与核心实现的约定。

CLI 示例（准备进入阶段 1 时执行）：

```sh
git switch -c feat/memtable
openspec new change implement-memtable
openspec status --change implement-memtable
openspec instructions proposal --change implement-memtable
# 按依赖顺序完善 proposal、specs、design、tasks，完成设计讨论后再实现
openspec validate implement-memtable --strict --no-interactive
./mvnw clean verify
# 完成实现、验收与学习复盘后再归档
openspec archive implement-memtable
```

通过 `openspec list` 查看进行中的变更，`openspec list --specs` 查看正式规范。

## Git 与学习路线

默认分支为 `main`，每阶段使用独立主题分支、小步提交。忽略构建产物、IDE 配置与 `data/` 运行数据；本轮不配置远端或推送。

见 [学习路线](docs/roadmap.md) 和 [协作约定](AGENTS.md)。下一步共同设计键值模型和 MemTable API，再由你实现第一组核心操作。
