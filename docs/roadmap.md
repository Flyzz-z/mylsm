# 分阶段学习路线

以下为候选路线，每次开始一个阶段时再细化设计，不一次性生成全部实现。你主导设计决策和推进节奏，AI 负责编写代码与测试。

| 阶段 | 学习与实现内容 | 你参与的关键步骤 | 验收重点 |
| --- | --- | --- | --- |
| 0：环境 | Java 21、Maven、JUnit、OpenSpec、Git | 理解目录与协作方式 | 构建与规范检查通过 |
| 1：内存 KV | 键值语义、比较规则、MemTable、删除标记 | 确定 API，指挥 AI 逐步实现并理解各操作 | 覆盖写入、覆盖、删除、边界和排序 |
| 2：WAL | 日志格式、写入顺序、校验与重放 | 讨论日志格式，理解恢复与尾部截断策略 | 重启恢复；区分正常关闭与崩溃持久性 |
| 3：SSTable | 不可变有序文件、块、索引 | 选择文件布局，理解读写与定位过程 | 往返一致、缺失键、边界和损坏输入 |
| 4：最小 LSM | 单冻结表后台刷盘、背压、多 SSTable 读取、文件集元数据 | 理解并确定日志切换、版本选择、删除遮蔽与恢复规则 | 刷盘期间可写、背压有界、重启不丢数据且旧值不复活 |
| 5：压缩 | 多路合并、压缩策略、文件替换 | 讨论合并流程与 tombstone 回收条件 | 压缩前后语义一致，中断可恢复 |
| 6：查询与性能 | Bloom Filter、缓存、范围扫描、基准 | 讨论取舍并选择一项优化交给 AI 实现 | 正确性不变，用测量解释收益与代价 |
| 7：进阶（可选） | 后台压缩、并发、快照/MVCC | 选择目标并定义一致性模型 | 并发与故障场景验证 |

阶段 0–3 优先建立单进程、单线程下可解释的正确性；阶段 4 按用户选择引入一个内部后台刷盘线程，前台仍串行调用。具体存储格式和持久性承诺在对应阶段共同确定。

## 每阶段的节奏

1. **理解**：画出读写路径，回答本阶段解决什么问题。
2. **设计**：创建 OpenSpec change，共同明确接口、不变量、非目标和验收场景。
3. **逐步实现**：把核心任务拆成可讨论的小步骤，你指挥 AI 编写代码；每步结合实现解释原理，留出提问和调整的空间。
4. **验证**：AI 运行针对性的测试，解释结果、分析失败并修复，你参与审阅。
5. **复盘**：共同回顾关键不变量与设计取舍，记录到 `docs/`；归档 change 并提交 Git。

当前已完成阶段 0、阶段 1 与阶段 2。内存部分包括字节键、记录模型、MemTable 基本操作及复制式有序快照，见 [键学习笔记](byte-key.md)、[记录学习笔记](entry-model.md)、[MemTable 学习笔记](memtable.md) 和 [快照与阶段复盘](memtable-snapshot.md)。WAL 部分包括编解码、文件头、同步追加、恢复扫描、残缺尾部修复及 WalStore 组合，见 [WAL 编解码笔记](wal-codec.md)、[文件写入笔记](wal-writer.md)、[恢复笔记](wal-recovery.md) 和 [存储组合与阶段复盘](wal-store.md)；已验证独立进程未正常关闭时的恢复，并完成复盘和归档。

阶段 3 已完成复盘与归档。SSTable 支持编解码、新文件写入、打开、三态点查与有序遍历，见 [数据块笔记](sstable-block.md)、[元信息笔记](sstable-metadata.md)、[文件写入笔记](sstable-writer.md)、[打开与点查笔记](sstable-reader.md) 和 [有序遍历与阶段复盘](sstable-scan.md)。冻结、自动刷盘、多文件版本选择与 WAL 回收已在阶段 4 接入。

阶段 4 已完成实现、验收、[复盘](lsm-review.md)及归档。包含 [Manifest 编解码](manifest-codec.md)、[元信息文件恢复](manifest-file.md)、[LsmStore 目录恢复与基础读写](lsm-store.md)、[单冻结表后台刷盘、背压、显式 flush 和关闭协调](lsm-background-flush.md)，以及启动时孤儿清理与[独立进程退出验收](lsm-process-recovery.md)。正式规范为 [lsm-store](../openspec/specs/lsm-store/spec.md) 与 [lsm-manifest](../openspec/specs/lsm-manifest/spec.md)，阶段记录见[归档设计](../openspec/changes/archive/2026-10-16-implement-minimal-lsm/design.md)与[归档任务](../openspec/changes/archive/2026-10-16-implement-minimal-lsm/tasks.md)。下一候选阶段是压缩，尚未开始设计或实现。
