# 第二步：值、删除标记与记录缺失

本步实现 Entry、Value 和 Tombstone。MemTable 与 get 方法将在后续步骤实现。

## Entry 为什么是空接口

```java
public sealed interface Entry permits Value, Tombstone {
}
```

这里接口的作用是给两种合法记录提供共同类型，不要求它们都有相同的读取方法。`sealed` 限定允许的直接实现，`Value` 用 final 阻止继续继承，单成员 `Tombstone` 枚举也无法被调用方扩展。只有 Value 有字节载荷，因此 toByteArray 不放在 Entry 上。

下面是处理非 null 记录的 Java 21 示例。编译器知道只有两种合法类型，因此 switch 可以穷尽分支而无需 default：

```java
String describe(Entry entry) {
    return switch (entry) {
        case Value value -> "有值，长度为 " + value.toByteArray().length;
        case Tombstone ignored -> "已删除";
    };
}
```

类型封闭不影响以后替换 TreeMap 与跳表：它限定记录能表达哪些状态，而不是限定数据结构。

## Value 与 ByteKey 的联系

两者都独立持有字节内容：copyOf 复制输入，toByteArray 复制输出，equals/hashCode 按内容计算。外部修改数组不会改变已经创建的对象。

Value 没有 compareTo，因为记录按键排序，值不参与索引顺序。虽然代码有少量相似处，目前不引入共享父类，以免混淆键与值的语义；相同字节的 Value 与 ByteKey 并不相等。

## 单成员枚举为什么能表示删除

```java
public enum Tombstone implements Entry {
    INSTANCE
}
```

这里的 INSTANCE 是唯一的枚举实例，可以直接作为对象使用：

```java
Entry deleted = Tombstone.INSTANCE;
```

删除标记不保存键或字节值，所以所有键都能共享它。未来表中的 `key-a → Tombstone.INSTANCE` 表示删除 key-a；`key-b → Tombstone.INSTANCE` 表示删除 key-b。键在映射关系中，不在标记内部。

## 两种记录如何构成三种查询状态

未来计划使用 `Optional<Entry> get(ByteKey key)`，此方法当前尚未实现。

| 表达式 | 含义 |
| --- | --- |
| `Optional.of(Value.copyOf(new byte[0]))` | 有记录，内容恰好为空 |
| `Optional.of(Tombstone.INSTANCE)` | 有记录，明确表示删除 |
| `Optional.empty()` | 当前表没有这个键的信息 |

`Value.copyOf(null)` 会抛出异常，null 不被用来表示删除。将来读取更旧的数据时，“删除”应停止查找，“当前表无记录”可以继续查找。也不能用 `Optional.filter` 随意滤掉 Tombstone，否则会把删除错误地转换成缺失。

## 验证

ValueTest 验证输入与输出的数组隔离、内容相等和哈希一致、不同内容/长度/类型不相等、空值与删除标记区分，以及 null 拒绝。枚举单例和 sealed 限制由 Java 类型系统提供，不需要手写单例管理代码。

```sh
./mvnw -Dtest=ValueTest test
./mvnw clean verify
```

下一步讨论 MemTable 的 put/get/delete 接口与有序遍历，再实现基于 TreeMap 的第一版。
