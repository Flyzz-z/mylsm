## Context

动机与范围见 proposal.md。ByteKey 已定义无符号字节字典序；Entry 区分 Value 与 Tombstone；MemTable.snapshot 提供每键唯一的有序记录。当前尚无 SSTable 磁盘格式，WalStore 无须在本阶段改变。

本文是阶段启动时的具体设计提案。用户已指示开始 SSTable 阶段；以下布局和接口是助手提出的起点，具体格式、资源上限与错误语义需要在第一个实现步骤前结合示例共同讨论，不把阶段启动等同于逐项设计决策已完成。

## Goals / Non-Goals

**Goals:**
- 单线程、单文件、按序构建、只读打开，以有界数据块实现点查和顺序扫描。
- 所有键、值与文件偏移均有明确解释；解析前验证长度和范围。
- 成功写入的文件可以关闭后重新打开，错误不能伪装成正常缺失。

**Non-Goals:**
- 无通用存储引擎接口、文件集发布协议、目录持久性保证或自动清理崩溃遗留文件。
- 无前缀压缩、块内偏移索引、压缩算法或跨文件版本号。
- 不修改输入顺序，不替调用方排序或消除重复键。

## Decisions

### 1. 数据流与不变量

```text
write：按序输入 → 校验记录与顺序 → 累积数据块 → 写块并记录索引
       → 写索引 → 写 Footer → force(true) → 关闭文件 → 成功
open：检查文件头和 Footer → 校验索引及块区间 → 返回只读实例
get：在索引中找首个 maxKey >= key 的块 → 读取校验 → 块内查找
scan：按文件顺序逐块读取校验 → 按键顺序交给回调
```

- 记录按 ByteKey 自然顺序严格递增；重复键与逆序输入均拒绝。
- 空键、空 Value、Tombstone 分别保留，不将删除转换成缺失。
- 数据块非空且互不重叠，覆盖文件头后到索引前的连续区域。
- 索引的 maxKey 严格递增，每项精确指向一个数据块。
- 已完成文件只读；调用方须独占构建目标，读取期间不得由外部修改文件。

### 2. 二进制布局提案

所有整数均采用大端序，offset 使用非负 long，长度与数量使用经校验的非负 int。

```text
Header (8) | DataBlock* | IndexBlock | Footer (24)
Header = magic "MYST" (4) | version 1 (4)
Footer = indexOffset (8) | indexLength (4) | version 1 (4)
         | magic "MYFT" (4) | checksum (4)
```

Footer 的 checksum 使用 CRC32C，覆盖前 20 字节；indexLength 包含索引帧前缀。固定 Footer 允许从文件末尾直接找到索引，不必扫描数据。Header 与 Footer 均检查 magic 和版本，不支持的版本明确拒绝。

数据块和索引块共用帧外壳：

```text
payloadLength (4) | checksum (4) | payload
checksum = CRC32C(原始 payloadLength 四字节 + payload)
```

数据块 payload：

```text
recordCount (4) | record*
record = kind (1) | keyLength (4) | valueLength (4) | key | value
kind：1 = Value，2 = Tombstone；Tombstone 的 valueLength 必须为 0
```

单条 record 最大 16 MiB（包含 9 字节记录头），与当前 WAL 的载荷上限对齐，但不复用 WalCodec：SSTable 保存状态，WAL 保存操作历史，后续演进应独立。数据块 payload 最大 16 MiB + 4 字节。记录长度计算先使用 long，验证后才分配数组；解析必须精确消耗块，不接受尾随字节或与数量不符的内容。

索引块 payload：

```text
blockCount (4) | indexEntry*
indexEntry = maxKeyLength (4) | maxKey | offset (8) | frameLength (4)
```

索引 payload 上限建议为 64 MiB。写入超过上限时明确失败；读取在分配前验证上限、文件边界和数量是否可能容纳于载荷。所有块区间须连续，首块从偏移 8 开始，末块恰好结束于 indexOffset，索引结束紧邻 Footer；使用差值或宽类型计算避免溢出。空表含零项索引且没有数据块，最小文件为 8 + 12 + 24 = 44 字节。

选择理由：显式数量和长度便于学习格式边界及检错；块级 CRC 避免每条记录单独存校验；固定 Footer 让索引可以放在写入末尾，无须回写 Header。暂不引入可变长整数、共享前缀或块内偏移表。

### 3. 分块与索引选择

普通块以 4 KiB payload 为目标；追加下一条记录会超过目标且当前块非空时，先输出当前块。记录不跨块；一条合法的大记录允许独占一个超过目标的块，但仍受最大块长度限制。4 KiB 是学习用分块目标，不宣称与文件系统块或设备 I/O 严格对齐。

索引使用每块实际最大键，点查通过二分找到首个 maxKey >= key 的块。若不存在这样的项，可直接返回缺失。块内先顺序解析并验证记录，再查找目标；暂不添加块内二分所需的偏移数组。

备选：逐条索引容易定位，但内存随记录数增长；首键索引需要寻找最后一个 firstKey <= key，并额外处理最小键边界。最大键索引与当前讲解示例一致。

### 4. 打开验证与按需数据校验

open 检查 Header、Footer、索引 CRC、精确布局、递增 maxKey 和块长度上限，不预读全部数据块。get/scan 在读取块时验证帧长度、CRC、记录语义、块内严格顺序、最后一键等于该项 maxKey，以及首键大于上一项 maxKey。

这是按需检测：未读取的数据块可能尚未验证；get 查不到索引候选时也不会检查无关块。不能声称 open 成功即全文件数据无损，或单次查询能够发现任意位置损坏。完整 scan 可用于检查全部数据块。CRC 不能防止恶意重写并重算校验，也不能保证检测所有位损坏。

完整文件的截断和已访问区域的损坏均报 IOException，不沿用 WAL 的尾部修复策略，不修改或跳过坏块。读取过程中出现 I/O/格式错误后实例进入 FAILED，仅允许 close，避免调用方继续使用已知异常文件。回调自己抛出的运行时异常终止该次 scan，但不将合法文件判坏；已产生的回调副作用不回滚。

### 5. 接口与生命周期提案

- `SsTableRecord(ByteKey key, Entry entry)`：不可变配对，拒绝 null；暂不移动已有 MemTableRecord，避免为新阶段重构旧 API。
- 包内 `SsTableBlockCodec`：编码/解码一个完整数据块，返回独立数组或不可修改的记录列表；这是第一个实现小步，无文件 I/O。
- `SsTableWriter.write(Path, Iterable<SsTableRecord>)`：一次性构建新文件，调用方提供已排序输入，方法完成同步和关闭后才成功返回。
- `SsTableReader.open(Path)`、`get(ByteKey): Optional<Entry>`、`scan(Consumer<SsTableRecord>)`、`close()`：只读、AutoCloseable；scan 按序逐块读取，不汇集整张表到 List。

Writer 使用 CREATE_NEW，拒绝覆盖任何已有文件；父目录须存在。创建后失败时关闭并尝试删除本次新建文件，清理失败保留为 suppressed 异常，不掩盖主错误。不删除已有目标。进程崩溃仍可能遗留半成品，成功文件发布与崩溃清理属于后续文件集设计，不能仅凭文件存在就认定已发布。force 和 close 成功也不承诺父目录项的断电持久性。

非法调用参数/输入顺序/记录超限以 NullPointerException 或 IllegalArgumentException 表达；文件错误、编码内容损坏及索引容量超限以 IOException 表达。Reader 状态为 OPEN、FAILED、CLOSED，关闭可重复，失败或关闭后拒绝 get/scan。打开失败须释放已获取资源，关闭失败也不得恢复使用。scan 回调不得关闭、重入 reader 或修改同一文件。

首个代码步骤将数据块接口具体化为包内静态 `encode(List<SsTableRecord>): byte[]` 与 `decode(byte[]): List<SsTableRecord>`。encode 要求非空且严格递增，返回独立帧数组，不修改输入；null 抛 NullPointerException，空块、顺序错误和超限抛 IllegalArgumentException。decode 只接受恰好一个完整非空块，先验证长度和 CRC，再验证数量、每条结构及顺序，全部成功后返回不可修改的列表，不保留输入数组。前缀不足或合法长度下缺失帧字节抛 EOFException；其他结构错误抛 IOException。EOFException 仅说明字节不足，不意味着 SSTable 可以像 WAL 一样截尾修复。当前最小数据块 payload 为 4 字节计数加 9 字节空键/空值记录，共 13 字节。空表应没有数据块，不编码零记录的数据块。编解码不执行 4 KiB 分块，也不验证尚未实现的跨块索引边界。

元信息步骤新增包内 `SsTableMetadataCodec`，包含不可变描述符 `IndexEntry(ByteKey maxKey, long offset, int frameLength)` 与 `Footer(long indexOffset, int indexLength)`。接口为 `encodeHeader()/decodeHeader(byte[])`、`encodeIndex(List<IndexEntry>, long indexOffset)/decodeIndex(byte[], long indexOffset)` 和 `encodeFooter(Footer)/decodeFooter(byte[], long fileSize)`。索引编码的非法输入布局抛 IllegalArgumentException，容量超限抛 IOException；所有解码中的非法字段及布局抛 IOException，合法格式字节不足抛 EOFException；null 抛 NullPointerException。

索引解码返回不可修改的对象列表，键为独立的不可变 ByteKey，沿用用户撤回紧凑索引优化后的方案。计数须先验证不超过 `(payloadLength - 4) / 16`；maxKeyLength 须非负且不超过单条记录所能容纳的最大键长度 `16 MiB - 9`，还须能装入对应块。块长度范围为 `21..16 MiB + 12` 字节，并至少能容纳 21 字节固定开销与该最大键。连续性从偏移 8 开始检查，每个长度先与 `indexOffset - expectedOffset` 比较，再推进 expectedOffset，因此不会让相加溢出绕过检查；空索引只允许 indexOffset=8。Footer 校验索引帧长度为 `12..64 MiB + 8`、文件总长可用 long 表示、索引终点恰在 `fileSize - 24`。元信息校验不访问实际数据块，最大键是否确实匹配数据块内容仍由后续 Reader 读取块时验证。本步只在内存中组装完整字节样例，不引入 Writer、Reader 或文件 I/O 故障注入。

### 6. 需要用户参与的设计点与实现顺序

先结合具体记录解释：块级校验和每块最大键索引、4 KiB 目标与大记录独占块、按需数据校验、一次性 CREATE_NEW 写入。它们是具体提案，尚未在 tasks 中标记为用户已逐项确定。

实现分为：记录与数据块编解码 → 完整文件构建 → 索引读取与点查 → 有序扫描与错误路径 → 复盘归档。每步前明确接口与验收示例，每步后解释测试结果，留出讨论空间，不一次性完成全部步骤。

## Risks / Trade-offs

- [索引和数据块解析需要内存] → 固定上限并在分配前检查；内存仍随块数和最大块大小增长，不承诺常数总内存。
- [索引较大时单帧构建存在额外数组复制] → 优先可解释的格式；64 MiB 上限明确限制本阶段单文件规模。
- [惰性校验不能在 open 时发现所有损坏] → 明确 get/scan 检测范围，并测试未访问块与已访问块的区别。
- [直接写新路径会遗留崩溃半成品] → 当前由调用方只使用成功返回的产物；发布协议等待最小 LSM 阶段。
- [块最大键可能较长，索引膨胀] → 本阶段保存完整键；索引键缩短或多级索引需另行讨论。

## Migration Plan

新增独立组件，不改变既有 WAL 文件或存储入口。先建立并验证 change，按小步提交实现；阶段验收和学习复盘完成后更新 README、路线图、学习笔记并归档。阶段未完成时不更新正式 specs 宣称能力已实现。
