# 第三步：可替换的 MemTable

本步实现单线程下的 put/get/delete。基础类型位于 `io.github.flyzz.mylsm.model`，内存表接口和实现位于 `io.github.flyzz.mylsm.memtable`。依赖方向是 memtable → model。

## 接口与实现

```java
import io.github.flyzz.mylsm.memtable.MemTable;
import io.github.flyzz.mylsm.memtable.TreeMapMemTable;
import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Value;

MemTable table = new TreeMapMemTable();
ByteKey key = ByteKey.copyOf(new byte[]{1});

table.get(key);                         // Optional.empty()
table.put(key, Value.copyOf(new byte[]{10}));
table.get(key);                         // Optional 内包含 Value([10])
table.delete(key);
table.get(key);                         // Optional 内包含 Tombstone.INSTANCE
table.put(key, Value.copyOf(new byte[]{20}));
table.get(key);                         // Optional 内包含 Value([20])
```

变量类型是 MemTable，只有创建时选择 TreeMapMemTable。以后替换为跳表时，上层三个操作无需改写。接口不暴露内部 Map，也不承诺特定的对象引用身份。

## 三个操作为什么这样写

### put：覆盖每键最新状态

先检查 key/value 不为 null，再执行 `entries.put(key, value)`。TreeMap 使用 ByteKey.compareTo 定位键，因此不同对象只要字节内容相同，就覆盖同一条记录。原记录是 Value 或 Tombstone 都适用。

ByteKey 和 Value 已保证不可变，表直接保存对象引用即可，不需要再复制它们的数组。表内始终没有 null 值。

### get：区分当前表的三种状态

```java
return Optional.ofNullable(entries.get(key));
```

`Optional.ofNullable` 在参数是 null 时返回 empty，否则包住参数。由于写入禁止 null，TreeMap.get 返回 null 只可能意味着没有记录。若找到 Tombstone，它不是 null，因此仍然得到 present 结果。

这还不是最终数据库对外的查询 API：未来上层遇到 Tombstone 应停止读取旧数据，而遇到 empty 才继续查旧层。当前实现没有磁盘层。

### delete：保存删除事实

```java
entries.put(key, Tombstone.INSTANCE);
```

不能调用 remove，否则未来磁盘中的旧值可能重新被读出。即使当前键不存在，也需要写入删除标记；重复删除仍保留同一语义。

## 空值、错误与范围

- 空字节键和空字节值均合法，空值仍然是 present Value。
- null 键或 null 值会抛出 NullPointerException。检查发生在修改表之前，因此失败不会覆盖原记录。
- 每个实例持有自己的 TreeMap；没有共享可变表状态。
- 本版只支持单线程访问。所有数据仅在内存中，没有 WAL、刷盘或重启恢复。
- TreeMap 以红黑树保持内部键顺序，基本操作进行 O(log n) 次键比较；字节键比较自身还取决于共同前缀长度。当前尚未暴露遍历 API。

## 如何验证实现可以替换

`MemTableContractTest` 包含 12 个只调用接口的行为测试；`TreeMapMemTableTest` 继承这些测试，只负责创建具体实现。以后新增跳表时，通过同一组测试验证其写入、查询三态、删除、空值、错误与数据隔离语义。

```sh
./mvnw -Dtest=TreeMapMemTableTest test
./mvnw clean verify
```

下一步是有序遍历：需要讨论返回键值记录的形式，以及遍历开始后发生写入时，要看到快照还是实时变化，再为刷盘准备接口。
