package io.github.flyzz.mylsm.lsm;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;
import io.github.flyzz.mylsm.sstable.SsTableRecord;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class LsmExplicitFlushTest {
    @TempDir
    Path directory;

    @Test
    void emptyFlushDoesNotCreateFilesRotateWalOrAdvanceManifest() throws IOException {
        FlushFiles files = new FlushFiles();
        try (LsmStore store = LsmStore.open(directory, files)) {
            byte[] before = Files.readAllBytes(directory.resolve("MANIFEST"));
            store.flush();
            store.flush();
            assertEquals(1, files.latest.revision());
            assertEquals(List.of(), files.latest.sstIds());
            assertEquals(0, files.builds.get());
            assertEquals(List.of("1.wal", "MANIFEST"), names());
            org.junit.jupiter.api.Assertions.assertArrayEquals(before, Files.readAllBytes(directory.resolve("MANIFEST")));
        }
    }

    @Test
    void flushesBelowTargetAndSupportsRepeatedWritesDeletesAndReopen() throws IOException {
        FlushFiles files = new FlushFiles();
        files.close(); // 本用例不暂停后台。
        try (LsmStore store = LsmStore.open(directory, files)) {
            store.put(key("k"), value("1"));
            store.put(key(""), value(""));
            store.delete(key("deleted"));
            store.flush();
            assertEquals(0, store.activeBytes());
            assertEquals(0, store.frozenBytes());
            assertEquals(1, files.latest.sstIds().size());
            assertEquals(0, files.latest.frozenWalId());
            assertEquals(8, Files.size(LsmStore.walPath(directory, files.latest.activeWalId())));
            long revision = files.latest.revision();
            store.flush();
            assertEquals(revision, files.latest.revision());
            store.put(key("k"), value("2"));
            store.flush();
            assertEquals(Optional.of(value("2")), store.get(key("k")));
            store.delete(key("k"));
            store.flush();
            assertEquals(3, files.latest.sstIds().size());
        }
        try (LsmStore recovered = LsmStore.open(directory)) {
            assertEquals(Optional.of(Tombstone.INSTANCE), recovered.get(key("k")));
            assertEquals(Optional.of(Tombstone.INSTANCE), recovered.get(key("deleted")));
            assertEquals(Optional.of(value("")), recovered.get(key("")));
            assertEquals(0, recovered.activeBytes());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitsForExistingFrozenAndThenOnlyFlushesANonemptyActiveGeneration(boolean activePresent)
            throws Exception {
        FlushFiles files = new FlushFiles();
        try (LsmStore store = LsmStore.open(directory, 11, files);
             var foreground = Executors.newSingleThreadExecutor(); FlushFiles release = files) {
            store.put(key("k"), value("1"));
            await(files.started[0]);
            if (activePresent) {
                store.put(key("k"), value("2"));
            }
            var flushed = foreground.submit(() -> { store.flush(); return null; });
            await(files.waiting);
            assertFalse(flushed.isDone());
            assertEquals(1, files.builds.get());
            files.release[0].countDown();
            if (activePresent) {
                await(files.started[1]);
                assertFalse(flushed.isDone());
                assertEquals(2, files.builds.get());
                assertEquals(2, files.latest.frozenWalId());
                files.release[1].countDown();
            }
            flushed.get(5, TimeUnit.SECONDS);
            assertEquals(activePresent ? 2 : 1, files.latest.sstIds().size());
            assertEquals(0, store.activeBytes());
            assertEquals(0, store.frozenBytes());
            assertEquals(Optional.of(value(activePresent ? "2" : "1")), store.get(key("k")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptedWaitKeepsTheDataWorkerAndStoreUsable(boolean existingFrozen) throws Exception {
        FlushFiles files = new FlushFiles();
        try (LsmStore store = LsmStore.open(directory, existingFrozen ? 11 : 1000, files);
             var foreground = Executors.newSingleThreadExecutor(); FlushFiles release = files) {
            store.put(key("k"), value("1"));
            if (existingFrozen) {
                await(files.started[0]);
                store.put(key("k"), value("2"));
            }
            AtomicReference<Thread> caller = new AtomicReference<>();
            var interrupted = foreground.submit(() -> {
                caller.set(Thread.currentThread());
                assertThrows(InterruptedIOException.class, store::flush);
                return Thread.currentThread().isInterrupted();
            });
            await(files.waiting);
            await(files.started[0]);
            caller.get().interrupt();
            assertTrue(interrupted.get(5, TimeUnit.SECONDS));
            assertEquals(1, files.latest.frozenWalId());
            assertTrue(Files.exists(directory.resolve("1.wal")));
            assertEquals(Optional.of(value(existingFrozen ? "2" : "1")), store.get(key("k")));
            files.close();
            store.flush();
            assertEquals(0, store.activeBytes());
            assertEquals(0, store.frozenBytes());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"first-write", "second-write", "first-publish", "second-publish"})
    void failureOfEitherGenerationWakesFlushAndNeverReportsSuccess(String fault) throws Exception {
        FlushFiles files = new FlushFiles();
        files.failGeneration = fault.startsWith("first") ? 1 : 2;
        files.failPublication = fault.endsWith("publish");
        LsmStore store = LsmStore.open(directory, 11, files);
        try (var foreground = Executors.newSingleThreadExecutor(); FlushFiles release = files) {
            store.put(key("k"), value("1"));
            await(files.started[0]);
            store.put(key("k"), value("2"));
            var flushed = foreground.submit(() -> { store.flush(); return null; });
            await(files.waiting);
            files.release[0].countDown();
            if (files.failGeneration == 2) {
                await(files.started[1]);
                files.release[1].countDown();
            }
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> flushed.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
            assertSame(files.failure, failure.getCause().getCause());
            assertThrows(IllegalStateException.class, store::flush);
        } finally {
            files.close();
            IOException failure = assertThrows(IOException.class, store::close);
            assertSame(files.failure, failure.getCause());
        }
        try (LsmStore recovered = LsmStore.open(directory)) {
            recovered.flush();
            assertEquals(Optional.of(value("2")), recovered.get(key("k")));
        }
    }

    @Test
    void explicitRotationFailureRetainsTheLoggedData() throws IOException {
        FlushFiles files = new FlushFiles();
        files.failNewWal = true;
        LsmStore store = LsmStore.open(directory, files);
        try {
            store.put(key("k"), value("1"));
            assertSame(files.failure, assertThrows(IOException.class, store::flush));
            assertThrows(IllegalStateException.class, store::flush);
        } finally {
            store.close();
        }
        try (LsmStore recovered = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("1")), recovered.get(key("k")));
            recovered.flush();
        }
    }

    @Test
    void closedStoreRejectsFlush() throws IOException {
        LsmStore store = LsmStore.open(directory);
        store.close();
        assertThrows(IllegalStateException.class, store::flush);
    }

    private List<String> names() throws IOException {
        try (var files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out at flush boundary");
    }

    private static ByteKey key(String text) {
        return ByteKey.copyOf(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Value value(String text) {
        return Value.copyOf(text.getBytes(StandardCharsets.UTF_8));
    }

    private static final class FlushFiles extends LsmStore.FileOps implements AutoCloseable {
        private final CountDownLatch[] started = {new CountDownLatch(1), new CountDownLatch(1)};
        private final CountDownLatch[] release = {new CountDownLatch(1), new CountDownLatch(1)};
        private final CountDownLatch waiting = new CountDownLatch(1);
        private final AtomicInteger builds = new AtomicInteger();
        private final IOException failure = new IOException("injected explicit flush failure");
        private volatile ManifestState latest;
        private int failGeneration;
        private boolean failPublication;
        private boolean failNewWal;

        @Override
        ManifestFile createManifest(Path path, ManifestState initial) throws IOException {
            ManifestFile manifest = super.createManifest(path, initial);
            latest = initial;
            return manifest;
        }

        @Override
        WalWriter createWal(Path path) throws IOException {
            if (failNewWal && !path.getFileName().toString().equals("1.wal")) {
                throw failure;
            }
            return super.createWal(path);
        }

        @Override
        void beforeWait() {
            waiting.countDown();
        }

        @Override
        void writeTable(Path path, Iterable<SsTableRecord> records) throws IOException {
            int generation = builds.incrementAndGet();
            if (generation <= started.length) {
                started[generation - 1].countDown();
                try {
                    if (!release[generation - 1].await(10, TimeUnit.SECONDS)) {
                        throw new IOException("flush test release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            if (!failPublication && generation == failGeneration) {
                throw failure;
            }
            super.writeTable(path, records);
        }

        @Override
        void commit(ManifestFile manifest, ManifestState next) throws IOException {
            if (failPublication && next.frozenWalId() == 0 && builds.get() == failGeneration) {
                throw failure;
            }
            super.commit(manifest, next);
            latest = next;
        }

        @Override
        public void close() {
            for (CountDownLatch latch : release) {
                latch.countDown();
            }
        }
    }
}
