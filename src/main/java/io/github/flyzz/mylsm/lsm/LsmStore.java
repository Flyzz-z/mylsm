package io.github.flyzz.mylsm.lsm;

import io.github.flyzz.mylsm.memtable.MemTable;
import io.github.flyzz.mylsm.memtable.MemTableRecord;
import io.github.flyzz.mylsm.memtable.TreeMapMemTable;
import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Entry;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;
import io.github.flyzz.mylsm.sstable.SsTableReader;
import io.github.flyzz.mylsm.sstable.SsTableRecord;
import io.github.flyzz.mylsm.sstable.SsTableWriter;
import io.github.flyzz.mylsm.wal.WalCodec;
import io.github.flyzz.mylsm.wal.WalRecord;
import io.github.flyzz.mylsm.wal.WalWriter;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/**
 * 目录级存储，前台串行调用，内部一个线程后台刷盘，调用方必须独占目录。
 * 最多一个活跃表和一个冻结表；冻结槽位忙且活跃表已满时后续写入等待。
 */
public final class LsmStore implements AutoCloseable {
    private static final Pattern INTERNAL_FILE = Pattern.compile("([1-9][0-9]*)\\.(wal|sst)");
    private static final FileOps FILES = new FileOps();
    private static final long DEFAULT_MEMTABLE_TARGET_BYTES = 4 * 1024 * 1024;
    private enum State { OPEN, FAILED, CLOSING, CLOSED }

    private final Path directory;
    private final ManifestFile manifest;
    private final long targetBytes;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private WalWriter writer;
    private MemoryTable active;
    private Frozen frozen;
    private List<SsTableReader> tables; // 从旧到新。
    private List<AutoCloseable> owned;
    private final FileOps files;
    private Thread worker;
    private long maxFileId;
    private State state = State.OPEN;
    private Throwable failure;
    private Throwable backgroundFailure;

    private LsmStore(Path directory, ManifestFile manifest, WalWriter writer, MemoryTable active,
                     MemoryTable frozen, List<SsTableReader> tables, List<AutoCloseable> owned,
                     FileOps files, long maxFileId, long targetBytes) {
        this.directory = directory;
        this.manifest = manifest;
        this.targetBytes = targetBytes;
        this.writer = writer;
        this.active = active;
        this.frozen = frozen == null ? null : new Frozen(frozen, manifest.current().frozenWalId());
        this.tables = new ArrayList<>(tables);
        this.owned = new ArrayList<>(owned);
        this.files = files;
        this.maxFileId = maxFileId;
    }

    /**
     * 已有空目录可初始化；已有数据库按最新有效 Manifest 恢复。
     * 缺失引用、残缺文件头、冻结 WAL 长度不符或可检测损坏均失败，不返回部分实例。
     * 不提供文件锁；调用方不得同时通过其他实例或进程访问目录。
     */
    public static LsmStore open(Path directory) throws IOException {
        return open(directory, DEFAULT_MEMTABLE_TARGET_BYTES, FILES);
    }

    /** 目标是每表最新记录编码字节量，允许一条合法大记录越过目标，不等于堆内存上限。 */
    public static LsmStore open(Path directory, long memTableTargetBytes) throws IOException {
        return open(directory, memTableTargetBytes, FILES);
    }

    static LsmStore open(Path directory, FileOps files) throws IOException {
        return open(directory, DEFAULT_MEMTABLE_TARGET_BYTES, files);
    }

    static LsmStore open(Path directory, long memTableTargetBytes, FileOps files) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(files, "files");
        if (memTableTargetBytes <= 0) {
            throw new IllegalArgumentException("MemTable target must be positive");
        }
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
            LsmStore store = new LsmStore(directory, manifest, writer, active, frozen, tables,
                    owned, files, maxId, memTableTargetBytes);
            store.worker = Objects.requireNonNull(files.newWorker(store::runWorker), "worker");
            store.worker.start();
            return store;
        } catch (IOException | RuntimeException | Error failure) {
            closeResources(owned, failure);
            throw failure;
        }
    }

    /** 完整追加并同步 WAL 后更新活跃表；必要时轮换或在追加前等待冻结槽位。 */
    public void put(ByteKey key, Value value) throws IOException {
        lock.lock();
        try {
            requireOpen();
            appendAndApply(new WalRecord(key, value));
        } finally {
            lock.unlock();
        }
    }

    /** 保存删除标记，包含当前不存在的键。 */
    public void delete(ByteKey key) throws IOException {
        lock.lock();
        try {
            requireOpen();
            appendAndApply(new WalRecord(key, Tombstone.INSTANCE));
        } finally {
            lock.unlock();
        }
    }

    /** 按 active、frozen、SSTable 从新到旧查询，遇到 Tombstone 也立即停止。 */
    public Optional<Entry> get(ByteKey key) throws IOException {
        lock.lock();
        try {
            requireOpen();
            Objects.requireNonNull(key, "key");
            return getRecord(key);
        } finally {
            lock.unlock();
        }
    }

    private Optional<Entry> getRecord(ByteKey key) throws IOException {
        try {
            Optional<Entry> result = active.table.get(key);
            if (result.isPresent()) {
                return result;
            }
            if (frozen != null) {
                result = frozen.memory().table.get(key);
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
            fail(failure, false);
            throw failure;
        }
    }

    private void appendAndApply(WalRecord record) throws IOException {
        active.bytesAfter(record); // 在可能等待或轮换之前拒绝非法参数。
        while (active.bytes >= targetBytes) {
            if (frozen != null) {
                files.beforeWait();
                try {
                    changed.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException error = new InterruptedIOException("Interrupted waiting for flush slot");
                    error.initCause(interrupted);
                    throw error;
                }
                if (state == State.FAILED) {
                    throw new IOException("LSM failed while waiting for flush slot", failure);
                }
                requireOpen();
            } else {
                rotate();
            }
        }
        long newBytes = active.bytesAfter(record);
        try {
            files.append(writer, record);
            active.apply(record, newBytes);
        } catch (IOException | RuntimeException | Error failure) {
            fail(failure, false);
            throw failure;
        }
        if (active.bytes >= targetBytes && frozen == null) {
            rotate();
        }
    }

    /** 包内编号预留，不创建文件；轮换/输出创建仍须使用 CREATE_NEW。 */
    long allocateFileId() throws IOException {
        lock.lock();
        try {
            requireOpen();
            return allocateFileIdLocked();
        } finally {
            lock.unlock();
        }
    }

    private long allocateFileIdLocked() throws IOException {
        if (maxFileId == Long.MAX_VALUE) {
            throw new IOException("LSM file ID space exhausted");
        }
        return ++maxFileId;
    }

    long activeBytes() {
        lock.lock();
        try {
            requireOpen();
            return active.bytes;
        } finally {
            lock.unlock();
        }
    }

    long frozenBytes() {
        lock.lock();
        try {
            requireOpen();
            return frozen == null ? 0 : frozen.memory().bytes;
        } finally {
            lock.unlock();
        }
    }

    /** 等待已启动后台任务结束后释放资源；不强刷剩余活跃表。等待中断延后恢复标志。 */
    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            if (state == State.CLOSED) {
                return;
            }
            if (state == State.OPEN) {
                state = State.CLOSING;
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        boolean interrupted = false;
        while (worker.isAlive()) {
            try {
                worker.join();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        Throwable failure;
        lock.lock();
        try {
            state = State.CLOSED;
            failure = backgroundFailure == null ? null
                    : new IOException("Background SSTable flush failed", backgroundFailure);
            failure = closeResources(owned, failure);
            owned = List.of();
        } finally {
            lock.unlock();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
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
            throw new IllegalStateException("LSM store is " + state, failure);
        }
    }

    /** 持锁执行；新 WAL 被提交为 active 之前绝不接纳用户写入。 */
    private void rotate() throws IOException {
        WalWriter nextWriter = null;
        boolean registered = false;
        try {
            ManifestState previous = manifest.current();
            long nextId = allocateFileIdLocked();
            MemoryTable nextActive = new MemoryTable();
            Frozen nextFrozen = new Frozen(active, previous.activeWalId());
            nextWriter = files.createWal(walPath(directory, nextId));
            owned.add(nextWriter);
            registered = true;
            files.closeWal(writer);
            owned.remove(writer);
            long sealedLength = files.walSize(walPath(directory, previous.activeWalId()));
            ManifestState next = new ManifestState(Math.incrementExact(previous.revision()), nextId,
                    previous.activeWalId(), sealedLength, previous.sstIds());
            files.commit(manifest, next);
            writer = nextWriter;
            active = nextActive;
            frozen = nextFrozen;
            changed.signalAll();
        } catch (IOException | RuntimeException | Error failure) {
            if (nextWriter != null && !registered) {
                closeResources(List.of(nextWriter), failure);
            }
            fail(failure, false);
            throw failure;
        }
    }

    /** 线程拥有唯一执行循环，不使用任务队列；恢复的 frozen 也由同一线程处理。 */
    private void runWorker() {
        try {
            while (true) {
                Frozen job;
                long outputId;
                List<MemTableRecord> snapshot;
                lock.lock();
                try {
                    while (frozen == null && state == State.OPEN) {
                        changed.awaitUninterruptibly();
                    }
                    if (frozen == null || state == State.FAILED || state == State.CLOSED) {
                        return;
                    }
                    job = frozen;
                    outputId = allocateFileIdLocked();
                    snapshot = job.memory().table.snapshot();
                } finally {
                    lock.unlock();
                }
                writeAndPublish(job, outputId, snapshot);
            }
        } catch (IOException | RuntimeException | Error failure) {
            lock.lock();
            try {
                fail(failure, true);
            } finally {
                lock.unlock();
            }
        }
    }

    private void writeAndPublish(Frozen job, long outputId, List<MemTableRecord> snapshot) throws IOException {
        Path output = sstPath(directory, outputId);
        // 固定快照的惰性适配，不另收集一份全表列表。
        Iterable<SsTableRecord> records = () -> snapshot.stream()
                .map(record -> new SsTableRecord(record.key(), record.entry())).iterator();
        SsTableReader reader = null;
        Throwable primary = null;
        try {
            files.writeTable(output, records);
            reader = files.openTable(output);
            lock.lock();
            try {
                if (state == State.FAILED || state == State.CLOSED) {
                    return; // 其他路径已失败：保留 WAL，不再提交新状态。
                }
                ManifestState previous = manifest.current();
                List<Long> ids = new ArrayList<>(previous.sstIds());
                if (ids.size() == ManifestCodec.MAX_SSTABLE_COUNT) {
                    throw new IOException("Manifest state exceeds payload limit");
                }
                ids.add(outputId);
                ManifestState next = new ManifestState(Math.incrementExact(previous.revision()),
                        previous.activeWalId(), 0, 0, ids);
                List<SsTableReader> nextTables = new ArrayList<>(tables);
                nextTables.add(reader);
                List<AutoCloseable> nextOwned = new ArrayList<>(owned);
                nextOwned.add(reader);
                files.commit(manifest, next);
                tables = nextTables;
                owned = nextOwned;
                reader = null; // 所有权已移交；提交后的安装只做引用赋值。
                frozen = null;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
            try {
                files.deleteObsolete(walPath(directory, job.walId()));
            } catch (IOException | RuntimeException ignored) {
                // 最新成功提交不再引用它，留下孤儿不改变数据库语义。
            }
        } catch (IOException | RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException | RuntimeException | Error cleanupFailure) {
                    if (primary != null) {
                        if (primary != cleanupFailure) {
                            primary.addSuppressed(cleanupFailure);
                        }
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }
    }

    /** 所有调用者都持有 lock；先保存错误，再唤醒前台及工作线程。 */
    private void fail(Throwable cause, boolean background) {
        if (failure == null) {
            failure = cause;
        }
        if (background && backgroundFailure == null) {
            backgroundFailure = cause;
        }
        state = State.FAILED;
        changed.signalAll();
    }

    private record Frozen(MemoryTable memory, long walId) {
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
        Thread newWorker(Runnable task) {
            return new Thread(task, "mylsm-flush");
        }

        /** 包内测试阻塞点：调用时持状态锁，测试不得在此等待发布。 */
        void beforeWait() {
        }

        void writeTable(Path path, Iterable<SsTableRecord> records) throws IOException {
            SsTableWriter.write(path, records);
        }

        void commit(ManifestFile manifest, ManifestState next) throws IOException {
            manifest.append(next);
        }

        void closeWal(WalWriter writer) throws IOException {
            writer.close();
        }

        long walSize(Path path) throws IOException {
            return Files.size(path);
        }

        void deleteObsolete(Path path) throws IOException {
            Files.deleteIfExists(path);
        }

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
