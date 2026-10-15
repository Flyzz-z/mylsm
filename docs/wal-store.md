# WAL 与 MemTable 的组合

WalStore 提供 `open(Path)`、`put(ByteKey, Value)`、`delete(ByteKey)`、`get(ByteKey)` 与 `close()`。它组合已有的 WalWriter 和 TreeMapMemTable，让操作历史可以重建内存状态。仍限定单线程、单实例独占一个日志文件，没有文件锁；父目录必须已存在。

## 正常写入与恢复走不同入口

```text
正常 put/delete
  → 检查状态与参数
  → writer.append：编码与大小检查 → 完整写入 → force(true)
  → apply：更新私有 MemTable
  → 返回成功

打开恢复
  → 创建尚未对外暴露的新 MemTable
  → openRecovering：初始化或校验文件头 → 顺序扫描
  → 每条有效记录回调 apply：直接更新新 MemTable
  → 必要时截尾并同步
  → 返回 WalStore

查询 get
  → 检查状态 → 查询私有 MemTable
```

apply 只更新内存。若恢复回调调用 store.put/delete，会把历史操作重新写进日志，造成日志增长；因此恢复直接使用 apply。只有整个恢复成功后才暴露新的表，避免读取半恢复状态。

正常写入必须先日志同步再更新内存。如果先更新内存，后续日志写入失败，用户可能已经读到无法恢复的值。当前顺序让返回成功同时意味着日志同步和内存更新完成。代码复用 WalWriter 的编码与大小检查，不在 store 重复编码。

## 查询仍有三态

| 查询结果 | 含义 |
| --- | --- |
| Optional.of(Value) | 当前值，包括空字节值 |
| Optional.of(Tombstone.INSTANCE) | 当前记录是删除标记 |
| Optional.empty() | 没有这个键的记录 |

删除未出现的键、重复删除，都保存 Tombstone。重放按操作顺序覆盖，所以 PUT(k,A)、DELETE(k)、PUT(k,B) 最终得到 B。MemTable 与 writer 都是私有字段，外部不能绕过 WAL 直接修改存储内部状态。

## 失败不等于回滚

```text
OPEN ──追加/同步失败──→ FAILED ──close──→ CLOSED
  └──────────────────close────────────→ CLOSED
```

null 或超限参数在追加前拒绝，文件与内存不变，store 仍可用。追加或同步失败时，不执行内存更新，并拒绝后续 get/put/delete，只允许关闭。同步后内存更新若发生意外异常，也停止服务。

即使同步报错，文件里也可能已经有完整有效记录。重新打开会重放它，所以调用报错的操作可能在恢复后出现；不能承诺失败操作必定回滚。如果只有残缺记录，则恢复时截去尾部，保留此前的有效状态。

为什么连查询也拒绝：当前内存可能还保留 A，而日志已经有完整的 B。继续提供查询会让当前实例与重新打开后的状态出现分歧。失败实例不再服务，关闭后由恢复规则重新建立状态。

close 可重复调用；即使关闭报错，实例也保持关闭状态，不重新开放读写。

## 使用示例

以下类来自 `io.github.flyzz.mylsm.wal.WalStore`、`io.github.flyzz.mylsm.model.ByteKey` 和 `io.github.flyzz.mylsm.model.Value`；Path 与 Files 来自 java.nio.file。

```java
Path path = Path.of("data", "example.wal");
Files.createDirectories(path.getParent()); // 调用方准备父目录。
ByteKey key = ByteKey.copyOf(new byte[]{0x6b});
Value value = Value.copyOf(new byte[]{0x41});

try (WalStore store = WalStore.open(path)) {
    store.put(key, value);
    System.out.println(store.get(key));
}

try (WalStore reopened = WalStore.open(path)) {
    // 查询恢复的值；重放本身不会追加日志。
    assert reopened.get(key).orElseThrow().equals(value);
}
```

## 验证与保证边界

WalStoreTest 在真实临时文件上验证三态查询、覆盖与删除、空键空值及二进制重放。代理 WalIo 在短写和同步边界观察 store 查询仍返回旧值，验证只有完整同步后内存才改变；注入部分追加失败、完整追加后报错和同步报错，验证失败实例停止服务，并按实际有效日志重新恢复。参数失败、打开失败、资源关闭和重复关闭也有覆盖。

WalStoreProcessTest 启动独立 JVM，完成多次成功写入和删除后使用 Runtime.halt(0) 退出，跳过 close 和 shutdown hook。测试进程再恢复日志并继续写入，验证成功调用无需正常关闭即可恢复。这是进程退出测试，不是机器断电测试。

成功写入依赖平台正确实现 FileChannel.force(true)。当前不保证新建日志的父目录项断电持久性，也不提供事务、并发或跨进程同时访问支持。残缺尾部与损坏的区别及格式限制见 [恢复笔记](wal-recovery.md)。日志仍会增长，轮转与回收留待未来刷盘设计。

本小步完成组合与端到端验证。WAL 阶段仍待共同复盘及 OpenSpec 归档；SSTable、冻结、刷盘和压缩尚未开始。
