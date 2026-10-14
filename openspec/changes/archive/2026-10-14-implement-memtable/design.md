## Context

参见 proposal.md。ByteKey 与 Entry 模型已完成，用户同意先实现 put/get/delete，暂缓有序遍历。沿用 Java 21、JUnit 5，无新增依赖。

## Goals / Non-Goals

**Goals:** 稳定的小接口、TreeMap 首版实现、可复用行为测试以及按职责组织的包结构。

**Non-Goals:** 不承诺并发安全，不实现遍历、磁盘读取、内存限额或历史版本。

## Decisions

1. `memtable.MemTable` 提供 `void put(ByteKey, Value)`、`Optional<Entry> get(ByteKey)`、`void delete(ByteKey)`。上层依赖接口，通过构造具体实例选择实现，无需插件框架。
2. `TreeMapMemTable` 私有持有 `TreeMap<ByteKey, Entry>`，按 ByteKey 自然顺序组织数据。TreeMap 保留了后续有序遍历的基础，但本轮没有遍历 API。不选择 HashMap，避免下一步再替换底层有序结构。
3. put 先检查全部参数，再写入 Value；delete 通过 put 写入 Tombstone.INSTANCE，不能 remove；get 以 Optional.ofNullable 包装查找结果。表内没有 null 值，因此 null 查找结果只表示缺失。
4. ByteKey/Value 已不可变，内存表可以安全共享对象引用，无需在读写时再次复制；不暴露内部 Map。
5. `model` 放 ByteKey、Entry、Value、Tombstone，保持 sealed 家族同包；`memtable` 只依赖 model。已有模型测试同步迁移，历史归档保持原样。
6. 使用抽象 `MemTableContractTest` 定义基于接口的共同测试，具体测试类只提供 createMemTable。未来跳表复用这些行为测试，避免替换时改变删除或缺失语义。

## Risks / Trade-offs

- TreeMap 非线程安全 → 接口与实现明确标注仅供单线程使用。
- 删除标记占用内存 → 这是遮蔽旧值所需的语义；回收策略留到后续阶段。
- 包迁移改变 import → 更新全部当前源码和学习文档，并运行完整测试；无已发布版本兼容负担。
- 有序遍历语义尚未确定 → 不在本轮暴露内部集合、迭代器或快照 API。
