package io.github.flyzz.mylsm.sstable;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.file.StandardOpenOption.READ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class SsTableConcurrentReadTest {
    @TempDir
    Path directory;

    @Test
    void twoRealBlockReadsOverlapAndKeepIndependentPositionsAndBuffers() throws Exception {
        Path path = table();
        BlockingInput input = new BlockingInput(path, 2);
        try (SsTableReader reader = SsTableReader.open(input);
             var pool = Executors.newFixedThreadPool(2); AutoCloseable release = () -> input.release.countDown()) {
            input.armed = true;
            var a = pool.submit(() -> reader.get(key(1)));
            var b = pool.submit(() -> reader.get(key(2)));
            await(input.entered);
            assertEquals(2, input.maxReads.get());
            input.release.countDown();
            assertEquals(Optional.of(value(1)), a.get(5, TimeUnit.SECONDS));
            assertEquals(Optional.of(value(2)), b.get(5, TimeUnit.SECONDS));
            assertEquals(Optional.empty(), reader.get(key(3)));
        }
        assertEquals(1, input.closes.get());
    }

    @Test
    void closeWaitsForBothActiveReadsAndClosesTheChannelOnlyOnce() throws Exception {
        BlockingInput input = new BlockingInput(table(), 2);
        SsTableReader reader = SsTableReader.open(input);
        try (var pool = Executors.newFixedThreadPool(4); AutoCloseable release = () -> input.release.countDown()) {
            input.armed = true;
            var a = pool.submit(() -> reader.get(key(1)));
            var b = pool.submit(() -> reader.get(key(2)));
            await(input.entered);
            CountDownLatch closing = new CountDownLatch(2);
            var first = pool.submit(() -> { closing.countDown(); reader.close(); return null; });
            var second = pool.submit(() -> { closing.countDown(); reader.close(); return null; });
            await(closing);
            assertThrows(TimeoutException.class, () -> first.get(100, TimeUnit.MILLISECONDS));
            assertFalse(second.isDone());
            assertEquals(0, input.closes.get());
            input.release.countDown();
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(1, input.closes.get());
            assertThrows(IllegalStateException.class, () -> reader.get(key(1)));
        } finally {
            input.release.countDown();
            reader.close();
        }
    }

    @Test
    void concurrentIoFailuresDoNotLoseTheFailureStateOrDeadlockClose() throws Exception {
        BlockingInput input = new BlockingInput(table(), 2);
        try (SsTableReader reader = SsTableReader.open(input);
             var pool = Executors.newFixedThreadPool(2); AutoCloseable release = () -> input.release.countDown()) {
            input.fail = true;
            input.armed = true;
            var a = pool.submit(() -> reader.get(key(1)));
            var b = pool.submit(() -> reader.get(key(2)));
            await(input.entered);
            input.release.countDown();
            for (var result : List.of(a, b)) {
                var error = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
                assertSame(input.failure, error.getCause());
            }
            assertThrows(IllegalStateException.class, () -> reader.get(key(1)));
            assertThrows(IllegalStateException.class, reader::openCursor);
        }
        assertEquals(1, input.closes.get());
    }

    @Test
    void pausedCursorDoesNotBlockPointLookupsOrShareTheirPosition() throws Exception {
        try (SsTableReader reader = SsTableReader.open(table());
             var pool = Executors.newSingleThreadExecutor();
             var cursor = reader.openCursor()) {
            assertTrue(cursor.next());
            var result = pool.submit(() -> reader.get(key(2)));
            assertEquals(Optional.of(value(2)), result.get(5, TimeUnit.SECONDS));
            assertEquals(key(1), cursor.record().key());
            cursor.close();
            assertEquals(Optional.of(value(1)), reader.get(key(1)));
        }
    }

    @Test
    void scanCursorAndPointLookupShareOneReaderAndOverlapTheirBlockReads() throws Exception {
        BlockingInput input = new BlockingInput(table(), 3);
        try (SsTableReader reader = SsTableReader.open(input);
             var pool = Executors.newFixedThreadPool(3); AutoCloseable release = () -> input.release.countDown()) {
            input.armed = true;
            var scan = pool.submit(() -> {
                var records = new ArrayList<SsTableRecord>();
                reader.scan(records::add);
                return records;
            });
            var cursor = pool.submit(() -> {
                var records = new ArrayList<SsTableRecord>();
                try (var context = reader.openCursor()) {
                    while (context.next()) records.add(context.record());
                }
                return records;
            });
            var lookup = pool.submit(() -> reader.get(key(2)));
            await(input.entered);
            assertEquals(3, input.maxReads.get());
            input.release.countDown();
            List<SsTableRecord> expected = List.of(new SsTableRecord(key(1), value(1)),
                    new SsTableRecord(key(2), value(2)));
            assertEquals(expected, scan.get(5, TimeUnit.SECONDS));
            assertEquals(expected, cursor.get(5, TimeUnit.SECONDS));
            assertEquals(Optional.of(value(2)), lookup.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, input.closes.get());
    }

    @Test
    void closeWaitsForAnActiveCursorStepAndInvalidatesAllPausedContexts() throws Exception {
        BlockingInput input = new BlockingInput(table(), 1);
        SsTableReader reader = SsTableReader.open(input);
        try (var paused = reader.openCursor(); var untouched = reader.openCursor();
             var pool = Executors.newFixedThreadPool(2); AutoCloseable release = () -> input.release.countDown()) {
            assertTrue(paused.next());
            input.armed = true;
            var advancing = pool.submit(() -> {
                try (var context = reader.openCursor()) {
                    return context.next();
                }
            });
            await(input.entered);
            CountDownLatch closing = new CountDownLatch(1);
            var closed = pool.submit(() -> { closing.countDown(); reader.close(); return null; });
            await(closing);
            assertThrows(TimeoutException.class, () -> closed.get(100, TimeUnit.MILLISECONDS));
            assertEquals(0, input.closes.get());
            input.release.countDown();
            assertTrue(advancing.get(5, TimeUnit.SECONDS));
            closed.get(5, TimeUnit.SECONDS); // 无须先关闭 paused/untouched。
            assertEquals(1, input.closes.get());
            for (var context : List.of(paused, untouched)) {
                assertThrows(IllegalStateException.class, context::next);
                assertThrows(IllegalStateException.class, context::record);
            }
        } finally {
            input.release.countDown();
            reader.close();
        }
    }

    @Test
    void oneContextIoFailureInvalidatesCachedPositionsInEveryOtherContext() throws Exception {
        BlockingInput input = new BlockingInput(table(), 1);
        try (SsTableReader reader = SsTableReader.open(input); var paused = reader.openCursor();
             var pool = Executors.newSingleThreadExecutor(); AutoCloseable release = () -> input.release.countDown()) {
            assertTrue(paused.next());
            assertEquals(key(1), paused.record().key());
            input.fail = true;
            input.armed = true;
            var failing = pool.submit(() -> {
                try (var context = reader.openCursor()) {
                    return context.next();
                }
            });
            await(input.entered);
            input.release.countDown();
            var error = assertThrows(ExecutionException.class, () -> failing.get(5, TimeUnit.SECONDS));
            assertSame(input.failure, error.getCause());
            assertThrows(IllegalStateException.class, paused::record);
            assertThrows(IllegalStateException.class, paused::next);
            assertThrows(IllegalStateException.class, () -> reader.get(key(1)));
            assertEquals(0, input.closes.get());
        }
        assertEquals(1, input.closes.get());
    }

    @Test
    void callbackFailureOnlyDiscardsItsOwnContextAndLeavesAnotherCursorUnchanged() throws Exception {
        try (SsTableReader reader = SsTableReader.open(table()); var paused = reader.openCursor()) {
            assertTrue(paused.next());
            var failure = new IllegalArgumentException("consumer failed");
            assertSame(failure, assertThrows(IllegalArgumentException.class,
                    () -> reader.scan(record -> { throw failure; })));
            assertEquals(key(1), paused.record().key());
            assertTrue(paused.next());
            assertEquals(key(2), paused.record().key());
            assertEquals(Optional.of(value(1)), reader.get(key(1)));
        }
    }

    @Test
    void callbackRunsOutsideReaderLockSoCloseCanCompleteWhileItIsPaused() throws Exception {
        SsTableReader reader = SsTableReader.open(table());
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch callbackRelease = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2); AutoCloseable release = callbackRelease::countDown) {
            var scanning = pool.submit(() -> {
                reader.scan(record -> {
                    callbackEntered.countDown();
                    try {
                        if (!callbackRelease.await(10, TimeUnit.SECONDS)) throw new AssertionError("callback timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                });
                return null;
            });
            await(callbackEntered);
            var closing = pool.submit(() -> { reader.close(); return null; });
            closing.get(5, TimeUnit.SECONDS); // 回调仍暂停，也能取得生命周期写锁。
            assertFalse(scanning.isDone());
            callbackRelease.countDown();
            var error = assertThrows(ExecutionException.class, () -> scanning.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, error.getCause());
        } finally {
            callbackRelease.countDown();
            reader.close();
        }
    }

    private Path table() throws IOException {
        Path path = directory.resolve("table.sst");
        // 每条超过分块目标，确保两个键读取不同的块。
        SsTableWriter.write(path, List.of(new SsTableRecord(key(1), value(1)),
                new SsTableRecord(key(2), value(2))));
        return path;
    }

    private static ByteKey key(int n) {
        return ByteKey.copyOf(new byte[]{(byte) n});
    }

    private static Value value(int n) {
        byte[] bytes = new byte[6000];
        java.util.Arrays.fill(bytes, (byte) n);
        return Value.copyOf(bytes);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out at Reader boundary");
    }

    private static final class BlockingInput implements SsTableReader.ReadIo {
        private final FileChannel channel;
        private final CountDownLatch entered;
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger reading = new AtomicInteger();
        private final AtomicInteger maxReads = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private final IOException failure = new IOException("injected concurrent read failure");
        private volatile boolean armed;
        private boolean fail;

        BlockingInput(Path path, int count) throws IOException {
            channel = FileChannel.open(path, READ);
            entered = new CountDownLatch(count);
        }

        @Override
        public long size() throws IOException {
            return channel.size();
        }

        @Override
        public int read(ByteBuffer buffer, long offset) throws IOException {
            if (!armed) {
                return channel.read(buffer, offset);
            }
            int active = reading.incrementAndGet();
            maxReads.accumulateAndGet(active, Math::max);
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("read release timed out");
                }
                if (fail) {
                    throw failure;
                }
                return channel.read(buffer, offset);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            } finally {
                reading.decrementAndGet();
            }
        }

        @Override
        public void close() throws IOException {
            closes.incrementAndGet();
            channel.close();
        }
    }
}
