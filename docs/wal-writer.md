# WAL 文件初始化与同步追加

本笔记记录文件写入小步：WalWriter、WalIo 与 FileChannelWalIo 可以初始化日志文件、同步追加操作记录，并在 I/O 失败后停止写入。后续已补充恢复扫描与截尾，见 [恢复笔记](wal-recovery.md)；与 MemTable 的组合见 [存储组合笔记](wal-store.md)。

## 文件布局

文件头固定 8 字节，只写一次；后面顺序追加 WalCodec 编码的帧：

```text
4d 59 57 4c | 00 00 00 01 | 记录 1 | 记录 2 | ...
ASCII MYWL | 版本 1      |
```

文件头用来识别文件格式与版本，每条记录的长度和校验和用来识别记录边界与常见损坏，两者职责不同。

`WalWriter.openEmpty(Path)` 支持不存在、零字节或仅有合法文件头的日志；父目录须已存在。新文件头在返回前写完并执行 `force(true)`。已有非空文件先校验文件头，错误标识、未知版本或不完整文件头都导致失败且不修改内容。

openEmpty 对已有操作记录的日志仍拒绝打开，并保留原样。含记录的日志应使用新增的 openRecovering，先读取已有记录并修复不完整尾部，再开放追加。

## 完整写入与同步

```text
检查 OPEN 状态 → 编码参数校验 → 循环写完整帧 → force(true) → 推进追加偏移 → 返回
```

`FileChannel.write(buffer, offset)` 可能只写出 buffer 剩余内容的一部分，返回实际写入数，并推进 buffer 的 position。writer 必须使用更新后的 position 继续写，同时将文件偏移增加实际写入数。所有字节写完后才执行同步，不能每写一小段就算一次成功操作。

这里的两个 position 不同：ByteBuffer 的 position 指向内存中下一字节；传入 FileChannel 的 offset 指向文件中下一字节。按偏移写不会推进 FileChannel 自己的当前位置，因此由 writer 私有维护 appendOffset；单线程且独占文件是此设计的前提。

底层返回 0 时按“无进展”报 IOException，避免死循环。读取已有文件头也循环处理短读，并区分 EOF、无进展和真实 I/O 异常。

`force(true)` 请求文件内容和相关元数据同步。write 完成与 force 成功是两个边界，正常 close 不能替代每条记录的同步。本步通过测试验证同步发生在完整帧之后，不据此宣称所有设备上的绝对断电安全，尤其不保证新建文件父目录项的断电持久性。

## 失败状态

```text
OPEN ──追加或同步 IOException──→ FAILED
  │                               │
  └──────── close ────────────────┴──→ CLOSED
```

- null 或超限等编码错误发生在文件写入前，不改变文件，也不使 OPEN 失效。
- 追加或同步发生 IOException 后进入 FAILED，只允许 close，后续 append 得到状态异常。
- 不回滚失败写入，也不继续使用旧偏移：失败时可能已经写完了全部记录；留下半条记录则由重新打开时的恢复路径处理。
- close 先将状态改成 CLOSED，再关闭资源；即使关闭报错，实例也不可继续追加。重复关闭不再调用底层 close。
- openEmpty 在初始化或校验失败时会尝试关闭资源；若关闭又失败，将它保存在原异常的 suppressed 列表中，保留原始失败原因。

## 为什么有三个类

- WalWriter 负责文件头、写入顺序、同步边界与状态机。
- WalIo 是包内最小 I/O 接口，包含 size、按偏移 read/write、force 与 close；恢复小步新增 truncate。
- FileChannelWalIo 将这些操作转发给 JDK FileChannel，force 固定调用 `force(true)`。

测试用代理包装真实临时文件通道，限制一次读写字节数或在指定位置抛错。这样既验证真实文件内容，也能稳定验证短写、同步失败等正常运行时难以复现的分支，不增加 Mock 框架。

## 阅读与验证

建议先读 WalWriter 的 openEmpty、append、writeFully 和 close，再看 FileChannelWalIo，最后对照 WalWriterTest。

所有文件测试使用 `@TempDir`。覆盖文件头布局、连续追加、仅有头部的文件重开、非法文件不变性、已有完整或半条记录的拒绝、短读写、零进展、参数失败、初始化/追加/同步/读取/关闭故障及资源清理。

恢复扫描和尾部修复现已实现，见 [恢复笔记](wal-recovery.md)；组合后的 WalStore 见 [存储组合笔记](wal-store.md)。WAL change 保持未归档，待阶段复盘。
