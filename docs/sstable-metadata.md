# SSTable 第二步：文件头、索引与 Footer

本步新增包内 `SsTableMetadataCodec`，将文件布局转换为可验证的字节格式。它只在内存里编码和解析元信息，不打开文件、不提供点查，也不验证实际数据块的内容。

## 三种元信息各自负责什么

```text
Header (8) | DataBlock* | IndexBlock | Footer (24)
```

Header 的 `MYST + version=1` 用于识别文件格式。索引保存每个数据块的最大键、文件起点与完整帧长度。Footer 放在末尾固定 24 字节，记录索引位置和长度，使读取者可以先找到索引，再找到数据块。

当前索引解码为不可修改的 `List<IndexEntry>`，每项包含不可变 ByteKey 和两个数值字段。用户讨论过紧凑字节表示后选择恢复简单方案，本步不加入索引记录偏移数组。

## 索引的格式与长度

```text
payloadLength (4) | checksum (4) | payload
payload = blockCount (4) | indexEntry*
indexEntry = maxKeyLength (4) | maxKey | offset (8) | frameLength (4)
```

整数采用大端序。索引项因为 maxKey 变长，长度也变长；解码时先验证数量，再逐项解析为对象。CRC32C 覆盖原始 payloadLength 四字节和完整 payload，不包含 checksum 自身。

`offset` 是数据块在整个文件中的起点；`frameLength` 包含数据块自己的 8 字节前缀。索引载荷上限为 64 MiB，完整索引帧最多 64 MiB + 8 字节。每项最少 16 字节，在分配列表前用剩余字节约束 blockCount，不能直接相信文件给出的数量。

最大键还受数据记录格式约束：键长不能超过 16 MiB - 9，而且它至少要能放进指向的数据块。最小数据块是 21 字节，保存一个空键和空值或删除标记；若最大键长为 K，对应块至少需要 `21 + K` 字节。

## 用两个真实数据块推算偏移

假设第一个块只有 `k → A`，第二个块只有删除 z：

| 区域 | 起点 | 长度 | 终点（不包含） |
| --- | --- | --- | --- |
| Header | 0 | 8 | 8 |
| 数据块：k → A | 8 | 23 | 31 |
| 数据块：删除 z | 31 | 22 | 53 |
| 索引 | 53 | 46 | 99 |
| Footer | 99 | 24 | 123 |

索引条目为：

```text
maxKey=k, offset=8,  frameLength=23
maxKey=z, offset=31, frameLength=22
```

两个键各 1 字节，所以索引 payload 为 `4 + 2 × (16 + 1) = 38` 字节，加上帧前缀共 46 字节。Footer 因此记录 `indexOffset=53, indexLength=46`。

读取者用文件大小 123 找到 Footer 起点 99，解析出索引位置后读取区间 `[53, 99)`。本步测试在内存数组中实际组装和定位这些部分，没有模拟成已经实现文件 Reader。

## Footer 格式

```text
indexOffset (8) | indexLength (4) | version (4) | magic "MYFT" (4) | checksum (4)
```

CRC32C 覆盖前 20 字节。indexLength 包含完整索引帧前缀。解码 Footer 还需要调用方提供 fileSize，才能检查索引恰好结束于 Footer 起点，而不是只检查字段自身。

空表没有数据块，索引仅有 4 字节零计数和 8 字节前缀，因此文件总长为 `8 + 12 + 24 = 44` 字节。Footer 记录 `indexOffset=8, indexLength=12`。空索引如果声称数据区还有字节，必须拒绝。

## 位置检查为什么比 CRC 更进一步

即使 CRC 正确，也必须验证：

1. 第一块从偏移 8 开始，所有块区间连续，无空洞或重叠。
2. 每块长度合法，块最大键严格递增。
3. 最后一块结束于 indexOffset。
4. 索引长度合法，索引终点恰在 fileSize - 24。
5. 所有字节被精确消耗，无额外尾随数据。

数据区边界采用先比较 `frameLength <= indexOffset - expectedOffset`、再推进偏移的方式。Footer 使用 `indexLength == footerOffset - indexOffset`，同时防止生成无法用 long 表示文件总长的描述。这样损坏的巨大 offset 不能通过加法溢出绕过检查。

文件偏移采用 long，索引帧长度采用 int。本步用 129 个最大块描述验证超过 2 GiB 的偏移，无须真正创建大文件；另验证 Footer 对 long 边界的处理。

元信息只能确认它描述的范围自洽，不能证明数据块 CRC 正确或其中最大键真的等于索引键。后者仍需后续 Reader 在访问数据块时检查。

## 错误契约与验证

编码时 null 抛 NullPointerException，非法布局抛 IllegalArgumentException，索引容量超过 64 MiB 抛 IOException。解码时非法 magic、版本、CRC、计数、长度和布局均抛 IOException；固定结构不足或合法声明长度下缺字节抛 EOFException，不赋予尾部修复语义。

`./mvnw test` 通过：本步新增 33 个用例，全项目共 281 个。覆盖独立逐位 CRC32C 生成的固定样例、空表和双块内存组装、变长二进制键、输入与结果隔离、逐字节截断与修改、重算 CRC 后的结构错误、索引容量恰好达到上限和超出 1 字节，以及大偏移与溢出检查。

下一步才由 Writer 把这些组成部分写到新文件，并处理分块、短写、同步、关闭与失败清理。
