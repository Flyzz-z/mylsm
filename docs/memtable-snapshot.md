# 第四步：复制式有序快照

`MemTable.snapshot()` 返回 `List<MemTableRecord>`，按 ByteKey 自然顺序排列，包含每键的最新值或删除标记。空表返回空列表。创建快照不会冻结或改变原表。

## 实现中的三行

```java
var records = new ArrayList<MemTableRecord>(entries.size());
entries.forEach((key, entry) -> records.add(new MemTableRecord(key, entry)));
return List.copyOf(records);
```

1. 创建独立列表，预留当前记录数的容量。
2. TreeMap 按键顺序遍历，新建每个键与状态的配对。这里保留 tombstone，不进行过滤。
3. 返回不可修改的列表，调用方不能 add/set/remove/clear，也不能通过迭代器删除记录。

不能简单返回原表 entrySet 或只读包装：只读包装只禁止通过这个引用修改集合，不保证底层表停止变化。原表的 Map.Entry 也可能跟随覆盖写入发生变化，因此快照使用独立的 MemTableRecord。

## record 的不可变性来自哪里

```java
public record MemTableRecord(ByteKey key, Entry entry) {
    public MemTableRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entry, "entry");
    }
}
```

record 自动生成 key()/entry() 访问方法，以及 equals/hashCode。紧凑构造器检查参数后，Java 自动将参数赋给字段。record 本身只保证组件引用不可重新赋值；这里 ByteKey 和 Entry 的两种实现也不可变，才让整个配对能够安全共享。

复制列表时不复制底层字节数组。覆盖表中的 Value(A) 只让表指向 Value(B)，旧快照仍引用不可变的 Value(A)，所以读到的仍是 A。只要快照持有旧值，垃圾回收器就不会回收它。

## 使用示例

以下示例使用现有类型，导入包为 `io.github.flyzz.mylsm.memtable` 和 `io.github.flyzz.mylsm.model`：

```java
MemTable table = new TreeMapMemTable();
ByteKey key = ByteKey.copyOf(new byte[]{1});
table.put(key, Value.copyOf(new byte[]{10}));
var before = table.snapshot();

table.delete(key);
var after = table.snapshot();

// before.getFirst().entry() 仍是 Value([10])
// after.getFirst().entry() 是 Tombstone.INSTANCE
for (MemTableRecord record : before) {
    // record.key() 与 record.entry() 是获取快照时固定的配对。
}
```

## 成本和边界

创建快照需要 O(n) 时间与额外空间：每条记录新建配对，并创建临时及结果列表的引用数组。字节内容共享，但快照会延长旧键值对象的存活时间。用完后不再保留快照引用即可，无需 close。

这仍是单线程内存表；不支持在复制期间由其他线程修改，也不提供持久化或 MVCC。未来冻结方案会让旧表只读、新表承接写入，以避免整表复制，但需要管理切换、刷盘失败与旧表释放的生命周期，本步尚未实现。

## 验证与阶段复盘

契约测试覆盖乱序写入后的无符号排序、前缀与空键、每键最新记录、删除标记、旧快照隔离、新快照可见性、列表及迭代器不可修改、字节数组隔离。记录构造测试覆盖 null 键与 null 状态。

至此阶段 1 的内存 KV 范围已完成：字节模型、删除三态、可替换 MemTable、基本操作和有序遍历。核心不变量是每键最新状态、删除保留、统一键序与不可变的数据边界。当前数据随进程退出而丢失；下一阶段讨论 WAL 如何记录操作以恢复内存状态。

```sh
./mvnw -Dtest=TreeMapMemTableTest,MemTableRecordTest test
./mvnw clean verify
```
