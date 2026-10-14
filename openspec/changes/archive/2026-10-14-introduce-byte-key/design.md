## Context

动机见 proposal.md。目前没有业务代码；用户已选择字节模型，并要求未来能替换 MemTable 实现。此文档只规划第一个基础类型，尚不表示已实现。

## Goals / Non-Goals

**Goals:** 用一个小类型建立内容相等、统一排序和不可变性，让用户能逐项理解契约。

**Non-Goals:** 不抽象通用编码框架，不引入可配置比较器，也不实现 MemTable。

## Decisions

1. 用户已选择底层字节模型。键使用 `io.github.flyzz.mylsm.ByteKey`，值未来使用字节序列；业务编码放在上层。采用 final 类实现 `Comparable<ByteKey>`，不直接用默认 record 包装数组，因为 record 不会自动提供数组内容相等和防御性复制。
2. 拟定最小 API：`ByteKey.copyOf(byte[])`、`toByteArray()`、`compareTo`、`equals`、`hashCode`。构造入口和导出入口都复制数组。不暴露内部可变数组；首版无需缓存哈希。
3. 排序使用 `Arrays.compareUnsigned`，内容身份使用 `Arrays.equals` 和 `Arrays.hashCode`。先解释算法规则，再复用 JDK；不为学习 LSM 重写已有的字节比较库。未来 TreeMap、跳表和 SSTable 需遵守同一规则。
4. 补充边界约定：允许空键，拒绝 null，使用 NullPointerException 表达非法空引用。空键与缺失记录是不同概念。
5. 字节字典序不自动等于业务数字顺序，例如 UTF-8 字符串 "10" 会排在 "2" 前。数字保序编码属于未来上层需求，不改变底层比较规则。

## Risks / Trade-offs

- 复制数组有分配成本 → 先保证不可变性，未来有测量依据时再讨论受控的数据所有权优化。
- 误用 Java 有符号 byte 比较 → 覆盖 7F、80、FF 的排序测试。
- 实现替换导致键身份不同 → 检验 equals、hashCode 与 compareTo 的一致性，验证比较反对称性和传递性。

## 已讨论的后续上下文

这些决策供下一步设计使用，不属于本变更交付范围：MemTable 通过接口替换 TreeMap/跳表；每键只保留最新状态；删除必须留下 tombstone，即使当前内存没有这个键；读取区分有值、已删除、未找到；空值合法且不代表删除；值也要防止外部数组修改。
