package io.github.flyzz.mylsm.wal;

import io.github.flyzz.mylsm.memtable.MemTable;
import io.github.flyzz.mylsm.memtable.TreeMapMemTable;
import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Entry;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 单线程、单实例独占 WAL 的键值存储；每次写入先同步日志，再更新私有内存表。
 * 不提供文件锁；调用方不得同时通过其他实例或进程访问同一文件。
 */
public final class WalStore implements AutoCloseable {
    private enum State { OPEN, FAILED, CLOSED }

    private final WalWriter writer;
    private final MemTable memTable;
    private State state = State.OPEN;

    private WalStore(WalWriter writer, MemTable memTable) {
        this.writer = writer;
        this.memTable = memTable;
    }

    /**
     * 初始化或恢复日志；恢复成功前不暴露临时内存表。
     * 父目录须已存在；完整损坏导致失败，残缺尾部修复规则见 WalWriter.openRecovering。
     *
     * @throws NullPointerException 如果 path 为 null
     * @throws IOException 如果文件初始化、恢复或修复失败
     */
    public static WalStore open(Path path) throws IOException {
        MemTable table = new TreeMapMemTable();
        WalWriter writer = WalWriter.openRecovering(path, record -> apply(table, record));
        return new WalStore(writer, table);
    }

    /** 包内故障注入入口，io 的所有权交给 WalWriter。 */
    static WalStore open(WalIo io) throws IOException {
        MemTable table = new TreeMapMemTable();
        WalWriter writer = WalWriter.openRecovering(io, record -> apply(table, record));
        return new WalStore(writer, table);
    }

    /**
     * 写入或覆盖；完整追加并同步日志后才更新内存并返回。
     *
     * @throws IllegalStateException 如果实例已失败或关闭
     * @throws NullPointerException 如果 key 或 value 为 null
     * @throws IllegalArgumentException 如果记录载荷超过 16 MiB；参数错误不使实例失效
     * @throws IOException 如果追加或同步失败；失败结果不确定，实例之后仅允许关闭
     */
    public void put(ByteKey key, Value value) throws IOException {
        requireOpen();
        appendAndApply(new WalRecord(key, value));
    }

    /**
     * 保存删除标记，包括当前不存在的键；成功边界与 put 相同。
     *
     * @throws IllegalStateException 如果实例已失败或关闭
     * @throws NullPointerException 如果 key 为 null
     * @throws IllegalArgumentException 如果记录载荷超过 16 MiB；参数错误不使实例失效
     * @throws IOException 如果追加或同步失败；失败结果不确定，实例之后仅允许关闭
     */
    public void delete(ByteKey key) throws IOException {
        requireOpen();
        appendAndApply(new WalRecord(key, Tombstone.INSTANCE));
    }

    /**
     * 返回当前值或删除标记；Optional.empty() 表示没有该键的记录。
     *
     * @throws IllegalStateException 如果实例已失败或关闭
     * @throws NullPointerException 如果 key 为 null
     */
    public Optional<Entry> get(ByteKey key) {
        requireOpen();
        return memTable.get(key);
    }

    private void appendAndApply(WalRecord record) throws IOException {
        try {
            writer.append(record); // encode 的参数异常不进入 FAILED。
        } catch (IOException failure) {
            state = State.FAILED;
            throw failure;
        }
        try {
            apply(memTable, record);
        } catch (RuntimeException | Error failure) {
            // 日志已同步，但内存更新未能完成，不能继续提供可能过时的查询。
            state = State.FAILED;
            throw failure;
        }
    }

    /** 恢复与正常写入共用的纯内存路径，不追加 WAL。 */
    private static void apply(MemTable table, WalRecord record) {
        if (record.entry() instanceof Value value) {
            table.put(record.key(), value);
        } else {
            table.delete(record.key());
        }
    }

    /** 重复关闭安全；关闭报错后仍拒绝读写。 */
    @Override
    public void close() throws IOException {
        if (state != State.CLOSED) {
            state = State.CLOSED;
            writer.close();
        }
    }

    private void requireOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException("WAL store is " + state);
        }
    }
}
