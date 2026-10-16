## Context

动机与范围见 proposal.md。本 change 起步时，WalStore 私有持有一个 MemTable 和 WalWriter，面向单调用线程；SsTableWriter 接收严格有序输入，SsTableReader 支持三态点查。MemTable.snapshot 复制但不冻结表，尚无文件集元信息、日志轮换或后台线程。

用户已确定采用后台刷盘：一个活跃表、最多一个冻结表、一个刷盘线程，冻结槽位忙且活跃表达到阈值时写入等待。本设计将其落到独立目录级入口，不改变旧组件的独立使用契约。除用户已选的并发模型外，下列格式、默认值和接口是本提案给出的具体方案，随对应实现小步讲解和验证。

当前已完成 Manifest、LsmStore 目录恢复与三态读写、WAL 轮换、单工作线程刷盘发布、背压、显式 flush 及关闭协调。学习笔记见 docs/manifest-codec.md、docs/manifest-file.md、docs/lsm-store.md 和 docs/lsm-background-flush.md。启动时孤儿清理已完成，组合进程退出验收与阶段复盘仍待完成。

## Goals / Non-Goals

**Goals:**
- 一个前台调用线程与一个后台工作线程协作，后台写 SSTable 的耗时不占用全局状态锁。
- 任意正常运行时最多有两代未发布的内存数据；文件发布前始终保留对应 WAL 引用。
- 已确认写入在进程异常退出后可恢复；文件错误与正常缺失明确区分。
- 可用确定性阻塞点和 I/O 故障测试验证切换、背压和失败唤醒。

**Non-Goals:**
- 不承诺多个前台线程并发调用，不引入跨进程文件锁。
- 不实现多冻结表队列、并行刷盘、压缩、全库 scan、MVCC 或断电一致性。
- 阈值是编码后的逻辑字节量，不宣称等于 Java 堆内存占用；不限制 WAL 累计历史大小。

## Decisions

### 1. 新入口与线程所有权

新增 LsmStore（AutoCloseable），拟定接口：

- `open(Path directory, long memTableTargetBytes)`：目录须存在，阈值为正；提供使用 4 MiB 逻辑目标的重载。
- `put(ByteKey, Value)`、`delete(ByteKey)`、`get(ByteKey): Optional<Entry>`。
- `flush()`：等待调用前未发布的数据全部成为正式 SSTable，空库不创建空表文件。
- `close()`：停止接收请求，等待已启动后台任务结束并关闭资源；不强制把剩余活跃表写成 SSTable，其 WAL 负责恢复。

当前实现 open(Path)、带正逻辑阈值的 open 重载、put/delete/get/flush/close，默认目标 4 MiB。前台串行，一个内部 worker 在 open 成功恢复后启动，恢复的 frozen 会自动刷盘。

前台 API 仍要求调用方串行使用，只新增内部后台并行。状态、MemTable 的访问、Manifest 提交、文件编号分配及查询均由同一锁串行化。后台仅在锁外构建冻结表对应的 SSTable，在锁内发布结果和唤醒等待者。读取器的 get/close 在锁内执行，避免修改现有 SsTableReader 的线程安全契约；不向调用方泄露内部表或 Reader。

单工作线程显式拥有任务生命周期，不使用无界队列。等待使用条件变量并在循环中重查状态；释放锁等待，避免工作线程无法发布。不得在持锁时 join 工作线程。关闭须等工作线程停止访问文件后才释放资源；即使失败也尝试关闭所有资源，清理错误 suppressed。

已具体使用 ReentrantLock/Condition，worker 在无 frozen 时等待，轮换只设置单槽位并 signal；后台取冻结表快照和分配输出编号时持锁，文件构建及独立输出 Reader 打开时不持锁。FileOps 追加 writeTable、commit、closeWal、walSize、deleteObsolete、newWorker，以及仅供测试观察条件等待的 beforeWait 阻塞点，不允许该阻塞点等待发布。

### 2. 写入、阈值与背压

活跃表大小取每个键最新记录的 `9 + keyLength + valueLength` 总和，Tombstone 值长度为零；覆盖时减旧记录再加新记录，使用 long 和溢出检查。最大单条记录沿用 WAL/SSTable 共同的 16 MiB 编码记录限制。

写入先验证参数，再在锁内进行容量协调。若当前活跃表已达到目标且冻结槽位忙，等待槽位释放后再接纳下一条操作，等待期间不追加该操作的 WAL。若已满且槽位空闲，先轮换。合法单条大记录允许越过目标，不拆分、不因低阈值拒绝；一次写入可使表达到目标加一条记录的规模。

之后完整追加并同步当前 WAL，再更新活跃表和逻辑大小。达到阈值且槽位空闲时，在返回前执行日志轮换并唤醒后台；若已有冻结表，则暂留已满活跃表，由下一次写入等待。覆盖和删除也遵守该背压规则，不通过特殊操作绕开上限。

因此一次写入可能等待 WAL 同步和短暂轮换，通常无需等待 SSTable 构建；后台跟不上时主动阻塞。轮换失败可能发生在本次记录已同步之后，调用失败的结果仍不确定，重启可能恢复该记录。

备选是同步刷盘或无界冻结表队列；前者写入停顿较长，后者在磁盘落后时内存持续增长。用户已选择单冻结槽位。

### 3. 文件命名与 Manifest 的内容

独占目录包含 `MANIFEST`、数字编号的 `.wal` 和 `.sst` 文件。只接受内部生成的正整数文件编号，不在元信息中保存任意路径。Manifest 的每个状态快照保存：递增提交序号、活跃 WAL 编号、可选冻结 WAL 编号及其封存字节长度，以及从旧到新的 SSTable 编号列表。封存长度使恢复能发现恰好发生在记录边界的冻结日志截断。至多两份有效 WAL；SSTable 的版本顺序以清单为准，不依据修改时间或目录遍历顺序。

新文件编号大于 Manifest 引用及本目录保留命名空间中所有现有编号，运行中单调递增，检查 long 溢出。扫描目录仅避免覆盖孤儿文件，不将其内容加入有效文件集；所有新文件使用 CREATE_NEW。后台输出使用独立分配的编号，其版本年龄由冻结代决定。

本阶段采用单个 Manifest 的追加式完整状态快照，避免每次通过替换 CURRENT/Manifest 文件引入额外指针协议。代价是每次提交复制文件列表且 Manifest 会增长；本阶段不回收历史元信息，不进行压缩。

### 4. Manifest 格式与提交

建议版本 1：大端序 Header=`MYMF`(4)+version(4)；后续为 `payloadLength(4)+CRC32C(4)+payload`。CRC 覆盖长度原始字节与 payload。payload 为 `revision(8)+activeWalId(8)+frozenWalId(8，0 表示无)+frozenWalLength(8)+sstCount(4)+sstId*(8)`，最小 36 字节、最大 16 MiB。无 frozen 时长度必须为 0，有 frozen 时长度至少容纳 WAL 文件头。编号为正、引用不重复，revision 从 1 开始严格连续；数量、长度、偏移及上限在分配前验证，精确消耗记录。文件列表顺序不要求编号递增。

首个状态要求没有 frozen 且 SSTable 列表为空；后续只允许两类转换：无 frozen → 新 active、原 active 成为带封存长度的 frozen、SSTable 列表不变；或有 frozen → active 不变、清除 frozen 及其长度、SSTable 列表原前缀加一个新编号。拒绝其余转换与编号复用；恢复流式验证历史转换，不要求已经失效的历史文件仍在磁盘上。

纯内存接口已具体化为不可变 `ManifestState(revision, activeWalId, frozenWalId, frozenWalLength, List<Long> sstIds)` 及包内 `ManifestCodec.encodeHeader/decodeHeader`、`encode(ManifestState): byte[]`、`decode(byte[]): ManifestState`。构造器复制列表并验证单态字段、引用唯一性和容量，requireInitial/requireSuccessor 另验历史位置与转换。新编号严格大于前一状态 maxFileId，合法历史中的上界不会下降，因而无需历史编号集合就能拒绝复用。查重采用独立 long[] 排序，不改变清单顺序。

调用参数非法抛 NullPointerException/IllegalArgumentException（含构造超容量状态）；decode 的非法长度、CRC、数量、状态字段抛 IOException，合法帧缺字节抛 EOFException。decode 不验证历史；后续恢复层须将 requireInitial/requireSuccessor 的语义失败转换为文件 IOException，并在构造超容量提交前报告容量 I/O 错误。因为 payload 为 36+8*N，最大合法载荷为 16 MiB-4，最大编号数 2,097,147；数量精确匹配载荷后才分配列表。

提交在状态锁内追加完整快照并 force，成功后才更新内存所采用的 Manifest 状态。短写循环，无进展或同步失败使实例 FAILED，不能继续提交或删除可能被引用的文件。完整有效记录即使先前 force 调用报错，重启也可以采用；失败不承诺回滚。

恢复按序校验所有完整记录，采用最后有效状态。不完整末尾前缀或合法长度下的残缺 payload 可以截去并同步；完整记录 CRC/语义错误、非法长度或不完整文件头均明确失败，不回退到更早状态伪装成功。已存在但没有任何完整状态的 Manifest 不能作为空库初始化。初始化仅面向空目录：先新建并同步初始 WAL，再建立并同步 Header 与首个状态；中断留下的未初始化目录拒绝打开，保留文件供显式处理，不静默覆盖。

Manifest 描述进程崩溃下的恢复来源；新目录项和文件同步的断电语义不在承诺内。

本步文件接口具体化为包内 `ManifestFile.create(Path, ManifestState)`、`open(Path)`、`append(ManifestState)`、`current()`、`close()`。create 先验证初始状态，再使用 CREATE_NEW；open 不带 CREATE。包内 ManifestIo 注入 size/read/write/truncate/force/close，默认代理 FileChannel。初始化失败关闭资源并保留文件，已有路径不改；打开失败不暴露实例，关闭错误 suppressed。参数/转换错误在 I/O 前拒绝，不使实例失效；追加开始后错误使实例 FAILED，current 也不能继续读取旧缓存，close 可重复且先置 CLOSED。

恢复逐帧读取，只保存前一有效状态，长度验证复用 codec 的 checkedFrameLength。完整帧错误带起始偏移，实际读取失败不当成残尾；没有首个有效状态时不截断。截尾后 force 成功才返回，正常完整恢复不重写文件。合法声明长度超过剩余字节时按残尾处理，这也可能包含某些长度字段损坏，不能承诺区分所有损坏和截断。本步不验证真实引用文件、不回收 WAL，也不提供线程锁；这些职责仍在后续目录层。

### 5. 日志切换：先记录两代，再接纳新写入

持锁，槽位为空且活跃表非空：

1. 为新的活跃表创建新 WAL，初始化并同步文件头。
2. 关闭旧 WAL writer，记录其封存字节长度，旧文件继续保留；关闭或取长度失败则停止服务。
3. 追加 Manifest 快照：旧 active 变为 frozen，新 WAL 变为 active，SSTable 清单保持不变。
4. 提交成功后将旧表视为冻结表，创建新活跃表并切换 writer，提交单个后台任务。
5. 释放锁后，前台才可能向新 WAL 追加记录。

冻结后旧表不得再修改。后台可在开始时取其有序快照并适配 SsTableRecord；快照有额外引用数组开销，但不会复制全量键值字节，也不会增加第三代可写表。

任何前置或提交失败都进入 FAILED，保存旧/新 WAL；不清理由于提交结果不确定而可能有效的文件。线程启动/任务唤醒失败同样记录并唤醒等待者，不让冻结表无任务且写入永久等待。

### 6. 后台发布：同一提交替换恢复来源

后台在锁外构建并同步新的 SSTable，随后打开 Reader，准备进入查询清单。此时冻结表与旧 WAL 仍有效，前台可继续更新新活跃表。

后台重新取锁，基于当前状态追加 Manifest：保留当前活跃 WAL，在 SSTable 清单末尾加入本次输出，同时清除 frozen WAL。只有提交成功后才安装 Reader、移除冻结表并唤醒等待写入者。安装所需资源和可分配对象应在提交前准备好；提交后的内存安装失败则停止服务，恢复以磁盘状态为准。

之后才尝试删除不再被最新状态引用的旧 WAL。删除失败留下孤儿文件，不撤销成功发布，不把文件重新加入清单。SSTable 构建/打开/发布失败则 FAILED，保留恢复所需 WAL，记录原错误并唤醒等待者；已完成但未发布的 SSTable 只是孤儿。

工作线程不得持有过期的整个快照覆盖前台状态。虽然单冻结槽位阻止第二次轮换，仍从持锁后的当前状态构造提交。

### 7. 读路径、显式 flush 与关闭

读顺序是 active → frozen（若有）→ SSTable 从新到旧。遇到 Value 或 Tombstone 均停止；仅 Optional.empty 才继续。文件错误使 LsmStore FAILED，不能跳过坏文件查旧值。不提供数据库级 scan，现有单文件 scan 也不直接作为多路合并接口。

flush 在串行前台调用中，先等待已有冻结任务发布；然后若活跃表非空，轮换并等待该任务发布；返回时调用前的数据均已进入清单。后台错误使等待者得到 IOException，不能只检查“任务结束”就当成功。等待中断报告 InterruptedIOException 并恢复中断标志；没有文件错误时不单因调用方中断而丢弃冻结数据或将服务判坏。

flush 已按两段 frozen 条件循环实现，复用 rotate 与 runWorker，不持锁等待文件构建或 join。共享 awaitFlushProgress 负责 Condition 等待、中断标志与生命周期检查，写入背压也使用此方法。空表和重复空 flush 不创建文件、不提交状态；返回只要求 SSTable 已发布，不要求旧 WAL 删除完成。两个等待位置的中断、两代构建/发布错误均用 latch 控制验证；已失败/关闭实例的新调用仍按既有状态异常拒绝。

close 进入 CLOSING，拒绝新调用，允许已启动 worker 完成发布，锁外等待终止后再关闭活跃 WAL、SSTable Reader、Manifest。关闭等待不因中断提前释放后台仍使用的资源，完成后恢复中断标志。正常关闭无需刷剩余 active，重启由 WAL 恢复。FAILED 也可关闭，重复关闭安全，关闭报错后不再可用。

关闭协调是接入线程后安全释放文件的必要前置，已在本步完成任务 6.3。后台错误保存并唤醒等待者，已有槽位等待抛 IOException 且携带原因，后续新调用按 FAILED 拒绝；close 也报告后台失败并聚合清理错误。前台读取/追加已报出的错误不会无条件作为新的后台错误再次报告。若前台先失败，worker 完成输出后不再发布，关闭尚未移交的 Reader 并保留 WAL；发布成功后的旧 WAL 删除 IOException/运行时异常仅留下孤儿，不撤销状态。

### 8. 恢复及崩溃窗口

先验证 Manifest，再验证所有引用文件存在；禁止用 WalWriter 的“缺失即新建”语义补出被引用但丢失的 WAL。独占目录假设下进行存在性和类型检查，再使用已有恢复器；恢复失败不暴露实例并关闭全部资源。Manifest 内引用的 SSTable 打开后沿用按需数据块校验。

本步实际命名为不补零的正十进制编号加 .wal/.sst，目录须存在，引用路径要求是普通文件且不接受内部符号链接。空目录先独占创建 1.wal 并同步头，再新建初始 Manifest；初始化中断保留产物。包内 FileOps 仅包装既有组件的创建/打开/追加，用于组合层错误注入；资源在工厂成功返回后由 LsmStore 统一持有，失败逆序关闭全部资源，原错误优先。

为复用 WAL 扫描而不重复格式，新加 WalWriter.openExistingRecovering（READ/WRITE，无 CREATE，拒绝零字节）和 replaySealed（READ，精确封存长度，不修尾）。二者与旧入口共享同一扫描器，旧 openEmpty/openRecovering 的行为不变。先打开所有 SSTable 元信息和严格重放 frozen，最后恢复 active，降低已知其他引用异常时修改活跃日志的机会。逻辑字节量在内存应用与重放时共同维护，覆盖先减旧记录，WAL 同步成功后才更新活跃映射和统计。

编号预留从当前状态与全部规范内部名称的最大值之后递增，超出 long 范围的保留名称拒绝打开，Long.MAX_VALUE 后分配失败；启动清理后本次运行仍保留扫描到的最大编号。SSTable 新旧顺序只取 Manifest，未知名称保留。此时 allocateFileId 仅包内预留，不生成文件。

若有 frozen WAL，分别重放 frozen 和 active 到独立表。frozen 是轮换前已同步关闭的日志，先要求文件大小精确等于 Manifest 中封存长度，再只读验证所有字节完整有效，不能按 active 的残尾规则修复；active 沿用现有 WAL 不完整尾部修复。打开成功前完成恢复，之后为 frozen 启动唯一后台任务。活跃表即使恢复后超过阈值也不拆出第三代，下一次写入按同一背压规则协调。

| 中断位置 | 采用的有效来源 |
| --- | --- |
| 新 WAL 已创建，轮换状态尚未完整记录 | 旧 active WAL；新 WAL 是孤儿，尚无成功用户写入 |
| 轮换状态完整，新内存尚未切换 | frozen WAL + 新 active WAL |
| SSTable 写入中或已完整，发布状态尚未完整记录 | frozen WAL + active WAL；忽略未引用 SSTable |
| 发布状态完整，旧 WAL 尚未删除 | 已发布 SSTable + active WAL；忽略旧 WAL |

启动清理在全部引用路径检查、SSTable 元信息打开和两代 WAL 恢复成功后、工作线程启动前执行。按完整文件名区分 WAL/SSTable 引用，仅删除规范命名且无引用的普通文件，不跟随符号链接、不递归目录；未知名称、目录和符号链接保留。初始化不完整或恢复失败时不清理。单个删除失败继续处理其他文件，目录枚举或关闭失败也不阻止打开；错误仅留下孤儿，不改变有效文件集，下次打开可重试。编号分配先记录全部现有编号，本次运行不因删除高编号孤儿而复用编号。SSTable 数据块仍按需验证，清理不额外全表校验，也不依据孤儿内容猜测恢复来源。

### 9. 分步实现与验证边界

先讲解并实现 Manifest 状态与编解码（纯内存），再实现提交/恢复 I/O，再实现目录恢复与只含前台的基本读写，然后接入单冻结槽位轮换与后台发布，最后验证背压、生命周期及进程退出。每个小步单独解释和提交，不一次实现全部任务。

测试使用 @TempDir、可控 I/O 代理和 latch，明确暂停在 WAL 切换、块输出、Manifest 发布等位置，不以 sleep 猜测调度。独立进程测试覆盖未正常关闭；不将它称为断电实验。

## Risks / Trade-offs

- [两个线程共享旧单线程组件] → 在上层串行化组件访问，后台构建使用独立输出资源，发布前准备 Reader。
- [后台落后或失败造成写入无限等待] → 限定冻结槽位，条件等待重查 FAILED/CLOSING，所有完成和错误路径唤醒。
- [逻辑阈值不是堆上限，快照和块编解码有额外开销] → 明确软目标、单条上限及额外索引/快照内存，不宣传恒定总内存。
- [Manifest 历史和 SSTable 文件数增长] → 每条元信息有上限并在提交前检查容量；本阶段明确不压缩，容量耗尽报错并保留 WAL。
- [过早删 WAL 或按目录猜有效文件] → 所有回收依据最新成功提交状态，结果不确定时停止服务并保留文件。
- [目录项断电丢失] → 本阶段只承诺已明确限定的进程退出恢复，不把 force 当成目录同步。

## Migration Plan

新增独立目录格式，不自动导入原有单文件 WalStore。保持旧测试与正式规范；本阶段设计放在 change 内，实现验证完成后再同步正式 specs。用户已确定并发模型，后续每步先解释字节示例/状态与验收，再按指示编写核心代码。
