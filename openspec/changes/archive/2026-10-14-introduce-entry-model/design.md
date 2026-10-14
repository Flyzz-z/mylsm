## Context

动机见 proposal.md。已有不可变 ByteKey；用户已同意记录分为 Value/Tombstone，未来查询以 Optional<Entry> 区分当前表中的记录缺失。当前没有 MemTable 或持久化格式。

## Goals / Non-Goals

**Goals:** 建立小而完整的记录模型，复用已学过的数组所有权知识，引入 sealed 接口及单成员枚举。

**Non-Goals:** 不为记录增加键、时间戳或版本号，不实现查询和排序，不增加存储格式或通用序列化框架。

## Decisions

1. `Entry` 为 `sealed interface`，仅 permits `Value, Tombstone`。相较 boolean 标志搭配可空数组，此设计无法构造“既有值又被删除”等矛盾状态；未找到不属于记录类型。
2. `Value` 为 final 类，提供 `copyOf(byte[])` 和 `toByteArray()`，两端复制数组。与 ByteKey 一致，equals/hashCode 按内容实现；值不实现 Comparable，因为索引按键排序。使用 Arrays 与 Objects，无新依赖。不直接使用默认数组 record，避免错误的相等语义和数组泄漏。
3. `Tombstone` 为单成员枚举，公开 `INSTANCE`。没有载荷，所有键可以引用同一个标记；具体删除哪个键由未来的键到记录映射决定。
4. 未来 `Optional<Entry> get(ByteKey key)` 中，empty 表示当前表无记录，present Value 表示值，present Tombstone 表示显式删除。此处只记录签名方向，不提前增加查询实现。
5. `Entry` 不提供统一的字节导出方法：只有 Value 有字节载荷。消费者先按类型分支，不能将删除误当成空数组。

## Risks / Trade-offs

- 防御性复制增加分配 → 以正确的数据所有权为先，暂不引入零拷贝。
- sealed 限制第三方添加记录种类 → 当前逻辑状态固定，不影响未来替换 MemTable 实现；未来增加版本元数据需明确修订契约。
- 将空值误判为缺失 → 使用类型区分删除，缺失由未来查询的 Optional 容器表达，并在学习笔记中展示。
