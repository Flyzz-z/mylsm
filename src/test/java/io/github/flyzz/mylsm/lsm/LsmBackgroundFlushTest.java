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
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class LsmBackgroundFlushTest {
    @TempDir
    Path directory;

    @Test
    void foregroundContinuesUntilFullThenWaitsBeforeLoggingAndResumesAfterPublication() throws Exception {
        ControlledFiles files = new ControlledFiles("");
        try (LsmStore store = LsmStore.open(directory, 11, files);
             var foreground = Executors.newSingleThreadExecutor(); ControlledFiles release = files) {
            store.put(key("a"), value("1"));
            await(files.buildStarted);
            assertEquals(2, files.latest.activeWalId());
            assertEquals(1, files.latest.frozenWalId());
            assertEquals(List.of(), files.latest.sstIds());
            assertEquals(11, store.frozenBytes());
            assertEquals(Optional.of(value("1")), store.get(key("a")));
            store.put(key("b"), value("2"));
            assertEquals(11, store.activeBytes());
            assertEquals(Optional.of(value("2")), store.get(key("b")));
            long walSize = Files.size(directory.resolve("2.wal"));
            Future<?> pending = foreground.submit(() -> {
                store.put(key("c"), value("3"));
                return null;
            });
            await(files.waiting);
            assertFalse(pending.isDone());
            assertEquals(2, files.appends.get());
            assertEquals(walSize, Files.size(directory.resolve("2.wal")));
            files.close();
            pending.get(5, TimeUnit.SECONDS);
            assertEquals(Optional.of(value("3")), store.get(key("c")));
        }
        assertEquals(2, files.builds.get());
        assertEquals(1, files.maxSimultaneousBuilds.get());
        assertEquals(1, files.workerThreads.size());
        assertFalse(files.worker.isAlive());
        try (LsmStore store = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("1")), store.get(key("a")));
            assertEquals(Optional.of(value("2")), store.get(key("b")));
            assertEquals(Optional.of(value("3")), store.get(key("c")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void newestValueOrDeletionShadowsTheFrozenGenerationBeforeAndAfterPublication(boolean frozenDelete)
            throws Exception {
        ControlledFiles files = new ControlledFiles("");
        try (LsmStore store = LsmStore.open(directory, frozenDelete ? 10 : 11, files);
             ControlledFiles release = files) {
            if (frozenDelete) {
                store.delete(key("k"));
            } else {
                store.put(key("k"), value("1"));
            }
            await(files.buildStarted);
            if (frozenDelete) {
                store.put(key("k"), value("2"));
            } else {
                store.delete(key("k"));
            }
            var expected = frozenDelete ? value("2") : Tombstone.INSTANCE;
            assertEquals(Optional.of(expected), store.get(key("k")));
            files.close();
            await(files.deleted);
            assertEquals(Optional.of(expected), store.get(key("k")));
        }
        try (LsmStore store = LsmStore.open(directory)) {
            assertEquals(Optional.of(frozenDelete ? value("2") : Tombstone.INSTANCE), store.get(key("k")));
        }
    }

    @Test
    void aLegalRecordMayExceedTargetAndIsPersistedAsOneGeneration() throws IOException {
        Value large = Value.copyOf(new byte[8192]);
        try (LsmStore store = LsmStore.open(directory, 1)) {
            store.put(key("k"), large);
            assertEquals(Optional.of(large), store.get(key("k")));
        }
        try (ManifestFile manifest = ManifestFile.open(directory.resolve("MANIFEST"))) {
            assertEquals(1, manifest.current().sstIds().size());
            assertEquals(0, manifest.current().frozenWalId());
        }
        try (LsmStore store = LsmStore.open(directory)) {
            assertEquals(Optional.of(large), store.get(key("k")));
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0})
    void rejectsInvalidTargetsBeforeInitializingFiles(long target) throws IOException {
        assertThrows(IllegalArgumentException.class, () -> LsmStore.open(directory, target));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void backgroundFailureWakesABlockedWriterWithoutLoggingItsOperation() throws Exception {
        ControlledFiles files = new ControlledFiles("write");
        LsmStore store = LsmStore.open(directory, 11, files);
        try (var foreground = Executors.newSingleThreadExecutor(); ControlledFiles release = files) {
            store.put(key("a"), value("1"));
            await(files.buildStarted);
            store.put(key("b"), value("2"));
            Future<?> pending = foreground.submit(() -> {
                store.put(key("c"), value("3"));
                return null;
            });
            await(files.waiting);
            files.close();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> pending.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
            assertSame(files.failure, failure.getCause().getCause());
            assertEquals(2, files.appends.get());
            assertThrows(IllegalStateException.class, () -> store.get(key("a")));
        } finally {
            files.close();
            IOException failure = assertThrows(IOException.class, store::close);
            assertSame(files.failure, failure.getCause());
        }
        assertTrue(Files.exists(directory.resolve("1.wal")));
        try (LsmStore recovered = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("1")), recovered.get(key("a")));
            assertEquals(Optional.of(value("2")), recovered.get(key("b")));
            assertEquals(Optional.empty(), recovered.get(key("c")));
        }
    }

    @Test
    void waitingInterruptionLeavesTheStoreUsableAndPreservesInterruptStatus() throws Exception {
        ControlledFiles files = new ControlledFiles("");
        try (LsmStore store = LsmStore.open(directory, 11, files);
             var foreground = Executors.newSingleThreadExecutor(); ControlledFiles release = files) {
            store.put(key("a"), value("1"));
            await(files.buildStarted);
            store.put(key("b"), value("2"));
            AtomicReference<Thread> caller = new AtomicReference<>();
            Future<Boolean> interrupted = foreground.submit(() -> {
                caller.set(Thread.currentThread());
                assertThrows(InterruptedIOException.class, () -> store.put(key("c"), value("3")));
                return Thread.currentThread().isInterrupted();
            });
            await(files.waiting);
            caller.get().interrupt();
            assertTrue(interrupted.get(5, TimeUnit.SECONDS));
            assertEquals(2, files.appends.get());
            assertEquals(Optional.empty(), store.get(key("c")));
            assertEquals(Optional.of(value("2")), store.get(key("b")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"create-wal", "seal", "rotation-before", "rotation-after"})
    void rotationFailureStopsTheInstanceButRetainsRecoverySources(String fault) throws Exception {
        ControlledFiles files = new ControlledFiles(fault);
        LsmStore store = LsmStore.open(directory, 11, files);
        try {
            IOException failure = assertThrows(IOException.class, () -> store.put(key("k"), value("1")));
            assertSame(files.failure, failure);
            assertThrows(IllegalStateException.class, () -> store.get(key("k")));
        } finally {
            files.close();
            store.close();
        }
        assertTrue(Files.exists(directory.resolve("1.wal")));
        try (LsmStore recovered = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("1")), recovered.get(key("k")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"write-after", "open-table", "publication-before", "publication-after"})
    void failedOutputOrPublicationIsRecoveredUsingTheActualManifest(String fault) throws Exception {
        ControlledFiles files = new ControlledFiles(fault);
        LsmStore store = LsmStore.open(directory, 11, files);
        try {
            store.put(key("k"), value("1"));
            await(files.buildStarted);
        } finally {
            files.close();
            IOException failure = assertThrows(IOException.class, store::close);
            assertSame(files.failure, failure.getCause());
        }
        assertTrue(Files.exists(directory.resolve("1.wal")));
        assertTrue(Files.exists(directory.resolve("3.sst")));
        try (ManifestFile manifest = ManifestFile.open(directory.resolve("MANIFEST"))) {
            assertEquals(fault.equals("publication-after") ? 0 : 1, manifest.current().frozenWalId());
        }
        try (LsmStore recovered = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("1")), recovered.get(key("k")));
        }
        if (files.preparedReader != null) {
            assertThrows(IllegalStateException.class, () -> files.preparedReader.get(key("k")));
        }
    }

    @Test
    void obsoleteLogDeletionFailureDoesNotUndoPublication() throws Exception {
        ControlledFiles files = new ControlledFiles("delete");
        try (LsmStore store = LsmStore.open(directory, 11, files); ControlledFiles release = files) {
            store.put(key("k"), value("1"));
            await(files.buildStarted);
        }
        assertTrue(Files.exists(directory.resolve("1.wal")));
        try (ManifestFile manifest = ManifestFile.open(directory.resolve("MANIFEST"))) {
            assertEquals(List.of(3L), manifest.current().sstIds());
            assertEquals(0, manifest.current().frozenWalId());
        }
        try (LsmStore recovered = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("1")), recovered.get(key("k")));
        }
    }

    @Test
    void workerStartupFailureClosesAllInitializedResources() {
        ControlledFiles files = new ControlledFiles("start");
        assertThrows(RejectedExecutionException.class, () -> LsmStore.open(directory, 11, files));
        assertNotNull(files.createdWriter);
        assertNotNull(files.createdManifest);
        assertThrows(IllegalStateException.class,
                () -> files.createdWriter.append(new WalRecord(key("k"), value("1"))));
        assertThrows(IllegalStateException.class, files.createdManifest::current);
    }

    @Test
    void closeWaitsForTheWorkerAndPreservesAnInterruptedCallersFlag() throws Exception {
        ControlledFiles files = new ControlledFiles("");
        LsmStore store = LsmStore.open(directory, 11, files);
        try (var closer = Executors.newSingleThreadExecutor(); ControlledFiles release = files) {
            store.put(key("k"), value("1"));
            await(files.buildStarted);
            CountDownLatch closing = new CountDownLatch(1);
            Future<Boolean> closed = closer.submit(() -> {
                Thread.currentThread().interrupt();
                closing.countDown();
                store.close();
                return Thread.currentThread().isInterrupted();
            });
            await(closing);
            assertFalse(closed.isDone());
            assertTrue(files.worker.isAlive());
            files.close();
            assertTrue(closed.get(5, TimeUnit.SECONDS));
            assertFalse(files.worker.isAlive());
        } finally {
            files.close();
            store.close();
        }
        try (LsmStore recovered = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("1")), recovered.get(key("k")));
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out waiting for test boundary");
    }

    private static ByteKey key(String text) {
        return ByteKey.copyOf(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Value value(String text) {
        return Value.copyOf(text.getBytes(StandardCharsets.UTF_8));
    }

    private static final class ControlledFiles extends LsmStore.FileOps implements AutoCloseable {
        private final String fault;
        private final IOException failure = new IOException("injected flush failure");
        private final CountDownLatch buildStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch waiting = new CountDownLatch(1);
        private final CountDownLatch deleted = new CountDownLatch(1);
        private final AtomicInteger appends = new AtomicInteger();
        private final AtomicInteger builds = new AtomicInteger();
        private final AtomicInteger simultaneousBuilds = new AtomicInteger();
        private final AtomicInteger maxSimultaneousBuilds = new AtomicInteger();
        private final Set<Thread> workerThreads = new HashSet<>(); // 仅 worker 写，join 后检查。
        private volatile ManifestState latest;
        private volatile SsTableReader preparedReader;
        private WalWriter createdWriter;
        private ManifestFile createdManifest;
        private Thread worker;

        ControlledFiles(String fault) {
            this.fault = fault;
        }

        @Override
        Thread newWorker(Runnable task) {
            if (fault.equals("start")) { throw new RejectedExecutionException("worker start failed"); }
            worker = super.newWorker(task);
            return worker;
        }

        @Override
        void beforeWait() {
            waiting.countDown();
        }

        @Override
        WalWriter createWal(Path path) throws IOException {
            if (fault.equals("create-wal") && !path.getFileName().toString().equals("1.wal")) {
                throw failure;
            }
            createdWriter = super.createWal(path);
            return createdWriter;
        }

        @Override
        ManifestFile createManifest(Path path, ManifestState initial) throws IOException {
            createdManifest = super.createManifest(path, initial);
            latest = initial;
            return createdManifest;
        }

        @Override
        void closeWal(WalWriter writer) throws IOException {
            super.closeWal(writer);
            if (fault.equals("seal")) { throw failure; }
        }

        @Override
        void append(WalWriter writer, WalRecord record) throws IOException {
            super.append(writer, record);
            appends.incrementAndGet();
        }

        @Override
        void writeTable(Path path, Iterable<SsTableRecord> records) throws IOException {
            workerThreads.add(Thread.currentThread());
            int concurrent = simultaneousBuilds.incrementAndGet();
            maxSimultaneousBuilds.accumulateAndGet(concurrent, Math::max);
            int count = builds.incrementAndGet();
            try {
                buildStarted.countDown();
                if (count == 1 && !release.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("flush test release timed out");
                }
                if (fault.equals("write")) { throw failure; }
                super.writeTable(path, records);
                if (fault.equals("write-after")) { throw failure; }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            } finally {
                simultaneousBuilds.decrementAndGet();
            }
        }

        @Override
        SsTableReader openTable(Path path) throws IOException {
            if (fault.equals("open-table")) { throw failure; }
            preparedReader = super.openTable(path);
            return preparedReader;
        }

        @Override
        void commit(ManifestFile manifest, ManifestState next) throws IOException {
            String phase = next.frozenWalId() == 0 ? "publication" : "rotation";
            if (fault.equals(phase + "-before")) { throw failure; }
            super.commit(manifest, next);
            latest = next;
            if (fault.equals(phase + "-after")) { throw failure; }
        }

        @Override
        void deleteObsolete(Path path) throws IOException {
            if (fault.equals("delete")) { throw failure; }
            super.deleteObsolete(path);
            deleted.countDown();
        }

        @Override
        public void close() {
            release.countDown();
        }
    }
}
