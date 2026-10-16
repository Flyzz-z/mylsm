# MyLSM

使用 **Java 21** 分阶段实现 LSM Tree，由你指挥 AI 逐步实现核心算法，在理解设计、代码与取舍的过程中学习存储引擎。当前已完成环境初始化、不可变字节模型和基于 TreeMap 的 MemTable（put/get/delete/snapshot），已完成阶段 1 的内存 KV 范围；阶段 2 已完成 WAL 编解码、同步追加、恢复扫描、残缺尾部修复及 WalStore 组合入口，支持单线程读写与重新打开恢复，已复盘并归档。

阶段 3 已完成并归档：支持 SSTable 编解码、新文件分块写入与失败清理、只读打开、三态点查和逐块有序遍历。见 [数据块笔记](docs/sstable-block.md)、[元信息笔记](docs/sstable-metadata.md)、[文件写入笔记](docs/sstable-writer.md)、[打开与点查笔记](docs/sstable-reader.md) 和 [有序遍历与阶段复盘](docs/sstable-scan.md)。

阶段 4 已实现 Manifest、LsmStore 目录恢复与三态读写，以及单冻结表后台刷盘、阈值背压、显式 flush 和安全关闭；见[编解码笔记](docs/manifest-codec.md)、[文件笔记](docs/manifest-file.md)、[目录恢复笔记](docs/lsm-store.md)和[后台刷盘笔记](docs/lsm-background-flush.md)。默认每表逻辑目标 4 MiB，可通过 open 重载配置。启动时的孤儿清理与完整阶段验收尚待完成，见[设计](openspec/changes/implement-minimal-lsm/design.md)和[任务](openspec/changes/implement-minimal-lsm/tasks.md)。

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

首次构建需要联网下载 Maven 和依赖。Windows 使用 `mvnw.cmd`。JUnit 5 已覆盖基础模型与 MemTable 契约、WAL 帧格式与损坏输入、同步追加与恢复修复、短读写和 I/O 失败状态，以及 WalStore 的三态查询、重放、失败边界和独立进程未正常关闭时的恢复；构建通过只代表当前已实现范围通过验证。

## 目录

```text
src/main/java/io/github/flyzz/mylsm/
  model/                             ByteKey、Entry、Value、Tombstone
  memtable/                          MemTable、TreeMapMemTable、MemTableRecord
  wal/                               WAL 编解码、同步追加与恢复修复、WalStore
  sstable/                           SSTable 编解码、文件构建、点查与有序遍历
  lsm/                               Manifest、LsmStore 恢复、读写与后台刷盘
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

以下用已完成的有序遍历变更展示 CLI 流程；开始新变更时请使用新的分支名与变更名，不要重复执行此例：

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

见 [学习路线](docs/roadmap.md) 和 [协作约定](AGENTS.md)。已完成的基础模型见 [ByteKey 学习笔记](docs/byte-key.md) 和 [记录模型学习笔记](docs/entry-model.md)。内存表实现见 [MemTable 学习笔记](docs/memtable.md)。有序遍历及阶段复盘见 [快照学习笔记](docs/memtable-snapshot.md)。当前 WAL 见 [编解码学习笔记](docs/wal-codec.md)、[文件写入笔记](docs/wal-writer.md)、[恢复笔记](docs/wal-recovery.md) 和 [存储组合笔记](docs/wal-store.md)。WalStore 单线程独占日志，成功写入依赖平台正确实现文件同步，不保证新建文件父目录项的断电持久性；未正常关闭的进程退出测试不等同于断电实验。
