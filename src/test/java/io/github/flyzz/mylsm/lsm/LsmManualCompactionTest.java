package io.github.flyzz.mylsm.lsm;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;
import io.github.flyzz.mylsm.sstable.SsTableReader;
import io.github.flyzz.mylsm.sstable.SsTableRecord;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(25)
class LsmManualCompactionTest {
    @TempDir
    Path directory;

    @Test
    void flushesExistingFrozenAndActiveBeforeCompactingBelowAutomaticThreshold() throws Exception {
        seed(1);
        ControlledFiles files = new ControlledFiles();
        files.blockFlush = true;
        try (LsmStore store = LsmStore.open(directory, 11, Integer.MAX_VALUE, files);
             var caller = Executors.newSingleThreadExecutor(); ControlledFiles release = files) {
            store.put(key("k"), value("1")); // 恰好达到目标并生成 frozen。
            await(files.flushStarted);
            store.put(key("k"), value("2"));
            var result = caller.submit(() -> { store.compact(); return null; });
            files.awaitWait();
            assertEquals(0, files.compactions.get());
            files.flushRelease.countDown();
            await(files.buildStarted);
            assertFalse(result.isDone());
            assertEquals(3, files.inputCount);
            assertEquals(0, store.activeBytes());
            assertEquals(0, store.frozenBytes());
            files.buildRelease.countDown();
            result.get(5, TimeUnit.SECONDS);
            assertEquals(1, files.latest.sstIds().size());
            assertEquals(Optional.of(value("2")), store.get(key("k")));
            assertEquals(Optional.of(Tombstone.INSTANCE), store.get(key("ghost")));
            store.put(key("k"), value("3"));
            store.compact();
            assertEquals(1, files.latest.sstIds().size());
        }
        try (LsmStore recovered = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("3")), recovered.get(key("k")));
            assertEquals(Optional.of(Tombstone.INSTANCE), recovered.get(key("ghost")));
        }
    }

    @Test
    void emptyAndSingleTableRequestsDoNotAllocateFilesOrChangeManifest() throws IOException {
        ControlledFiles files = new ControlledFiles();
        try (LsmStore store = LsmStore.open(directory, Long.MAX_VALUE, Integer.MAX_VALUE, files)) {
            byte[] initial = Files.readAllBytes(directory.resolve("MANIFEST"));
            store.compact();
            store.compact();
            assertArrayEquals(initial, Files.readAllBytes(directory.resolve("MANIFEST")));
            assertEquals(List.of(), files.latest.sstIds());
            store.put(key("k"), value("1"));
            store.compact();
            assertEquals(List.of(3L), files.latest.sstIds());
            byte[] single = Files.readAllBytes(directory.resolve("MANIFEST"));
            store.compact();
            assertArrayEquals(single, Files.readAllBytes(directory.resolve("MANIFEST")));
            assertEquals(4, store.allocateFileId()); // 无操作没有消耗编号。
            assertEquals(0, files.compactions.get());
        }
    }

    @Test
    void waitsThroughPublicationAndResourceReclamationOnTheBackgroundThread() throws Exception {
        seed(2);
        ControlledFiles files = new ControlledFiles();
        files.blockReclaim = true;
        try (LsmStore store = LsmStore.open(directory, Long.MAX_VALUE, Integer.MAX_VALUE, files);
             var caller = Executors.newSingleThreadExecutor(); ControlledFiles release = files) {
            AtomicReference<Thread> callingThread = new AtomicReference<>();
            var result = caller.submit(() -> {
                callingThread.set(Thread.currentThread());
                store.compact();
                return null;
            });
            await(files.buildStarted);
            assertNotSame(callingThread.get(), files.buildThread);
            assertFalse(result.isDone()); // 已取走请求仍未完成。
            files.buildRelease.countDown();
            await(files.reclaimStarted);
            assertEquals(1, files.latest.sstIds().size());
            assertFalse(result.isDone()); // Manifest 提交也不等于全部任务完成。
            files.reclaimRelease.countDown();
            result.get(5, TimeUnit.SECONDS);
            assertEquals(1, files.compactions.get());
        }
    }

    @Test
    void queuedManualRequestUsesTheCompletedAutomaticResultWithoutRewritingIt() throws Exception {
        seed(2);
        ControlledFiles files = new ControlledFiles();
        try (LsmStore store = LsmStore.open(directory, Long.MAX_VALUE, 2, files);
             var caller = Executors.newSingleThreadExecutor(); ControlledFiles release = files) {
            await(files.buildStarted);
            var result = caller.submit(() -> { store.compact(); return null; });
            files.awaitWait(); // 自动任务执行中，手动请求已登记。
            assertFalse(result.isDone());
            files.buildRelease.countDown();
            result.get(5, TimeUnit.SECONDS);
            assertEquals(1, files.compactions.get());
            assertEquals(1, files.latest.sstIds().size());
        }
    }

    @Test
    void waitsForAutomaticWorkThenFlushesMemoryAndCompactsTheResult() throws Exception {
        seed(2);
        ControlledFiles files = new ControlledFiles();
        try (LsmStore store = LsmStore.open(directory, Long.MAX_VALUE, 2, files);
             var caller = Executors.newSingleThreadExecutor(); ControlledFiles release = files) {
            await(files.buildStarted);
            store.put(key("k"), value("new"));
            var result = caller.submit(() -> { store.compact(); return null; });
            files.awaitWait(); // frozen 等待正在执行的自动压缩。
            assertFalse(result.isDone());
            files.buildRelease.countDown();
            result.get(5, TimeUnit.SECONDS);
            assertEquals(2, files.compactions.get());
            assertEquals(1, files.latest.sstIds().size());
            assertEquals(0, store.activeBytes());
            assertEquals(0, store.frozenBytes());
            assertEquals(Optional.of(value("new")), store.get(key("k")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptionDoesNotCancelPendingOrRunningRequestAndAnotherCallCannotOverwriteIt(boolean automatic)
            throws Exception {
        seed(2);
        ControlledFiles files = new ControlledFiles();
        try (LsmStore store = LsmStore.open(directory, Long.MAX_VALUE,
                automatic ? 2 : Integer.MAX_VALUE, files);
             var caller = Executors.newSingleThreadExecutor(); ControlledFiles release = files) {
            if (automatic) {
                await(files.buildStarted);
            }
            AtomicReference<Thread> callingThread = new AtomicReference<>();
            var first = caller.submit(() -> {
                callingThread.set(Thread.currentThread());
                assertThrows(InterruptedIOException.class, store::compact);
                return Thread.currentThread().isInterrupted();
            });
            files.awaitWait();
            await(files.buildStarted);
            callingThread.get().interrupt();
            assertTrue(first.get(5, TimeUnit.SECONDS));
            var second = caller.submit(() -> { store.compact(); return null; });
            files.awaitWait();
            assertFalse(second.isDone());
            files.buildRelease.countDown();
            second.get(5, TimeUnit.SECONDS);
            assertEquals(1, files.compactions.get());
            store.put(key("continued"), value("ok"));
            assertEquals(Optional.of(value("ok")), store.get(key("continued")));
        }
    }

    @Test
    void interruptionDuringInitialFlushDoesNotSubmitCompactionOrCancelFlush() throws Exception {
        seed(1);
        ControlledFiles files = new ControlledFiles();
        files.blockFlush = true;
        try (LsmStore store = LsmStore.open(directory, Long.MAX_VALUE, Integer.MAX_VALUE, files);
             var caller = Executors.newSingleThreadExecutor(); ControlledFiles release = files) {
            store.put(key("k"), value("new"));
            AtomicReference<Thread> callingThread = new AtomicReference<>();
            var result = caller.submit(() -> {
                callingThread.set(Thread.currentThread());
                assertThrows(InterruptedIOException.class, store::compact);
                return Thread.currentThread().isInterrupted();
            });
            files.awaitWait();
            await(files.flushStarted);
            callingThread.get().interrupt();
            assertTrue(result.get(5, TimeUnit.SECONDS));
            files.flushRelease.countDown();
            store.flush();
            assertEquals(2, files.latest.sstIds().size());
            assertEquals(0, files.compactions.get());
            assertEquals(Optional.of(value("new")), store.get(key("k")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"write", "commit", "old-close", "pending-auto"})
    void backgroundFailureReachesTheWaiterIncludingAfterSuccessfulPublication(String fault) throws Exception {
        seed(2);
        ControlledFiles files = new ControlledFiles();
        files.fault = fault.equals("pending-auto") ? "write" : fault;
        LsmStore store = LsmStore.open(directory, Long.MAX_VALUE,
                fault.equals("pending-auto") ? 2 : Integer.MAX_VALUE, files);
        try (var caller = Executors.newSingleThreadExecutor(); ControlledFiles release = files) {
            var result = caller.submit(() -> { store.compact(); return null; });
            files.awaitWait();
            await(files.buildStarted);
            files.buildRelease.countDown();
            var error = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, error.getCause());
            assertSame(files.failure, error.getCause().getCause());
            assertEquals(fault.equals("old-close") ? 1 : 2, files.latest.sstIds().size());
            assertThrows(IllegalStateException.class, store::compact);
        } finally {
            files.close();
            assertSame(files.failure, assertThrows(IOException.class, store::close).getCause());
        }
        try (LsmStore recovered = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("1")), recovered.get(key("k")));
            assertEquals(Optional.of(Tombstone.INSTANCE), recovered.get(key("ghost")));
        }
    }

    @Test
    void initialFlushFailureIsReportedWithoutSubmittingACompaction() throws Exception {
        ControlledFiles files = new ControlledFiles();
        files.fault = "flush";
        LsmStore store = LsmStore.open(directory, Long.MAX_VALUE, Integer.MAX_VALUE, files);
        try {
            store.put(key("k"), value("1"));
            assertSame(files.failure, assertThrows(IOException.class, store::compact).getCause());
            assertEquals(0, files.compactions.get());
        } finally {
            assertThrows(IOException.class, store::close);
        }
        try (LsmStore recovered = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("1")), recovered.get(key("k")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closeRejectsPendingRequestsButAllowsStartedRequestsToFinish(boolean pending) throws Exception {
        seed(2);
        ControlledFiles files = new ControlledFiles();
        LsmStore store = LsmStore.open(directory, Long.MAX_VALUE, pending ? 2 : Integer.MAX_VALUE, files);
        try (var caller = Executors.newSingleThreadExecutor();
             var closer = Executors.newSingleThreadExecutor(); ControlledFiles release = files) {
            if (pending) {
                await(files.buildStarted);
            }
            var result = caller.submit(() -> { store.compact(); return null; });
            files.awaitWait();
            await(files.buildStarted);
            CountDownLatch closeCalled = new CountDownLatch(1);
            var closed = closer.submit(() -> { closeCalled.countDown(); store.close(); return null; });
            await(closeCalled);
            if (pending) {
                var error = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
                assertInstanceOf(IOException.class, error.getCause());
                assertTrue(error.getCause().getCause().getMessage().contains("before manual compaction"));
            } else {
                assertFalse(result.isDone());
            }
            assertFalse(closed.isDone());
            files.buildRelease.countDown();
            closed.get(5, TimeUnit.SECONDS);
            if (!pending) {
                result.get(5, TimeUnit.SECONDS);
            }
            assertThrows(IllegalStateException.class, store::compact);
        } finally {
            files.close();
            store.close();
        }
        try (LsmStore recovered = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("1")), recovered.get(key("k")));
        }
    }

    private void seed(int count) throws IOException {
        try (LsmStore store = LsmStore.open(directory, Long.MAX_VALUE, Integer.MAX_VALUE)) {
            for (int i = 0; i < count; i++) {
                store.put(key("k"), value(Integer.toString(i)));
                store.delete(key("ghost"));
                store.flush();
            }
        }
    }

    private static ByteKey key(String text) {
        return ByteKey.copyOf(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Value value(String text) {
        return Value.copyOf(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out at manual compaction boundary");
    }

    private static void gate(CountDownLatch started, CountDownLatch release) throws IOException {
        started.countDown();
        try {
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IOException("manual compaction test release timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }

    private static final class ControlledFiles extends LsmStore.FileOps implements AutoCloseable {
        private final CountDownLatch buildStarted = new CountDownLatch(1);
        private final CountDownLatch buildRelease = new CountDownLatch(1);
        private final CountDownLatch flushStarted = new CountDownLatch(1);
        private final CountDownLatch flushRelease = new CountDownLatch(1);
        private final CountDownLatch reclaimStarted = new CountDownLatch(1);
        private final CountDownLatch reclaimRelease = new CountDownLatch(1);
        private final BlockingQueue<Boolean> waits = new LinkedBlockingQueue<>();
        private final AtomicInteger compactions = new AtomicInteger();
        private final IOException failure = new IOException("injected manual compaction failure");
        private volatile ManifestState latest;
        private volatile Thread buildThread;
        private volatile int inputCount;
        private boolean blockFlush;
        private boolean blockReclaim;
        private boolean published;
        private String fault = "";

        @Override
        ManifestFile createManifest(Path path, ManifestState initial) throws IOException {
            ManifestFile result = super.createManifest(path, initial);
            latest = initial;
            return result;
        }

        @Override
        ManifestFile openManifest(Path path) throws IOException {
            ManifestFile result = super.openManifest(path);
            latest = result.current();
            return result;
        }

        @Override
        void beforeWait() {
            waits.add(true);
        }

        void awaitWait() throws InterruptedException {
            assertNotNull(waits.poll(5, TimeUnit.SECONDS), "caller did not wait");
        }

        @Override
        void writeTable(Path path, Iterable<SsTableRecord> records) throws IOException {
            if (blockFlush) {
                gate(flushStarted, flushRelease);
            }
            if (fault.equals("flush")) {
                throw failure;
            }
            super.writeTable(path, records);
        }

        @Override
        void writeCompaction(Path path, List<SsTableReader> inputs) throws IOException {
            compactions.incrementAndGet();
            inputCount = inputs.size();
            buildThread = Thread.currentThread();
            gate(buildStarted, buildRelease);
            if (fault.equals("write")) {
                throw failure;
            }
            super.writeCompaction(path, inputs);
        }

        @Override
        void commit(ManifestFile manifest, ManifestState next) throws IOException {
            boolean replacement = manifest.current().sstIds().size() >= 2 && next.sstIds().size() == 1;
            if (replacement && fault.equals("commit")) {
                throw failure;
            }
            super.commit(manifest, next);
            published |= replacement;
            latest = next;
        }

        @Override
        void closeTable(SsTableReader reader) throws IOException {
            super.closeTable(reader);
            if (published && fault.equals("old-close")) {
                throw failure;
            }
        }

        @Override
        void deleteObsolete(Path path) throws IOException {
            if (blockReclaim && path.toString().endsWith(".sst")) {
                gate(reclaimStarted, reclaimRelease);
            }
            super.deleteObsolete(path);
        }

        @Override
        public void close() {
            buildRelease.countDown();
            flushRelease.countDown();
            reclaimRelease.countDown();
        }
    }
}
