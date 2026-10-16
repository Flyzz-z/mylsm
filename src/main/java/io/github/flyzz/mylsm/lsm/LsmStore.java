package io.github.flyzz.mylsm.lsm;

import io.github.flyzz.mylsm.memtable.MemTable;
import io.github.flyzz.mylsm.memtable.TreeMapMemTable;
import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Entry;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;
import io.github.flyzz.mylsm.sstable.SsTableReader;
import io.github.flyzz.mylsm.wal.WalCodec;
import io.github.flyzz.mylsm.wal.WalRecord;
import io.github.flyzz.mylsm.wal.WalWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/**
 * 目录级存储的基础读写与恢复入口，单线程、单实例独占目录。
 * 当前实现尚不轮换或后台刷盘；恢复的冻结表只读保留，活跃写入继续由 WAL 持久化。
 */
public final class LsmStore implements AutoCloseable {
    private static final Pattern INTERNAL_FILE = Pattern.compile("([1-9][0-9]*)\\.(wal|sst)");
    private static final FileOps FILES = new FileOps();
    private enum State { OPEN, FAILED, CLOSED }

    private final WalWriter writer;
    private final MemoryTable active;
    private final MemoryTable frozen;
    private final List<SsTableReader> tables; // 从旧到新。
    private final List<AutoCloseable> owned;
    private final FileOps files;
    private long maxFileId;
    private State state = State.OPEN;

    private LsmStore(WalWriter writer, MemoryTable active, MemoryTable frozen,
                     List<SsTableReader> tables, List<AutoCloseable> owned, FileOps files, long maxFileId) {
        this.writer = writer;
        this.active = active;
        this.frozen = frozen;
        this.tables = List.copyOf(tables);
        this.owned = List.copyOf(owned);
        this.files = files;
        this.maxFileId = maxFileId;
    }

    /**
     * 已有空目录可初始化；已有数据库按最新有效 Manifest 恢复。
     * 缺失引用、残缺文件头、冻结 WAL 长度不符或可检测损坏均失败，不返回部分实例。
     * 不提供文件锁；调用方不得同时通过其他实例或进程访问目录。
     */
    public static LsmStore open(Path directory) throws IOException {
        return open(directory, FILES);
    }

    static LsmStore open(Path directory, FileOps files) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(files, "files");
        if (!Files.readAttributes(directory, BasicFileAttributes.class).isDirectory()) {
            throw new IOException("LSM path is not a directory: " + directory);
        }
        Path manifestPath = directory.resolve("MANIFEST");
        List<AutoCloseable> owned = new ArrayList<>();
        MemoryTable active = new MemoryTable();
        MemoryTable frozen = null;
        List<SsTableReader> tables = new ArrayList<>();
        try {
            ManifestFile manifest;
            WalWriter writer;
            long maxId;
            if (!Files.exists(manifestPath, NOFOLLOW_LINKS)) {
                try (var children = Files.newDirectoryStream(directory)) {
                    if (children.iterator().hasNext()) {
                        throw new IOException("Nonempty directory has no Manifest; refusing initialization");
                    }
                }
                writer = files.createWal(walPath(directory, 1));
                owned.add(writer);
                manifest = files.createManifest(manifestPath, new ManifestState(1, 1, 0, 0, List.of()));
                owned.add(manifest);
                maxId = 1;
            } else {
                requireRegularFile(manifestPath);
                manifest = files.openManifest(manifestPath);
                owned.add(manifest);
                ManifestState snapshot = manifest.current();
                // 全部引用先检查存在和类型，再做可能修复活跃日志的恢复。
                requireRegularFile(walPath(directory, snapshot.activeWalId()));
                if (snapshot.frozenWalId() != 0) {
                    requireRegularFile(walPath(directory, snapshot.frozenWalId()));
                }
                for (long id : snapshot.sstIds()) {
                    requireRegularFile(sstPath(directory, id));
                }
                maxId = scanMaxFileId(directory, snapshot.maxFileId());
                for (long id : snapshot.sstIds()) {
                    SsTableReader reader = files.openTable(sstPath(directory, id));
                    owned.add(reader);
                    tables.add(reader);
                }
                if (snapshot.frozenWalId() != 0) {
                    frozen = new MemoryTable();
                    files.replayFrozen(walPath(directory, snapshot.frozenWalId()),
                            snapshot.frozenWalLength(), frozen::apply);
                }
                writer = files.openWal(walPath(directory, snapshot.activeWalId()), active::apply);
                owned.add(writer);
            }
            return new LsmStore(writer, active, frozen, tables, owned, files, maxId);
        } catch (IOException | RuntimeException | Error failure) {
            closeResources(owned, failure);
            throw failure;
        }
    }

    /** 完整追加并同步当前 WAL 后更新活跃表；尚不自动刷盘。 */
    public void put(ByteKey key, Value value) throws IOException {
        requireOpen();
        appendAndApply(new WalRecord(key, value));
    }

    /** 保存删除标记，包含当前不存在的键。 */
    public void delete(ByteKey key) throws IOException {
        requireOpen();
        appendAndApply(new WalRecord(key, Tombstone.INSTANCE));
    }

    /** 按 active、frozen、SSTable 从新到旧查询，遇到 Tombstone 也立即停止。 */
    public Optional<Entry> get(ByteKey key) throws IOException {
        requireOpen();
        Objects.requireNonNull(key, "key");
        try {
            Optional<Entry> result = active.table.get(key);
            if (result.isPresent()) {
                return result;
            }
            if (frozen != null) {
                result = frozen.table.get(key);
                if (result.isPresent()) {
                    return result;
                }
            }
            for (int i = tables.size() - 1; i >= 0; i--) {
                result = tables.get(i).get(key);
                if (result.isPresent()) {
                    return result;
                }
            }
            return Optional.empty();
        } catch (IOException | RuntimeException | Error failure) {
            state = State.FAILED;
            throw failure;
        }
    }

    private void appendAndApply(WalRecord record) throws IOException {
        long newBytes = active.bytesAfter(record); // 参数/容量验证在 WAL 追加前完成。
        try {
            files.append(writer, record);
            active.apply(record, newBytes);
        } catch (IOException | RuntimeException | Error failure) {
            state = State.FAILED;
            throw failure;
        }
    }

    /** 仅为后续轮换预留编号，不创建文件；新文件仍须使用 CREATE_NEW。 */
    long allocateFileId() throws IOException {
        requireOpen();
        if (maxFileId == Long.MAX_VALUE) {
            throw new IOException("LSM file ID space exhausted");
        }
        return ++maxFileId;
    }

    long activeBytes() {
        requireOpen();
        return active.bytes;
    }

    long frozenBytes() {
        requireOpen();
        return frozen == null ? 0 : frozen.bytes;
    }

    /** 本步无后台任务，关闭所有持有资源；不将活跃表强制写成 SSTable。 */
    @Override
    public void close() throws IOException {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        Throwable failure = closeResources(owned, null);
        if (failure instanceof IOException ioFailure) {
            throw ioFailure;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IOException("Failed to close LSM resources", failure);
        }
    }

    private void requireOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException("LSM store is " + state);
        }
    }

    private static void requireRegularFile(Path path) throws IOException {
        if (!Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS).isRegularFile()) {
            throw new IOException("Referenced LSM path is not a regular file: " + path);
        }
    }

    private static long scanMaxFileId(Path directory, long max) throws IOException {
        try (var children = Files.newDirectoryStream(directory)) {
            for (Path path : children) {
                var matcher = INTERNAL_FILE.matcher(path.getFileName().toString());
                if (matcher.matches()) {
                    try {
                        max = Math.max(max, Long.parseLong(matcher.group(1)));
                    } catch (NumberFormatException failure) {
                        throw new IOException("Internal LSM filename exceeds file ID range: " + path, failure);
                    }
                }
            }
        }
        return max;
    }

    static Path walPath(Path directory, long id) {
        return directory.resolve(id + ".wal");
    }

    static Path sstPath(Path directory, long id) {
        return directory.resolve(id + ".sst");
    }

    /** 逆序关闭全部资源，即使其中一个失败；原始错误优先，后续错误 suppressed。 */
    static Throwable closeResources(List<? extends AutoCloseable> resources, Throwable failure) {
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (Exception | Error cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else if (failure != cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        return failure;
    }

    /** 组合层的最小故障注入边界；方法返回前失败须自行清理尚未移交的资源。 */
    static class FileOps {
        WalWriter createWal(Path path) throws IOException {
            Files.createFile(path); // CREATE_NEW；初始化失败保留文件，禁止覆盖。
            return WalWriter.openEmpty(path);
        }

        WalWriter openWal(Path path, Consumer<WalRecord> replay) throws IOException {
            return WalWriter.openExistingRecovering(path, replay);
        }

        void replayFrozen(Path path, long length, Consumer<WalRecord> replay) throws IOException {
            WalWriter.replaySealed(path, length, replay);
        }

        ManifestFile createManifest(Path path, ManifestState initial) throws IOException {
            return ManifestFile.create(path, initial);
        }

        ManifestFile openManifest(Path path) throws IOException {
            return ManifestFile.open(path);
        }

        SsTableReader openTable(Path path) throws IOException {
            return SsTableReader.open(path);
        }

        void append(WalWriter writer, WalRecord record) throws IOException {
            writer.append(record);
        }
    }

    private static final class MemoryTable {
        private final MemTable table = new TreeMapMemTable();
        private long bytes;

        private long bytesAfter(WalRecord record) {
            long recordBytes = encodedSize(record.key(), record.entry());
            if (recordBytes > WalCodec.MAX_PAYLOAD_SIZE) {
                throw new IllegalArgumentException("LSM record exceeds size limit");
            }
            long oldBytes = table.get(record.key()).map(entry -> encodedSize(record.key(), entry)).orElse(0L);
            return Math.addExact(bytes - oldBytes, recordBytes);
        }

        private void apply(WalRecord record) {
            apply(record, bytesAfter(record));
        }

        private void apply(WalRecord record, long nextBytes) {
            if (record.entry() instanceof Value value) {
                table.put(record.key(), value);
            } else {
                table.delete(record.key());
            }
            bytes = nextBytes;
        }

        private static long encodedSize(ByteKey key, Entry entry) {
            return (long) WalCodec.MIN_PAYLOAD_SIZE + key.toByteArray().length
                    + (entry instanceof Value value ? value.toByteArray().length : 0);
        }
    }
}
