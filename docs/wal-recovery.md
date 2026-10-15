# WAL 恢复扫描与尾部修复

本小步提供 `WalWriter.openRecovering(Path, Consumer<WalRecord>)`：读取并验证已有记录，逐条交给回调，必要时修复残缺尾部，然后返回可继续追加的 writer。`openEmpty` 保留只打开空日志的契约。后续已补充 MemTable 组合，见 [存储组合笔记](wal-store.md)。

## 数据流与偏移

```text
初始化或校验文件头
  → 读满 8 字节记录前缀
  → 检查载荷长度合法、剩余文件字节足够
  → 读完整帧
  → WalCodec.decode：CRC32C → 类型与长度结构 → K/V
  → replay.accept(record)
  → 推进 validEnd，继续下一条
  → 若尾部残缺，truncate(validEnd) → force(true)
  → 返回从 validEnd 追加的 writer
```

`validEnd` 是最后一条完整有效记录的结束位置，初始为 8。`readFully` 内的 offset 是实际读取游标，随每次短读前进。读到了字节不代表它们已被验证；只有完整记录解码并交给回调后才推进 validEnd。所有文件偏移使用 long。

例如 `PUT(k, A)` 的帧长 19 字节，`DELETE(k)` 的帧长 18 字节：

```text
0        8                 27                45
| 文件头 | PUT(k, A)        | DELETE(k)        | 残缺记录
                                             ↑ validEnd
```

此时修复到 45；修复同步成功后，新记录也从 45 写起，避免残缺字节挡住下一次扫描。

扫描开始时取得文件大小。在独占文件的前提下，恰好到达记录边界即正常结束；剩余不足 8 字节是残缺前缀；前缀完整且长度合法，但载荷字节不足，是残缺载荷。若文件大小表明字节足够，底层读取却提前返回 EOF、零进展或抛出异常，则打开失败，不能把它当成残缺尾部修复。

## 先读取，再验证，再解释

三种长度字段沿用原格式：

```text
payloadLength | checksum | operation | keyLength | valueLength | key | value
payloadLength = 9 + keyLength + valueLength
```

它们存在信息冗余，但外层长度让扫描器无需先理解 K/V 布局即可读取完整帧。长度必须先通过 9..16 MiB 的范围检查，且剩余字节足够，才能分配帧内存。WalCodec 拿到完整帧后才校验 CRC，再检查操作与键值长度结构。PUT 构造 Value，DELETE 要求 valueLength 为零并构造 Tombstone。

完整前缀长度非法，或完整帧 CRC / 结构非法，都报告记录起始偏移并拒绝打开；即使坏帧在文件末尾也不截断。扫描不跳过坏记录寻找后续帧。

CRC 不能在尚未读完整帧时验证。若长度被改成范围内的大数且超过剩余字节，会被当成残缺载荷；本格式无法完全区分这种损坏与真实截断。测试保留了这个限制的示例，不把尾部修复称作任意损坏修复。

## 回调与失败边界

扫描器每确认一条记录，就调用一次 `replay.accept(record)`。它不收集全部历史，内存主要受单条记录上限约束；若调用方主动将记录保存到 List，仍需承担整份历史的内存开销。

```java
// 用于观察记录顺序；WalStore 将回调接到临时 MemTable。
try (WalWriter writer = WalWriter.openRecovering(path, record -> {
    System.out.println(record);
})) {
    writer.append(nextRecord);
}
```

回调必须只修改尚未对外暴露的临时状态，不得修改或重新打开同一日志。后面的记录损坏、回调自身抛出异常，或者截尾失败时，之前的回调调用不会被撤销。WalStore 在打开失败时不暴露临时 MemTable。

扫描、回调、截断或同步失败都不返回可写 writer，并尝试关闭资源；关闭失败以 suppressed 附在原异常上。可检测损坏不会修改文件；截断或修复同步失败可能已经改变尾部，不保证回滚。

## 验证与下一步

WalRecoveryTest 使用 `@TempDir` 和真实文件，覆盖顺序重放、二进制与空值、短读、首条残缺记录、PUT/DELETE 尾部的每个字节截断、记录边界、修复同步后追加并再次恢复、完整损坏、非法长度及最大载荷。通过代理真实文件通道验证读取、截断、同步和回调失败时的资源清理，以及关闭失败不会遮蔽原始异常。

关键不变量是：完整记录验证前不交给回调，确认有效前不推进有效边界，残缺尾部截断并同步成功前不开放追加。

后续已实现 WalStore：成功写入先 WAL 同步再更新 MemTable，打开时重放到新的内存表，见 [存储组合笔记](wal-store.md)。当前 change 保持进行中，待阶段复盘和归档。
