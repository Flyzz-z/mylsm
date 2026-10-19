## Context

动机见 proposal.md。MemTable 是按 ByteKey 内容识别键的内存表，每键只有最新 Entry；查询区分值、Tombstone 与未找到，snapshot 返回独立的不可修改配对列表。现有 MemTableContractTest 可供新实现复用。

## Goals / Non-Goals

**Goals:** 直接编写面向 ByteKey/Entry 的跳表，展示前驱路径如何支撑查找和插入，最终保持现有 MemTable 契约。

**Non-Goals:** 不抽取通用泛型容器，不实现物理删除或 CAS 并发算法，不改变 WAL、SSTable 及 LsmStore/WalStore 的构造路径。

## Decisions

### 已共同讨论并获用户同意的方案

- 采用 SkipListMemTable，内部 Node 保存不可变 key、可替换 Entry 和按高度分配的 Node[] next；节点不为各层复制。
- head 是内部哨兵，拥有 32 个前向指针，其 null key/entry 不参与比较或暴露。真实节点的 key 和 entry 禁止为 null，空字节 key 仍合法。
- height 表示层数，最少为 1；节点高度不超过 32。所有层严格递增，底层包含全部键，且高层节点也属于所有更低层。
- 晋升概率为 1/2，默认使用 JDK 随机源。相较 1/4，额外指针稍多，但便于学习和推导；不承诺最坏 O(log n)。
- 同 key 只替换 entry，不改变节点高度或重新调用高度生成器。只有新 key 才增加 size；size 包括删除标记。
- delete 通过统一 upsert 写 Tombstone.INSTANCE；本阶段无需物理删除及层高回缩。
- snapshot 遍历第 0 层，新建 MemTableRecord 后返回 List.copyOf；共享不可变键和记录，禁止暴露可变 Node。

### 当前实现小步骤

先实现 Node、head、height、size、put/get 和内部 upsert。查找从高层向右再向下；插入用 update[] 保存各层前驱，get 不分配此数组。新高度超出当前高度时，对应前驱补为 head。先检查参数和生成高度的合法性，再修改表。

为避免用未完成方法冒充契约实现，当前类保持包内可见，不声明 implements MemTable，也不放置 UnsupportedOperationException 占位方法；后续完成 delete/snapshot 后再公开并实现接口。这是开发中的过渡状态，不是第二套对外 API。

包内构造器接受 IntSupplier 作为高度生成器，测试能指定 1 至 32 层的形状，不暴露节点。非法高度在修改状态前抛 IllegalArgumentException；覆盖写入不消费高度。默认构造器按抛硬币生成高度。

### 验收方式

当前验证：空表、未命中位置、乱序插入、全单层和最高层插入、不同层高之间下降、内容相同的键覆盖、前缀及无符号排序、null 与非法高度失败后状态不变。使用固定种子的混合 put/get 与 TreeMapMemTable 对照，不依赖时间阈值或随机概率来判断通过。

后续步骤再复用完整 MemTable 契约测试，覆盖 tombstone 与稳定快照。本次完成一个小步骤后说明代码和验证结果，留出讨论空间，不自动一次性完成阶段。

## Risks / Trade-offs

- 随机结构使失败难复现 → 测试固定高度或种子，专门覆盖退化成单链表的形状。
- 可变 entry 可能破坏旧快照 → 快照创建独立记录配对；在快照步骤验收。
- 固定层数对无限增长规模不保证对数复杂度 → 明确容量场景，不用复杂度的理想期望替代正确性测试。
- 类未提供同步 → 后续接入仍由上层保护读写；本次不变更上层并发路径。
- 当前类仅有部分方法 → 保持包内可见，任务清单保留未完成项，不归档或同步为已实现正式规范。
