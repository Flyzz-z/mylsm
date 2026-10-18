# SSTable 可暂停游标

这是 compaction 阶段的第一个实现小步。已确认的完整方案见 [设计](../openspec/changes/archive/2026-10-18-implement-compaction/design.md)，本步最初新增逐块游标，当前已按[独立读 context](sstable-read-context.md)支持同一 Reader 上的多个读取进度。后续已完成[多路有序归并](sstable-merge.md)、[Manifest 替换](manifest-compaction.md)、[自动后台压缩](lsm-automatic-compaction.md)及[手动 compact](lsm-manual-compaction.md)。

## 为什么需要游标

原有 `scan(Consumer)` 从头到尾调用回调，适合检查或消费一张表。但多路归并需要先取每张表的一个头记录，比较键后只推进其中一些输入。如果用现有 scan 把所有输入先收集到列表或 Map，会使记录内存随全库规模增长。

游标把推进权交给调用方，可以暂停在一条记录上，再推进另一个输入；每个游标只持有当前解码块。

## 接口与状态

```java
try (SsTableReader reader = SsTableReader.open(path);
     SsTableReader.Cursor cursor = reader.openCursor()) {
    while (cursor.next()) {
        SsTableRecord record = cursor.record();
        // 消费当前记录；它保留原始 Value 或 Tombstone。
    }
}
```

- `openCursor()` 不读数据块，只建立本次读取的独立遍历状态。
- `next()` 返回 true 才具有当前位置；返回 false 表示正常耗尽，可重复返回 false。
- `record()` 不推进，可以重复读取同一条不可变记录；第一次 next 之前、耗尽之后或关闭后调用都被拒绝。
- 正常耗尽仍须显式关闭以释放本 context 的局部状态，不占用其他读取。`try-with-resources` 会先关闭游标、再关闭 Reader。

SSTable 中每个块非空且键严格递增；块最大键和前一块边界也要匹配索引。游标复用现有 `readBlock`，不新增另一套格式校验逻辑。

## next 如何工作

游标保存下一个待读块编号、当前块记录列表、当前记录下标及关闭状态。

若当前块还有记录，next 只移动下标，没有文件 I/O。否则先清除旧下标和旧块引用，再判断是否还有数据块；有则完整读取、解码和校验新块，验证成功后才把第一条记录暴露出来。

例如两块 `[a,b]`、`[c,d]`：第一次 next 读取第一块，第二次只移动到 b，第三次才读取第二块。暂停在 a 或 b 时不预读后一块。

游标持有的记录缓冲为一个解码块，索引仍随 Reader 常驻；读取/解码还有帧字节和临时对象，所以不能把它解释成“总堆内存只有一个块大小”。外部调用者若主动收集所有 record，也会自行增加内存。

## Reader 与游标的所有权

Reader 共享文件资源和不可变索引，不保存唯一当前游标。同一 Reader 可以有多个独立 Cursor，允许 get、scan 和其他游标交错或并行；每个 Cursor 只由一个调用线程使用。scan 复用独立 Cursor，回调在 Reader 锁外执行，允许发起其他独立读取。

关闭游标只清除其当前记录和块缓冲，不关闭文件、不改变其他游标。重复关闭旧游标不影响其他 context。

Reader 的 get/openCursor/next/record 使用共享生命周期读锁，关闭持独占写锁等待当前读取退出；关闭后全部已有 context 不能继续 next/record，包括文件关闭报错时。暂停的 context 不阻止 Reader 关闭，其局部缓冲由各自 close 释放；不使用全局 context 注册表或引用计数。后台 compaction 仍独立打开输入 Reader，前台继续使用已有 Reader。

## 错误不等于结束

next 保留 checked `IOException`。读取无进展、提前 EOF、CRC 错误、块内容非法或与索引边界矛盾都会明确报错，使 Reader 失效，只允许关闭。

旧位置在尝试下一块前已经清除，因此下一块损坏时既不会返回部分坏块，也不会让 record 继续返回上一块的旧记录。此前已交给调用者的合法记录不回滚；调用者必须把下一次推进错误视为整次操作失败，不能发布部分归并结果。

没有直接实现 `Iterator`，因为标准 next/hasNext 不能声明 checked I/O。后续接入现有 Iterable Writer 时再在一个局部边界适配异常。

## 验证与后续

最初新增 17 个测试用例，验证空表和当前位置、创建无预读、块内推进无额外 I/O、跨块顺序、二进制与三态、不同输入交替推进、提前关闭、冲突操作与 scan 重入、CRC/内容/索引边界损坏、短读和无进展/EOF/异常、关闭失败及重复关闭。

本步完成时 `./mvnw test` 与 `./mvnw clean verify` 均通过，共 544 个用例，0 失败；`openspec validate --all --strict --no-interactive` 通过。后续已使用多个游标实现[有序归并](sstable-merge.md)，按输入表在 Manifest 中的位置选择同键最新记录；后台调度与文件集替换现已接入，见[自动压缩](lsm-automatic-compaction.md)及[手动请求](lsm-manual-compaction.md)。

后续独立 context 调整了原有占用及重入契约，增加并行读取、共享失败和关闭验证，当前行为见[读 context 笔记](sstable-read-context.md)。
