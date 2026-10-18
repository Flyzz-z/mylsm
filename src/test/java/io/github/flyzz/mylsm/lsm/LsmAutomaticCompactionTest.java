package io.github.flyzz.mylsm.lsm;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;
import io.github.flyzz.mylsm.sstable.SsTableReader;
import io.github.flyzz.mylsm.sstable.SsTableRecord;
import io.github.flyzz.mylsm.wal.WalRecord;
import io.github.flyzz.mylsm.wal.WalWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(25)
class LsmAutomaticCompactionTest {
    private static final long LARGE_TARGET = Long.MAX_VALUE;
    private static final ByteKey EMPTY = ByteKey.copyOf(new byte[0]);
    private static final ByteKey BINARY = ByteKey.copyOf(new byte[]{0, (byte) 0xff});
    private static final Value EMPTY_VALUE = Value.copyOf(new byte[0]);

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 1})
    void rejectsInvalidThresholdBeforeCreatingFiles(int threshold) throws IOException {
        assertThrows(IllegalArgumentException.class, () -> LsmStore.open(directory, 1024, threshold));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void defaultThresholdIsFourAndPublicationAutomaticallyStartsTheSingleWorker() throws Exception {
        seed(3, false);
        ControlledFiles files = new ControlledFiles(true, "", 4);
        try (LsmStore store = LsmStore.open(directory, files); ControlledFiles release = files) {
            assertEquals(0, files.compactions.get());
            store.put(key("fourth"), value("4"));
            store.flush(); // 只保证刷盘完成；后台可以立即进入自动压缩。
            await(files.started);
            assertEquals(4, files.compactionInputs.size());
            assertEquals(4, files.latest.sstIds().size());
            assertSeedData(store, 3);
            assertEquals(Optional.of(value("4")), store.get(key("fourth")));
            release.close();
            ManifestState published = publication(files);
            await(files.deleted);
            assertEquals(1, published.sstIds().size());
            assertSeedData(store, 3);
            for (long id : files.inputIds) {
                assertFalse(Files.exists(LsmStore.sstPath(directory, id)));
            }
        }
        assertEquals(1, files.compactions.get());
        assertEquals(1, files.maxConcurrent.get());
        assertEquals(Set.of(files.worker), files.threads);
        assertAllClosed(files);
        try (LsmStore store = LsmStore.open(directory)) {
            assertSeedData(store, 3);
            assertEquals(Optional.of(value("4")), store.get(key("fourth")));
        }
    }

    @Test
    void recoveryTriggersCompactionWithoutFlushingTheRecoveredActiveMemory() throws Exception {
        seed(2, true);
        ControlledFiles files = new ControlledFiles(true, "", 2);
        long activeId;
        byte[] activeWal;
        try (LsmStore store = LsmStore.open(directory, LARGE_TARGET, 2, files);
             ControlledFiles release = files) {
            await(files.started);
            activeId = files.latest.activeWalId();
            activeWal = Files.readAllBytes(LsmStore.walPath(directory, activeId));
            long activeBytes = store.activeBytes();
            assertTrue(activeBytes > 0);
            assertEquals(0, files.flushes.get());
            assertSeedData(store, 2);
            assertEquals(Optional.of(value("keep")), store.get(key("pending")));
            release.close();
            ManifestState compacted = publication(files);
            await(files.deleted);
            assertEquals(activeId, compacted.activeWalId());
            assertEquals(activeBytes, store.activeBytes());
            assertArrayEquals(activeWal, Files.readAllBytes(LsmStore.walPath(directory, activeId)));
            try (SsTableReader output = SsTableReader.open(files.output)) {
                assertEquals(Optional.empty(), output.get(key("pending")));
                assertEquals(Optional.of(Tombstone.INSTANCE), output.get(key("ghost")));
                assertEquals(Optional.of(EMPTY_VALUE), output.get(EMPTY));
            }
        }
        try (LsmStore store = LsmStore.open(directory)) {
            assertSeedData(store, 2);
            assertEquals(Optional.of(value("keep")), store.get(key("pending")));
        }
    }

    @Test
    void customThresholdCanCompactRepeatedlyAndDoesNotRewriteItsSingleOutput() throws Exception {
        ControlledFiles files = new ControlledFiles(false, "", 2);
        try (LsmStore store = LsmStore.open(directory, LARGE_TARGET, 2, files)) {
            store.put(key("k"), value("0"));
            store.flush();
            for (int i = 1; i <= 3; i++) {
                store.put(key("k"), value(Integer.toString(i)));
                store.flush();
                assertEquals(1, publication(files).sstIds().size());
                assertEquals(Optional.of(value(Integer.toString(i))), store.get(key("k")));
            }
        }
        assertEquals(3, files.compactions.get());
        assertEquals(4, files.flushes.get());
        assertEquals(1, files.maxConcurrent.get());
        assertAllClosed(files);
        try (LsmStore store = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("3")), store.get(key("k")));
        }
    }

    @Test
    void anEmptyStoreOrSinglePublishedTableDoesNotCreateCompactionOutput() throws IOException {
        ControlledFiles files = new ControlledFiles(false, "", 1);
        try (LsmStore store = LsmStore.open(directory, LARGE_TARGET, 2, files)) {
            store.flush();
            store.put(key("a"), value("one"));
            store.flush();
            store.put(key("pending"), value("memory"));
        }
        assertEquals(0, files.compactions.get());
        assertEquals(1, files.latest.sstIds().size());
    }

    @Test
    void foregroundReadsAndRotationContinueWhileCompactionUsesIndependentReaders() throws Exception {
        seed(2, false);
        ControlledFiles files = new ControlledFiles(true, "", 2);
        try (LsmStore store = LsmStore.open(directory, 64, 2, files);
             var foreground = Executors.newSingleThreadExecutor(); ControlledFiles release = files) {
            await(files.started); // 已消费归并器的首条记录，输入游标仍处于占用状态。
            assertSeedData(store, 2);
            long outputId = id(files.output);
            store.put(key("a"), value("fresh"));
            store.delete(key("b"));
            store.put(key("frozen"), Value.copyOf(new byte[40])); // 越过 64 字节，轮换。
            ManifestState rotated = files.latest;
            assertTrue(rotated.activeWalId() > outputId);
            assertTrue(rotated.frozenWalId() != 0);
            store.put(key("active"), value("live"));
            store.put(key("full"), Value.copyOf(new byte[64])); // frozen 忙，active 暂留已满。
            long walSize = Files.size(LsmStore.walPath(directory, rotated.activeWalId()));
            var pending = foreground.submit(() -> { store.put(key("later"), value("ok")); return null; });
            await(files.waiting);
            assertFalse(pending.isDone());
            assertEquals(walSize, Files.size(LsmStore.walPath(directory, rotated.activeWalId())));
            assertEquals(0, files.flushes.get());
            assertEquals(Optional.of(value("fresh")), store.get(key("a")));
            assertEquals(Optional.of(Tombstone.INSTANCE), store.get(key("b")));
            assertEquals(Optional.of(value("stable")), store.get(key("c")));
            release.close();
            ManifestState firstPublication = publication(files);
            assertEquals(List.of(outputId), firstPublication.sstIds());
            assertEquals(rotated.activeWalId(), firstPublication.activeWalId());
            assertEquals(rotated.frozenWalId(), firstPublication.frozenWalId());
            assertEquals(rotated.frozenWalLength(), firstPublication.frozenWalLength());
            pending.get(5, TimeUnit.SECONDS);
            store.flush();
            assertEquals(Optional.of(value("ok")), store.get(key("later")));
        }
        assertEquals("compact", files.jobs.get(0));
        assertEquals("flush", files.jobs.get(1));
        assertEquals(1, files.maxConcurrent.get());
        assertEquals(Set.of(files.worker), files.threads);
        assertAllClosed(files);
        try (LsmStore store = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("fresh")), store.get(key("a")));
            assertEquals(Optional.of(Tombstone.INSTANCE), store.get(key("b")));
            assertEquals(Optional.of(value("live")), store.get(key("active")));
            assertEquals(Optional.of(value("ok")), store.get(key("later")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"input-open", "write-before", "write-after", "output-open", "input-close",
            "commit-before", "commit-after", "old-close"})
    void backgroundFailureRetainsRecoverySourcesAndReportsThroughClose(String fault) throws Exception {
        seed(2, true);
        ControlledFiles files = new ControlledFiles(false, fault, 2);
        LsmStore store = LsmStore.open(directory, LARGE_TARGET, 2, files);
        try {
            await(files.finished);
            assertThrows(IllegalStateException.class, () -> store.get(key("a")));
        } finally {
            files.close();
            IOException failure = assertThrows(IOException.class, store::close);
            assertSame(files.failure, failure.getCause());
        }
        assertAllClosed(files);
        assertTrue(Files.exists(directory.resolve("3.sst")));
        assertTrue(Files.exists(directory.resolve("5.sst")));
        try (ManifestFile manifest = ManifestFile.open(directory.resolve("MANIFEST"))) {
            assertEquals(fault.equals("commit-after") || fault.equals("old-close") ? 1 : 2,
                    manifest.current().sstIds().size());
        }
        try (LsmStore recovered = LsmStore.open(directory, LARGE_TARGET, Integer.MAX_VALUE)) {
            assertSeedData(recovered, 2);
            assertEquals(Optional.of(value("keep")), recovered.get(key("pending")));
        }
    }

    @Test
    void corruptionPropagatesCheckedIoAndPreservesSuppressedCleanupErrors() throws Exception {
        seed(2, false);
        Path corrupt = directory.resolve("3.sst");
        byte[] original = Files.readAllBytes(corrupt);
        byte[] broken = original.clone();
        broken[12] ^= 1; // 第一个数据块 CRC；打开仍可验证元信息，归并读取时失败。
        Files.write(corrupt, broken);
        ControlledFiles files = new ControlledFiles(false, "suppressed", 2);
        LsmStore store = LsmStore.open(directory, LARGE_TARGET, 2, files);
        IOException error;
        try {
            await(files.finished);
            error = assertThrows(IOException.class, store::close);
        } finally {
            files.close();
            store.close();
        }
        IOException cause = assertInstanceOf(IOException.class, error.getCause());
        assertArrayEquals(new Throwable[]{files.failure}, cause.getSuppressed());
        assertTrue(Files.exists(directory.resolve("3.sst")));
        assertTrue(Files.exists(directory.resolve("5.sst")));
        assertFalse(Files.exists(files.output)); // Writer 清理部分输出。
        assertAllClosed(files);
        Files.write(corrupt, original);
        try (LsmStore recovered = LsmStore.open(directory)) {
            assertSeedData(recovered, 2);
        }
    }

    @Test
    void compactionFailureWakesBackpressuredWritesBeforeTheyAppendTheirWalRecord() throws Exception {
        seed(2, false);
        ControlledFiles files = new ControlledFiles(true, "write-after", 2);
        LsmStore store = LsmStore.open(directory, 1, 2, files);
        try (var foreground = Executors.newSingleThreadExecutor(); ControlledFiles release = files) {
            await(files.started);
            store.put(key("frozen"), value("one"));
            store.put(key("active"), value("two"));
            long wal = files.latest.activeWalId();
            long size = Files.size(LsmStore.walPath(directory, wal));
            var blocked = foreground.submit(() -> { store.put(key("never"), value("logged")); return null; });
            await(files.waiting);
            release.close();
            ExecutionException error = assertThrows(ExecutionException.class, () -> blocked.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, error.getCause());
            assertSame(files.failure, error.getCause().getCause());
            assertEquals(size, Files.size(LsmStore.walPath(directory, wal)));
        } finally {
            files.close();
            assertSame(files.failure, assertThrows(IOException.class, store::close).getCause());
        }
        try (LsmStore recovered = LsmStore.open(directory, LARGE_TARGET, Integer.MAX_VALUE)) {
            assertEquals(Optional.of(value("one")), recovered.get(key("frozen")));
            assertEquals(Optional.of(value("two")), recovered.get(key("active")));
            assertEquals(Optional.empty(), recovered.get(key("never")));
        }
    }

    @Test
    void aForegroundFailurePreventsTheCompletedOutputFromBeingPublished() throws Exception {
        seed(2, false);
        ControlledFiles files = new ControlledFiles(true, "", 2);
        LsmStore store = LsmStore.open(directory, LARGE_TARGET, 2, files);
        try {
            await(files.started);
            files.failAppend = true;
            assertSame(files.failure, assertThrows(IOException.class, () -> store.put(key("bad"), value("x"))));
            files.close();
            await(files.finished);
            assertEquals(2, files.latest.sstIds().size());
            assertTrue(Files.exists(files.output));
        } finally {
            files.close();
            store.close();
        }
        assertAllClosed(files);
        try (LsmStore recovered = LsmStore.open(directory)) {
            assertSeedData(recovered, 2);
            assertEquals(Optional.empty(), recovered.get(key("bad")));
            assertFalse(Files.exists(files.output));
        }
    }

    @Test
    void deletionFailureLeavesAnOrphanAndDoesNotPreventDeletingOtherInputs() throws Exception {
        seed(2, false);
        ControlledFiles files = new ControlledFiles(false, "delete", 2);
        try (LsmStore store = LsmStore.open(directory, LARGE_TARGET, 2, files)) {
            publication(files);
            await(files.deleted);
            assertSeedData(store, 2);
            assertTrue(Files.exists(directory.resolve("3.sst")));
            assertFalse(Files.exists(directory.resolve("5.sst")));
        }
        try (LsmStore recovered = LsmStore.open(directory)) {
            assertSeedData(recovered, 2);
            assertFalse(Files.exists(directory.resolve("3.sst")));
        }
    }

    @Test
    void closeWaitsForAnAlreadyRunningCompactionAndReleasesAllReaders() throws Exception {
        seed(2, false);
        ControlledFiles files = new ControlledFiles(true, "", 2);
        LsmStore store = LsmStore.open(directory, LARGE_TARGET, 2, files);
        try (var closing = Executors.newSingleThreadExecutor(); ControlledFiles release = files) {
            await(files.started);
            var closed = closing.submit(() -> { store.close(); return null; });
            assertThrows(TimeoutException.class, () -> closed.get(100, TimeUnit.MILLISECONDS));
            assertEquals(0, files.closes.get());
            release.close();
            closed.get(5, TimeUnit.SECONDS);
            assertFalse(files.worker.isAlive());
            assertThrows(IllegalStateException.class, () -> store.get(key("a")));
            assertAllClosed(files);
        } finally {
            files.close();
            store.close();
        }
        assertEquals(1, files.latest.sstIds().size());
        try (LsmStore reopened = LsmStore.open(directory)) {
            assertSeedData(reopened, 2);
        }
    }

    @Test
    void closeDoesNotStartANewAutomaticCompactionAfterFinishingItsFrozenFlush() throws Exception {
        seed(3, false);
        ControlledFiles files = new ControlledFiles(false, "", 4);
        files.pauseFlush = true;
        LsmStore store = LsmStore.open(directory, 1, 4, files);
        try (var closing = Executors.newSingleThreadExecutor(); ControlledFiles release = files) {
            store.put(key("fourth"), value("4"));
            await(files.flushStarted);
            var closed = closing.submit(() -> { store.close(); return null; });
            assertThrows(TimeoutException.class, () -> closed.get(100, TimeUnit.MILLISECONDS));
            release.close();
            closed.get(5, TimeUnit.SECONDS);
        } finally {
            files.close();
            store.close();
        }
        assertEquals(0, files.compactions.get());
        assertEquals(4, files.latest.sstIds().size());
        try (LsmStore reopened = LsmStore.open(directory, LARGE_TARGET, Integer.MAX_VALUE)) {
            assertSeedData(reopened, 3);
            assertEquals(Optional.of(value("4")), reopened.get(key("fourth")));
        }
    }

    private void seed(int generations, boolean pending) throws IOException {
        try (LsmStore store = LsmStore.open(directory, LARGE_TARGET, Integer.MAX_VALUE)) {
            store.put(key("b"), value("old"));
            store.put(key("c"), value("stable"));
            for (int i = 0; i < generations; i++) {
                store.put(key("a"), value("v" + i));
                store.put(key("generation-" + i), value(Integer.toString(i)));
                store.put(EMPTY, EMPTY_VALUE);
                store.put(BINARY, Value.copyOf(new byte[]{(byte) i, (byte) 0xff}));
                store.delete(key("ghost"));
                if (i > 0) {
                    store.delete(key("b"));
                }
                store.flush();
            }
            if (pending) {
                store.put(key("pending"), value("keep"));
            }
        }
    }

    private static void assertSeedData(LsmStore store, int generations) throws IOException {
        assertEquals(Optional.of(value("v" + (generations - 1))), store.get(key("a")));
        assertEquals(Optional.of(Tombstone.INSTANCE), store.get(key("b")));
        assertEquals(Optional.of(Tombstone.INSTANCE), store.get(key("ghost")));
        assertEquals(Optional.of(value("stable")), store.get(key("c")));
        assertEquals(Optional.of(EMPTY_VALUE), store.get(EMPTY));
        assertEquals(Optional.of(Value.copyOf(new byte[]{(byte) (generations - 1), (byte) 0xff})), store.get(BINARY));
        for (int i = 0; i < generations; i++) {
            assertEquals(Optional.of(value(Integer.toString(i))), store.get(key("generation-" + i)));
        }
    }

    private static void assertAllClosed(ControlledFiles files) {
        assertFalse(files.worker.isAlive());
        for (SsTableReader reader : files.readerPaths.keySet()) {
            assertThrows(IllegalStateException.class, () -> reader.get(key("a")));
        }
    }

    private static ManifestState publication(ControlledFiles files) throws InterruptedException {
        ManifestState state = files.publications.poll(5, TimeUnit.SECONDS);
        assertNotNull(state, "timed out waiting for compaction publication");
        return state;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out at compaction boundary");
    }

    private static void awaitWorker(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IOException("compaction test release timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(error);
        }
    }

    private static long id(Path path) {
        return Long.parseLong(path.getFileName().toString().replace(".sst", ""));
    }

    private static ByteKey key(String text) {
        return ByteKey.copyOf(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Value value(String text) {
        return Value.copyOf(text.getBytes(StandardCharsets.UTF_8));
    }

    private static final class ControlledFiles extends LsmStore.FileOps implements AutoCloseable {
        private final String fault;
        private final boolean pause;
        private final IOException failure = new IOException("injected compaction failure");
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final CountDownLatch waiting = new CountDownLatch(1);
        private final CountDownLatch flushStarted = new CountDownLatch(1);
        private final CountDownLatch flushRelease = new CountDownLatch(1);
        private final CountDownLatch deleted;
        private final BlockingQueue<ManifestState> publications = new LinkedBlockingQueue<>();
        private final AtomicInteger compactions = new AtomicInteger();
        private final AtomicInteger flushes = new AtomicInteger();
        private final AtomicInteger concurrent = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private final Set<Thread> threads = new HashSet<>(); // 只由 worker 写，join 后读取。
        private final List<String> jobs = new ArrayList<>();
        private final Map<SsTableReader, Path> readerPaths = Collections.synchronizedMap(new IdentityHashMap<>());
        private final Map<Path, Integer> opens = new HashMap<>(); // open 返回前前台写，之后只有 worker 写。
        private Set<SsTableReader> compactionInputs = Set.of();
        private List<Long> inputIds = List.of();
        private volatile ManifestState latest;
        private volatile Path output;
        private volatile boolean failAppend;
        private boolean pauseFlush;
        private boolean merging;
        private boolean closeFailed;
        private int inputOpens;
        private Thread worker;

        private ControlledFiles(boolean pause, String fault, int inputCount) {
            this.pause = pause;
            this.fault = fault;
            deleted = new CountDownLatch(inputCount);
        }

        @Override
        Thread newWorker(Runnable task) {
            worker = super.newWorker(() -> { try { task.run(); } finally { finished.countDown(); } });
            return worker;
        }

        @Override
        ManifestFile createManifest(Path path, ManifestState state) throws IOException {
            ManifestFile manifest = super.createManifest(path, state);
            latest = state;
            return manifest;
        }

        @Override
        ManifestFile openManifest(Path path) throws IOException {
            ManifestFile manifest = super.openManifest(path);
            latest = manifest.current();
            return manifest;
        }

        @Override
        void beforeWait() {
            waiting.countDown();
        }

        @Override
        void append(WalWriter writer, WalRecord record) throws IOException {
            if (failAppend) {
                throw failure;
            }
            super.append(writer, record);
        }

        @Override
        void writeCompaction(Path path, List<SsTableReader> inputs) throws IOException {
            output = path;
            compactionInputs = Set.copyOf(inputs);
            inputIds = inputs.stream().map(readerPaths::get).map(LsmAutomaticCompactionTest::id).toList();
            compactions.incrementAndGet();
            begin("compact");
            merging = true;
            try {
                if (fault.equals("write-before")) { throw failure; }
                super.writeCompaction(path, inputs);
                if (fault.equals("write-after")) { throw failure; }
            } finally {
                merging = false;
                concurrent.decrementAndGet();
            }
        }

        @Override
        void writeTable(Path path, Iterable<SsTableRecord> records) throws IOException {
            if (merging) {
                try {
                    super.writeTable(path, () -> new Iterator<>() {
                        private final Iterator<SsTableRecord> delegate = records.iterator();

                        @Override
                        public boolean hasNext() { return delegate.hasNext(); }

                        @Override
                        public SsTableRecord next() {
                            SsTableRecord record = delegate.next();
                            if (started.getCount() != 0) {
                                started.countDown();
                                if (pause) {
                                    try { awaitWorker(release); }
                                    catch (IOException error) { throw new UncheckedIOException(error); }
                                }
                            }
                            return record;
                        }
                    });
                } catch (UncheckedIOException error) {
                    if (fault.equals("suppressed")) { error.addSuppressed(failure); }
                    throw error;
                }
            } else {
                flushes.incrementAndGet();
                begin("flush");
                try {
                    if (pauseFlush) {
                        flushStarted.countDown();
                        awaitWorker(flushRelease);
                    }
                    super.writeTable(path, records);
                } finally {
                    concurrent.decrementAndGet();
                }
            }
        }

        private void begin(String job) {
            jobs.add(job);
            threads.add(Thread.currentThread());
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
        }

        @Override
        SsTableReader openTable(Path path) throws IOException {
            int count = opens.merge(path, 1, Integer::sum);
            if (count > 1 && !path.equals(output) && ++inputOpens == 2 && fault.equals("input-open")) {
                throw failure;
            }
            if (path.equals(output) && fault.equals("output-open")) { throw failure; }
            SsTableReader reader = super.openTable(path);
            readerPaths.put(reader, path);
            return reader;
        }

        @Override
        void closeTable(SsTableReader reader) throws IOException {
            closes.incrementAndGet();
            if (!closeFailed && (fault.equals("input-close") && compactionInputs.contains(reader)
                    || fault.equals("old-close") && !compactionInputs.contains(reader))) {
                closeFailed = true;
                throw failure; // 模拟关闭前报错，最终 owned 释放路径必须仍能关闭它。
            }
            super.closeTable(reader);
        }

        @Override
        void commit(ManifestFile manifest, ManifestState next) throws IOException {
            boolean compacting = manifest.current().sstIds().size() >= 2 && next.sstIds().size() == 1;
            if (compacting && fault.equals("commit-before")) { throw failure; }
            super.commit(manifest, next);
            latest = next;
            if (compacting) {
                if (fault.equals("commit-after")) { throw failure; }
                publications.add(next);
            }
        }

        @Override
        void deleteObsolete(Path path) throws IOException {
            boolean oldTable = output != null && path.getFileName().toString().endsWith(".sst")
                    && !path.equals(output);
            try {
                if (oldTable && fault.equals("delete") && path.getFileName().toString().equals("3.sst")) {
                    throw failure;
                }
                super.deleteObsolete(path);
            } finally {
                if (oldTable) { deleted.countDown(); }
            }
        }

        @Override
        public void close() {
            release.countDown();
            flushRelease.countDown();
        }
    }
}
