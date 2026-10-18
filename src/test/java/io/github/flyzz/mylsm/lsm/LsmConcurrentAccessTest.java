package io.github.flyzz.mylsm.lsm;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Entry;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class LsmConcurrentAccessTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void twoDiskLookupsOverlapWhileWriteOrCloseWaits(boolean closing) throws Exception {
        seed(1);
        ControlledFiles files = new ControlledFiles();
        files.blockReads = true;
        LsmStore store = LsmStore.open(directory, Long.MAX_VALUE, Integer.MAX_VALUE, files);
        try (var pool = Executors.newFixedThreadPool(3); ControlledFiles release = files) {
            var a = pool.submit(() -> store.get(key("a")));
            var b = pool.submit(() -> store.get(key("b")));
            await(files.readsEntered);
            assertEquals(2, files.maxReads.get());
            CountDownLatch called = new CountDownLatch(1);
            var blocked = pool.submit(() -> {
                called.countDown();
                if (closing) {
                    store.close();
                } else {
                    store.put(key("a"), value("new"));
                }
                return null;
            });
            await(called);
            assertThrows(TimeoutException.class, () -> blocked.get(100, TimeUnit.MILLISECONDS));
            files.readRelease.countDown();
            assertEquals(Optional.of(value("0")), a.get(5, TimeUnit.SECONDS));
            assertEquals(Optional.of(value("b0")), b.get(5, TimeUnit.SECONDS));
            blocked.get(5, TimeUnit.SECONDS);
            if (closing) {
                assertThrows(IllegalStateException.class, () -> store.get(key("a")));
            } else {
                assertEquals(Optional.of(value("new")), store.get(key("a")));
            }
        } finally {
            files.close();
            store.close();
        }
    }

    @Test
    void compactionCannotPublishOrReclaimInputsUntilReadersExit() throws Exception {
        seed(2);
        ControlledFiles files = new ControlledFiles();
        files.blockReads = true;
        files.blockCompaction = true;
        try (LsmStore store = LsmStore.open(directory, Long.MAX_VALUE, 2, files);
             var pool = Executors.newFixedThreadPool(2); ControlledFiles release = files) {
            await(files.compactStarted);
            var a = pool.submit(() -> store.get(key("a")));
            var b = pool.submit(() -> store.get(key("b")));
            await(files.readsEntered);
            files.compactRelease.countDown();
            await(files.compactBuilt);
            assertEquals(2, files.latest.sstIds().size());
            assertEquals(0, files.retired.get());
            files.readRelease.countDown();
            assertEquals(Optional.of(value("1")), a.get(5, TimeUnit.SECONDS));
            assertEquals(Optional.of(value("b1")), b.get(5, TimeUnit.SECONDS));
            store.compact(); // 等待先前自动任务完整结束，随后手动请求无操作。
            assertEquals(1, files.latest.sstIds().size());
            assertEquals(2, files.retired.get());
            assertEquals(Optional.of(Tombstone.INSTANCE), store.get(key("ghost")));
        }
    }

    @Test
    void twoReadFailuresReleaseReadLocksBeforeRecordingFailureAndClosing() throws Exception {
        seed(1);
        ControlledFiles files = new ControlledFiles();
        files.blockReads = true;
        files.failReads = true;
        LsmStore store = LsmStore.open(directory, Long.MAX_VALUE, Integer.MAX_VALUE, files);
        try (var pool = Executors.newFixedThreadPool(2); ControlledFiles release = files) {
            var a = pool.submit(() -> store.get(key("a")));
            var b = pool.submit(() -> store.get(key("b")));
            await(files.readsEntered);
            files.readRelease.countDown();
            for (var result : List.of(a, b)) {
                var error = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
                assertSame(files.failure, error.getCause());
            }
            assertThrows(IllegalStateException.class, () -> store.put(key("x"), value("x")));
        } finally {
            files.close();
            store.close();
        }
        try (LsmStore recovered = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("0")), recovered.get(key("a")));
        }
    }

    @Test
    void concurrentWritersReadsFlushesAndCompactionsPreserveWalOrderOnReopen() throws Exception {
        ControlledFiles files = new ControlledFiles();
        try (LsmStore store = LsmStore.open(directory, 80, 3, files);
             var pool = Executors.newFixedThreadPool(6); ControlledFiles release = files) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> work = new ArrayList<>();
            for (int n = 0; n < 4; n++) {
                int thread = n;
                work.add(pool.submit(() -> {
                    await(start);
                    for (int i = 0; i < 30; i++) {
                        ByteKey key = key("k" + i % 5);
                        if ((thread + i) % 4 == 0) {
                            store.delete(key);
                        } else {
                            store.put(key, value(thread + ":" + i));
                        }
                        store.get(key);
                    }
                    return null;
                }));
            }
            work.add(pool.submit(() -> {
                await(start);
                for (int i = 0; i < 8; i++) store.flush();
                return null;
            }));
            work.add(pool.submit(() -> {
                await(start);
                for (int i = 0; i < 4; i++) store.compact();
                return null;
            }));
            start.countDown();
            for (var result : work) result.get(15, TimeUnit.SECONDS);
            assertEquals(120, files.writes.size());
            var expected = new HashMap<ByteKey, Entry>();
            for (var record : files.writes) expected.put(record.key(), record.entry());
            for (var entry : expected.entrySet()) {
                assertEquals(Optional.of(entry.getValue()), store.get(entry.getKey()));
            }
        }
        try (LsmStore recovered = LsmStore.open(directory)) {
            var expected = new HashMap<ByteKey, Entry>();
            for (var record : files.writes) expected.put(record.key(), record.entry());
            for (var entry : expected.entrySet()) {
                assertEquals(Optional.of(entry.getValue()), recovered.get(entry.getKey()));
            }
        }
    }

    @Test
    void concurrentManualRequestsKeepTheirResultsWhileNewWritesCreateAFrozenTable() throws Exception {
        seed(2);
        ControlledFiles files = new ControlledFiles();
        files.blockCompaction = true;
        try (LsmStore store = LsmStore.open(directory, 11, Integer.MAX_VALUE, files);
             var pool = Executors.newFixedThreadPool(2); ControlledFiles release = files) {
            var first = pool.submit(() -> { store.compact(); return null; });
            files.awaitWait();
            await(files.compactStarted);
            var second = pool.submit(() -> { store.compact(); return null; });
            files.awaitWait();
            store.put(key("c"), value("3")); // 第一项执行时产生 frozen，第二项须保留自己的请求。
            assertFalse(first.isDone());
            assertFalse(second.isDone());
            files.compactRelease.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(2, files.compactions.get());
            assertEquals(1, files.latest.sstIds().size());
            assertEquals(Optional.of(value("3")), store.get(key("c")));
        }
    }

    @Test
    void concurrentFlushesReturnAtTheirTargetEvenWhenANewerFrozenTableIsBlocked() throws Exception {
        ControlledFiles files = new ControlledFiles();
        files.blockFlushes = true;
        files.blockSecondCommit = true;
        try (LsmStore store = LsmStore.open(directory, 11, Integer.MAX_VALUE, files);
             var pool = Executors.newFixedThreadPool(3); ControlledFiles release = files) {
            store.put(key("a"), value("1"));
            await(files.flushStarted[0]);
            store.put(key("b"), value("2"));
            var first = pool.submit(() -> { store.flush(); return null; });
            files.awaitWait();
            var second = pool.submit(() -> { store.flush(); return null; });
            files.awaitWait();
            files.flushRelease[0].countDown();
            await(files.flushStarted[1]);
            store.put(key("c"), value("3"));
            files.flushRelease[1].countDown();
            await(files.secondCommit); // 此时 worker 持写锁，两个旧调用仍在 Condition 上。
            AtomicReference<Thread> laterThread = new AtomicReference<>();
            var later = pool.submit(() -> {
                laterThread.set(Thread.currentThread());
                store.flush();
                return null;
            });
            awaitParked(laterThread); // 新 flush 已排在旧 Condition 等待者前面的写锁队列。
            files.commitRelease.countDown();
            await(files.flushStarted[2]);
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertFalse(later.isDone());
            assertTrue(store.frozenBytes() > 0);
            assertEquals(2, files.latest.sstIds().size());
            files.flushRelease[2].countDown();
            later.get(5, TimeUnit.SECONDS);
            assertEquals(3, files.latest.sstIds().size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentCloseCallersWaitForTheSameWorkerResultAndPreserveInterruption(boolean failing)
            throws Exception {
        ControlledFiles files = new ControlledFiles();
        files.blockFlushes = true;
        files.failFlush = failing;
        LsmStore store = LsmStore.open(directory, 11, Integer.MAX_VALUE, files);
        try (var pool = Executors.newFixedThreadPool(2); ControlledFiles release = files) {
            store.put(key("a"), value("1"));
            await(files.flushStarted[0]);
            AtomicReference<Thread> owner = new AtomicReference<>();
            var first = pool.submit(() -> {
                owner.set(Thread.currentThread());
                store.close();
                return null;
            });
            awaitParked(owner); // owner 已在锁外 join worker。
            var second = pool.submit(() -> {
                Thread.currentThread().interrupt();
                try {
                    store.close();
                    return null;
                } catch (IOException error) {
                    assertTrue(Thread.currentThread().isInterrupted());
                    return error;
                } finally {
                    assertTrue(Thread.currentThread().isInterrupted());
                }
            });
            files.awaitWait();
            assertFalse(first.isDone());
            assertFalse(second.isDone());
            files.flushRelease[0].countDown();
            if (failing) {
                var error = assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
                assertInstanceOf(IOException.class, error.getCause());
                assertSame(files.failure, error.getCause().getCause());
                assertSame(error.getCause(), second.get(5, TimeUnit.SECONDS));
            } else {
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
            }
            store.close();
            assertThrows(IllegalStateException.class, store::flush);
        } finally {
            files.close();
            store.close();
        }
        try (LsmStore recovered = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("1")), recovered.get(key("a")));
        }
    }

    private void seed(int count) throws IOException {
        try (LsmStore store = LsmStore.open(directory, Long.MAX_VALUE, Integer.MAX_VALUE)) {
            for (int i = 0; i < count; i++) {
                store.put(key("a"), value(Integer.toString(i)));
                store.put(key("b"), value("b" + i));
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
        assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out at store boundary");
    }

    private static void awaitParked(AtomicReference<Thread> reference) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            Thread thread = reference.get();
            if (thread != null && (thread.getState() == Thread.State.WAITING
                    || thread.getState() == Thread.State.TIMED_WAITING)) return;
            Thread.yield();
        }
        throw new AssertionError("caller did not park at the expected boundary");
    }

    private static void gate(CountDownLatch started, CountDownLatch release) throws IOException {
        started.countDown();
        try {
            if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("store test gate timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }

    private static final class ControlledFiles extends LsmStore.FileOps implements AutoCloseable {
        private final CountDownLatch readsEntered = new CountDownLatch(2);
        private final CountDownLatch readRelease = new CountDownLatch(1);
        private final CountDownLatch compactStarted = new CountDownLatch(1);
        private final CountDownLatch compactRelease = new CountDownLatch(1);
        private final CountDownLatch compactBuilt = new CountDownLatch(1);
        private final CountDownLatch[] flushStarted = {new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)};
        private final CountDownLatch[] flushRelease = {new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)};
        private final CountDownLatch secondCommit = new CountDownLatch(1);
        private final CountDownLatch commitRelease = new CountDownLatch(1);
        private final BlockingQueue<Boolean> waits = new LinkedBlockingQueue<>();
        private final AtomicInteger reading = new AtomicInteger();
        private final AtomicInteger maxReads = new AtomicInteger();
        private final AtomicInteger retired = new AtomicInteger();
        private final AtomicInteger compactions = new AtomicInteger();
        private final List<WalRecord> writes = new ArrayList<>(); // append 在存储写锁内串行。
        private final IOException failure = new IOException("injected concurrent store failure");
        private volatile ManifestState latest;
        private boolean blockReads;
        private boolean failReads;
        private boolean blockCompaction;
        private boolean blockFlushes;
        private boolean blockSecondCommit;
        private boolean failFlush;
        private boolean compacted;
        private int flushes;
        private int flushPublications;

        @Override
        ManifestFile createManifest(Path path, ManifestState initial) throws IOException {
            var result = super.createManifest(path, initial);
            latest = initial;
            return result;
        }

        @Override
        ManifestFile openManifest(Path path) throws IOException {
            var result = super.openManifest(path);
            latest = result.current();
            return result;
        }

        @Override
        Optional<Entry> readTable(SsTableReader reader, ByteKey key) throws IOException {
            if (!blockReads) return super.readTable(reader, key);
            int count = reading.incrementAndGet();
            maxReads.accumulateAndGet(count, Math::max);
            try {
                gate(readsEntered, readRelease);
                if (failReads) throw failure;
                return super.readTable(reader, key);
            } finally {
                reading.decrementAndGet();
            }
        }

        @Override
        void append(WalWriter writer, WalRecord record) throws IOException {
            super.append(writer, record);
            writes.add(record);
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
            if (blockFlushes) {
                int index = flushes++;
                if (index < flushStarted.length) gate(flushStarted[index], flushRelease[index]);
            }
            if (failFlush) throw failure;
            super.writeTable(path, records);
        }

        @Override
        void writeCompaction(Path path, List<SsTableReader> readers) throws IOException {
            compactions.incrementAndGet();
            if (blockCompaction) gate(compactStarted, compactRelease);
            super.writeCompaction(path, readers);
            compactBuilt.countDown();
        }

        @Override
        void commit(ManifestFile manifest, ManifestState next) throws IOException {
            boolean flush = manifest.current().frozenWalId() != 0 && next.frozenWalId() == 0;
            compacted |= manifest.current().sstIds().size() > 1 && next.sstIds().size() == 1;
            super.commit(manifest, next);
            latest = next;
            if (flush && ++flushPublications == 2 && blockSecondCommit) gate(secondCommit, commitRelease);
        }

        @Override
        void closeTable(SsTableReader reader) throws IOException {
            super.closeTable(reader);
            if (compacted) retired.incrementAndGet();
        }

        @Override
        public void close() {
            readRelease.countDown();
            compactRelease.countDown();
            commitRelease.countDown();
            for (var latch : flushRelease) latch.countDown();
        }
    }
}
