# LSM 独立进程退出恢复验证

LsmStoreProcessTest 用独立 JVM 运行真实 LsmStore，在指定协议边界调用 `Runtime.halt(73)`，跳过 finally、close 和 shutdown hook。父进程核对退出码、Manifest 与遗留文件，然后重新打开数据库。退出码 73 表示到达指定边界，意外异常以 1 退出；超时会强制终止并回收子进程。

## 六个退出位置

先通过正常写入和 flush 生成旧 SSTable，再重开数据库，在新内存代中写入覆盖、删除、空值和二进制键。最后一条大记录跨过 8192 字节目标，触发轮换。这个数是测试的逻辑字节目标，不是文件、WAL 或堆内存上限；生产默认目标仍为 4 MiB。

| 退出位置 | 退出时的有效来源 | 重启验证 |
| --- | --- | --- |
| 轮换提交前，新 WAL 已准备好 | 旧 active WAL + 旧 SSTable | 新空 WAL 无引用，清理后仍从旧 active 恢复 |
| 轮换提交后，内存切换前 | frozen WAL + 新 active WAL + 旧 SSTable | 磁盘提交足以恢复两代，新 active 此时为空 |
| SSTable 写出部分数据块后 | 两代 WAL + 旧 SSTable | 未完成输出不能打开，启动清理它，仍从 frozen 恢复 |
| SSTable 完整且 Reader 已打开，发布提交前 | 两代 WAL + 旧 SSTable | 完整输出也尚无效，不能替代 frozen WAL |
| 发布提交后，内存安装与旧 WAL 删除前 | 新旧 SSTable + active WAL | 采用已提交 SSTable，清理旧 frozen WAL |
| 两代显式 flush 成功后 | 全部已发布 SSTable + 空 active WAL | 无冻结代，内存数据均能由正式 SSTable 恢复 |

前两个位置从前台轮换路径退出。后面的后台位置先用 CountDownLatch 暂停构建，让前台在新 active 中完成覆盖和删除，再允许后台继续到指定退出点。所有前台 API 仍串行调用，不通过并发前台操作绕过约定，不用 sleep 猜测线程调度。

部分 SSTable 场景通过包装真实 Writer 的输入迭代器，在已有数据块落入文件、索引与页脚尚未写出时 halt。因此测试会留下真实构建路径的半成品，没有让异常处理先删除它。发布前后则包装原有 FileOps.commit，在真实 Manifest 追加与同步前后退出；无需增加生产接口。

## 重启后检查的不变量

父进程先读取 Manifest、核对文件状态，再通过 latch 暂停重启后恢复的 frozen 刷盘，以便观察原恢复来源。检查完成后放行后台、flush 两代、继续写入，并再次重开，验证恢复后的数据库仍能使用。

- active 的新值覆盖 frozen 中的 Tombstone。
- active 的 Tombstone 覆盖 frozen 和旧 SSTable 中的值。
- 只有 frozen 保存的记录不丢失；没有 active 覆盖时，frozen 的 Tombstone 继续遮蔽旧 SSTable。
- 空 Value、Tombstone 和缺失保持不同状态，二进制键及大记录可恢复。
- 有效 WAL 在恢复时不被清理或改写，未发布输出和失效 WAL 不参与读取。

触发自动轮换的最后一条 put，在轮换提交前后退出场景中尚未向调用者返回，但其 WAL 已完整同步，因此重启可读到它。测试同时检查此前已成功返回的写入，不能把“尚未收到成功”解释为“恢复后一定不存在”。

## 验证范围

本小步新增 6 个独立进程用例；`./mvnw test` 与 `./mvnw clean verify` 均通过全项目 527 个测试，`openspec validate --all --strict --no-interactive` 通过全部 7 项验证。已有组件测试覆盖逐字节残尾、完整损坏、短读写及同步失败，已有组合测试覆盖背压、等待中断、后台失败唤醒和关闭协调。

这些用例验证协议边界上的进程直接退出：操作系统和文件系统仍在运行。它们没有模拟机器断电、新目录项丢失或所有可能的系统调用中途退出；当前也没有父目录 fsync 协议，不能据此承诺断电一致性。目录仍要求单实例独占，前台调用串行。

当前已完成最小 LSM 的实现与恢复验收；阶段复盘及规范同步归档仍待完成。压缩、全库 scan、Bloom Filter、MVCC 和通用多前台线程接口留给后续独立阶段。
