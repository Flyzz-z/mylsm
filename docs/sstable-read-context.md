# 共享 Reader 与独立读 context

用户指出文件资源和索引可以只加载一次，每次可暂停游标及扫描应有自己的读 context。本步复用 Cursor 类型表达 context，Reader 共享资源，游标持有独立读取进度；scan 使用自己的 Cursor 循环推进。数据库级范围扫描及跨表一致性视图仍留在后续设计。

```text
SsTableReader
  ├─ input：共享文件资源，读取明确指定偏移
  ├─ index：只加载一次的不可变索引
  ├─ state：OPEN / FAILED / CLOSED
  └─ 生命周期读写锁
       ├─ Cursor A：自己的 records / nextBlock / recordIndex / closed
       ├─ Cursor B：自己的 records / nextBlock / recordIndex / closed
       └─ scan：内部创建 Cursor C，向回调传递不可变记录
```

get 已使用方法局部的定位结果和块缓冲，不额外分配 context 对象。Reader 不再保存 scanning 标志或“唯一当前 cursor”，不同游标、扫描和点查可以交错或并行。每个 Cursor 仍由一个调用线程使用，不能让两个线程同时推进同一个游标。

## 锁保留在哪里

| 操作 | Reader 的锁 | 原因 |
| --- | --- | --- |
| get | 全程共享读锁 | 防止读取中关闭文件，保护状态检查与 I/O 的生命周期边界 |
| openCursor | 短暂共享读锁 | 创建时确认资源仍可用，不在游标整个生命周期持锁 |
| Cursor.next / record | 每次操作共享读锁 | 防止操作与文件关闭交错，失效后不能返回缓存位置 |
| Cursor.close | 不取得 Reader 锁 | 只清理本 context 的私有缓冲和位置，不操作共享资源 |
| Reader.close | 独占写锁 | 等当前读操作退出，设置 CLOSED 并关闭共享文件 |
| scan 回调 | 不持 Reader 锁 | 处理用户代码时不阻塞关闭，可启动其他独立读取 |

Reader 锁仍有必要：即使进度独立，文件关闭和共享失败状态仍需协调。读取之间共享读锁，可以同时进入底层 I/O；文件关闭取得写锁前等待当前操作退出。FAILED 保持 volatile，并行读只会单向设置 FAILED，关闭等待读者退出后再写 CLOSED，避免互相覆盖。

本步的 context 不引用计数，也不延长文件句柄寿命：Reader 关闭后，暂停游标的 next/record 立即拒绝，不要求先关闭所有游标。各游标保存的局部缓冲仍由其 close 释放，包括 Reader 已关闭、失败和游标耗尽后的清理；调用方应使用 try-with-resources。

## 扫描复用游标

```java
try (Cursor context = openCursor()) {
    Objects.requireNonNull(consumer, "consumer");
    while (context.next()) {
        consumer.accept(context.record());
    }
}
```

每个块仍在完整校验后才产生记录，按需读下一块，不预取整个文件。next/record 返回前已经释放生命周期读锁，所以 consumer.accept 在锁外执行。回调可以点查、打开另一个游标或启动独立 scan，不会改变原扫描进度。

回调抛异常时，try-with-resources 只释放本次扫描的 context，不关闭 Reader，不清理其他游标。若回调或其他线程关闭 Reader，关闭可安全完成，本次扫描下一次访问明确失败，不把失效误报成正常耗尽。文件损坏则传播原 IOException，并使所有 context 后续操作拒绝；已经交给调用方的不可变记录不回滚。

归并器同样只拥有自己创建的 context，调用方可保留其他读取进度。相同 Reader 重复作为归并输入仍按原契约拒绝，现由参数检查显式完成，避免依赖“只能有一个游标”的旧限制。

## 验证与范围

调整原有独占游标、扫描重入与归并占用用例，新增 6 个测试：scan/游标/get 的真实块读取重叠、当前 next 阻塞关闭而暂停 context 不阻塞关闭、一个 context 文件错误使全部缓存位置失效、回调错误只释放自身、回调暂停时关闭可完成、归并不影响调用方已有游标。空表、按需跨块、校验、三态及关闭失败等既有测试继续保留。

./mvnw test 与 ./mvnw clean verify 通过 638 个测试，0 失败、0 错误；严格 OpenSpec 校验通过。共享索引和句柄减少重复打开工作，每个 context 仍有一个已解码块及读取临时分配，内存随活跃 context 数增长。

LsmStore 仍保持[读写锁并发点查](concurrent-point-reads.md)的边界，后台仍用独立 Reader 构建；本步没有把 store 点查移到锁外，没有引入文件集引用计数、范围 API 或 MVCC。
