# 同步手动压缩

`LsmStore.compact()` 与自动阈值触发共用 `mylsm-background`。调用线程负责 flush、登记请求和等待，文件归并仍由唯一后台线程执行，不增加执行线程或队列。

```java
try (LsmStore store = LsmStore.open(directory)) {
    store.put(key, value);
    store.compact(); // 成功后，调用前的数据全部位于唯一有效 SSTable。
}
```

没有数据时保持空 SST 清单；只有一张表时不重写、不分配压缩输出编号。保留最新 Value/Tombstone，与缺失仍可区分。

## 请求如何返回结果

```text
调用线程                                  唯一后台线程
flush：等已有 frozen，再刷出非空 active  →   处理 frozen 并发布
持锁登记 ManualCompaction
changed.signalAll()                    →   在任务边界检查请求
while (!request.done) await()               标记 started，固定全部输入
  等待时释放锁                             锁外归并，锁内提交并安装新读取集
                                           关闭旧 Reader，尝试回收旧文件
                                       ←   持锁写 done/error，signalAll()
重新持锁，检查请求结果并返回
```

请求从“待处理”变为“执行中”后仍保留在槽位中。只有成功完成整个流程、少于两张表的无操作结束，或明确失败时才登记结果并释放槽位。Manifest 发布本身会发出通知，调用方必须重新检查 `done`，不能把一次唤醒或请求已被取走当成完成。

`started`、`done`、`error` 和槽位全部受同一把锁保护，不需要 volatile。Condition 只负责通知，共享状态才表示进度；while 处理提前通知和虚假唤醒。

后台按 frozen flush → 手动请求 → 自动压缩选择下一项，已开始任务不抢占。若调用时自动压缩正在执行，先等它结束；随后 flush 调用前的内存数据，再登记手动请求。若自动任务已把全部数据合为一张，手动请求可以无操作成功。

## 中断、失败与关闭

- 等待中断抛 `InterruptedIOException` 并恢复中断标志；已提交的刷盘或压缩继续执行。若中断发生在初始 flush 等待中，尚未登记手动请求。
- 中断后再次调用，先 flush，再等待旧请求释放槽位，然后登记新请求；不会覆盖仍执行或待处理的请求。前台仍按原约定串行调用。
- 后台构建、打开、关闭或提交失败进入 FAILED，保存原因并唤醒请求等待者。手动调用抛 `IOException`，cause 保留原错误；后续操作拒绝，close 仍报告后台失败。
- 提交后关闭旧 Reader 失败也不能返回成功，已提交文件集不回滚。旧文件删除失败沿用孤儿策略，不撤销成功。
- close 拒绝新请求，将尚未开始的请求明确标记失败；已开始的手动任务安全执行到结束，close join 后才释放资源。

成功只说明此次全量整理完成；前台 API 的串行约定未扩展为通用并发支持。文件发布、恢复和资源所有权复用[自动压缩](lsm-automatic-compaction.md)的协议，没有新增磁盘格式。

## 验证

`LsmManualCompactionTest` 新增 15 个用例，使用 `@TempDir`、锁存器和有超时的等待，验证低于自动阈值时手动压缩、已有 frozen/active 先刷盘、空/单表无操作、等待到回收结束、自动任务交错、待处理/执行中请求中断后再次调用、初始 flush 中断、构建/提交/提交后关闭失败、待处理/执行中的请求与 close 协调，以及重开和续写。

手动和自动共用发布管线，进程退出窗口继续由 `LsmCompactionProcessTest` 的四个独立 JVM 用例覆盖。本步不增加 MVCC、分层压缩或 Tombstone 回收。

本步 `./mvnw test` 与 `./mvnw clean verify` 均通过，共 619 个用例，0 失败、0 错误；`openspec validate --all --strict --no-interactive` 通过。全量压缩写放大、输入输出临时共存、单输出索引上限及进程退出模型等限制与自动压缩相同。
