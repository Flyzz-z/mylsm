# 阈值自动后台压缩

本步把[多路归并](sstable-merge.md)和[Manifest 替换协议](manifest-compaction.md)接入 LsmStore，并完成自动阈值触发。自动与[同步手动 compact()](lsm-manual-compaction.md) 共用唯一工作线程，本阶段已完成[复盘与归档](compaction-review.md)。

## 配置与触发

已有 open 重载默认在有效 SSTable 数量达到 4 时自动压缩；可显式配置：

```java
// directory 为已有目录；每表逻辑目标 4 MiB，自动压缩阈值 4 张。
try (LsmStore store = LsmStore.open(directory, 4L * 1024 * 1024, 4)) {
    // put/delete/flush 按已有前台串行约定调用。
}
```

阈值至少为 2，非法参数在文件操作前拒绝。统计 Manifest 的有效表数，不统计目录孤儿或未发布输出；配置作用于本次打开，不写入格式。重开时按本次配置重新判断。

自动压缩只整理已发布 SSTable，不主动冻结 active，不改写 active WAL。flush() 继续只等待调用前内存数据全部发布，不等待自动压缩完成，返回时压缩可能已开始，也可能尚待后台选择。

## 唯一后台循环

后台仍只有一个 mylsm-background 线程，没有定时检测线程、线程池或任务队列：

```text
持锁检查：
  有 frozen                         → flush
  否则 OPEN 且有手动请求              → 手动 compaction（少于两表无操作）
  否则 OPEN 且有效表数达到阈值         → 自动全量 compaction
  OPEN 且没有工作                    → Condition 等待
  正在关闭且没有 frozen               → 退出
```

恢复后首次检查即可触发，任务发布后回到循环重新判断，无需另维护自动压缩标记。多表归并为一张，不会立即对单张输出重复压缩。

优先级只在任务边界生效，已开始压缩执行到底。前台期间可产生一个 frozen，等待压缩结束再刷盘；active 也达到目标时，下一次写入在追加 WAL 前等待。连续 flush 可能延后自动压缩，首版不做公平调度或抢占。

## 锁边界与发布

持锁固定全部 SST 编号、Reader 顺序并预留输出编号。锁外独立打开输入 Reader，按旧到新交给 SsTableMerger，避免后台游标占用前台点查使用的 Reader。

FileOps.writeCompaction 局部适配为单次 Iterable。hasNext 缓存一次推进结果，next 消费它，不复制全库记录。checked IOException 仅在 Iterator 边界包装为 UncheckedIOException，Writer 失败清理后还原原始 IOException，并移交包装异常上的 suppressed 清理错误。

完整构建、force 和输出文件关闭成功后，关闭独立输入 Reader，再打开输出。发布锁内检查输入清单仍匹配，基于最新 Manifest 保留 active/frozen 及封存长度，仅替换 SST 清单。新状态与读取集在提交前构造，提交成功后只安装预构建引用。

```text
固定 SST=[3,5]，预留输出6
  → 锁外归并，期间前台轮换为 active=7、frozen=4
  → 发布 active=7、frozen=4、SST=[6]
  → 后续 flush 发布 SST=[6,8]
```

新输出发布前，前台继续使用旧 SST，并优先查 active/frozen，所以期间成功写入仍有内存/WAL 来源；输出编号不作为记录版本。

## 资源、失败与关闭

临时 Reader 取得后立即登记 owned。正常关闭后持锁移除；关闭报错时保留引用，供 store.close() 最终释放。关闭组中一个资源失败仍尝试其他资源，清理错误保留为 suppressed。

前台 get 完整持锁且不泄露 Reader。安装时旧 get 已结束，后续 get 使用新集，所以旧前台 Reader 可在安装后锁外关闭，无需通用版本引用计数。新 Manifest 已提交、独立输入及旧前台 Reader 都已完成关闭后，才尝试删除旧 SST；单个删除失败不撤销发布，也不妨碍其他删除。资源关闭错误则报告后台失败，已提交状态不回滚。

打开、读取、构建、关闭或提交失败会停止服务并唤醒背压等待者，后续操作和 close 报错。提交结果不确定时保留新旧文件，重开按有效 Manifest 恢复。若前台已失败，后台即使构建成功也不提交新状态。

close 标记 CLOSING、唤醒并 join 工作线程；已开始任务安全结束，既有 frozen 仍可刷盘，但不再开始新自动任务；尚未开始的手动请求明确失败并唤醒调用方，已开始的手动请求完成后通知。关闭可能留下达到阈值的有效表集，下次打开再触发。

## 验证与限制

新增 23 个自动压缩用例：默认/自定义/非法阈值、恢复与发布触发、重复压缩、空表/单表无操作、保留未刷 active、独立 Reader 点查、期间 WAL 轮换与背压、flush 优先、失败唤醒、构建/打开/关闭/提交故障、原错误及 suppressed、删除失败、安全关闭及续写重开。

另有 4 个独立 JVM 用例，Runtime.halt 跳过 finally/close：真实 Writer 写出大块但未完成文件、完整输出提交前、提交后、删除首个旧输入后。每例都在压缩期间先完成覆盖、删除、WAL 轮换与新 active 写入；父进程核对原始文件集和两份 WAL，重开校验三态、空键/值、二进制、大记录及续写。

`./mvnw test` 与 `./mvnw clean verify` 均通过，包含手动压缩验证后共 619 个用例、0 失败；`openspec validate --all --strict --no-interactive` 通过。

恢复先验证引用再清理孤儿；进程退出验证不等同断电实验。全量压缩有较高写放大，需要输入输出临时共存；单输出受既有 64 MiB 索引上限约束，超限明确失败，不自动拆表或回收 Tombstone。
