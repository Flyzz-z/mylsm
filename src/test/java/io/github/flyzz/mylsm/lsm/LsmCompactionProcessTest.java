package io.github.flyzz.mylsm.lsm;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;
import io.github.flyzz.mylsm.sstable.SsTableReader;
import io.github.flyzz.mylsm.sstable.SsTableRecord;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LsmCompactionProcessTest {
    private static final int CHECKPOINT_EXIT = 73;
    private static final long LARGE_TARGET = Long.MAX_VALUE;
    private static final Value LARGE = Value.copyOf(new byte[8192]);
    private static final ByteKey EMPTY = ByteKey.copyOf(new byte[0]);
    private static final ByteKey BINARY = ByteKey.copyOf(new byte[]{0, (byte) 0xff});

    @TempDir
    Path temporary;

    enum ExitPoint { DURING_OUTPUT, BEFORE_COMMIT, AFTER_COMMIT, DURING_RECLAIM }

    @ParameterizedTest
    @EnumSource(ExitPoint.class)
    @Timeout(40)
    void recoversAutomaticCompactionAndBothWalGenerationsAfterProcessExit(ExitPoint point) throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("database"));
        Path outputLog = temporary.resolve("child-output.txt");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java, "-cp", classPath, CrashingWriter.class.getName(),
                directory.toString(), point.name()).redirectErrorStream(true).redirectOutput(outputLog.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), () -> readLog(outputLog));
            assertEquals(CHECKPOINT_EXIT, process.exitValue(), () -> readLog(outputLog));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
        boolean published = point == ExitPoint.AFTER_COMMIT || point == ExitPoint.DURING_RECLAIM;
        ManifestState state;
        try (ManifestFile manifest = ManifestFile.open(directory.resolve("MANIFEST"))) {
            state = manifest.current();
        }
        assertEquals(published ? List.of(6L) : List.of(3L, 5L), state.sstIds());
        assertEquals(7, state.activeWalId());
        assertEquals(4, state.frozenWalId());
        assertEquals(Files.size(directory.resolve("4.wal")), state.frozenWalLength());
        assertEquals(point != ExitPoint.DURING_RECLAIM, Files.exists(directory.resolve("3.sst")));
        assertTrue(Files.exists(directory.resolve("5.sst")));
        if (point == ExitPoint.DURING_OUTPUT) {
            assertTrue(Files.size(directory.resolve("6.sst")) > 8192);
            assertThrows(IOException.class, () -> SsTableReader.open(directory.resolve("6.sst")));
        } else {
            try (SsTableReader reader = SsTableReader.open(directory.resolve("6.sst"))) {
                assertEquals(Optional.of(value("new")), reader.get(key("a")));
                assertEquals(Optional.of(Tombstone.INSTANCE), reader.get(key("b")));
                assertEquals(Optional.of(LARGE), reader.get(key("large")));
            }
        }
        byte[] active = Files.readAllBytes(directory.resolve("7.wal"));
        byte[] frozen = Files.readAllBytes(directory.resolve("4.wal"));
        PausedRecovery files = new PausedRecovery();
        try (LsmStore store = LsmStore.open(directory, LARGE_TARGET, Integer.MAX_VALUE, files);
             PausedRecovery release = files) {
            await(files.started);
            assertData(store);
            assertArrayEquals(active, Files.readAllBytes(directory.resolve("7.wal")));
            assertArrayEquals(frozen, Files.readAllBytes(directory.resolve("4.wal")));
            if (published) {
                assertFalse(Files.exists(directory.resolve("3.sst")));
                assertFalse(Files.exists(directory.resolve("5.sst")));
            } else {
                assertFalse(Files.exists(directory.resolve("6.sst")));
            }
            release.close();
            store.flush();
            assertData(store);
            store.put(key("continued"), value("ok"));
        }
        try (LsmStore reopened = LsmStore.open(directory, LARGE_TARGET, Integer.MAX_VALUE)) {
            assertData(reopened);
            assertEquals(Optional.of(value("ok")), reopened.get(key("continued")));
        }
    }

    private static void assertData(LsmStore store) throws IOException {
        assertEquals(Optional.of(value("fresh")), store.get(key("a")));
        assertEquals(Optional.of(Tombstone.INSTANCE), store.get(key("b")));
        assertEquals(Optional.of(Tombstone.INSTANCE), store.get(key("ghost")));
        assertEquals(Optional.of(value("stable")), store.get(key("c")));
        assertEquals(Optional.of(Value.copyOf(new byte[40])), store.get(key("frozen")));
        assertEquals(Optional.of(value("live")), store.get(key("active")));
        assertEquals(Optional.of(LARGE), store.get(key("large")));
        assertEquals(Optional.of(value("tail1")), store.get(key("z-tail1")));
        assertEquals(Optional.of(value("tail2")), store.get(key("z-tail2")));
        assertEquals(Optional.of(Value.copyOf(new byte[0])), store.get(EMPTY));
        assertEquals(Optional.of(Value.copyOf(new byte[]{(byte) 0xff, 0})), store.get(BINARY));
        assertEquals(Optional.empty(), store.get(key("missing")));
    }

    private static void seed(Path directory) throws IOException {
        try (LsmStore store = LsmStore.open(directory, LARGE_TARGET, Integer.MAX_VALUE)) {
            store.put(key("a"), value("old"));
            store.put(key("b"), value("old"));
            store.put(key("c"), value("stable"));
            store.put(key("large"), LARGE);
            store.put(key("z-tail1"), value("tail1"));
            store.put(key("z-tail2"), value("tail2"));
            store.flush();
            store.put(key("a"), value("new"));
            store.delete(key("b"));
            store.delete(key("ghost"));
            store.put(EMPTY, Value.copyOf(new byte[0]));
            store.put(BINARY, Value.copyOf(new byte[]{(byte) 0xff, 0}));
            store.flush();
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IOException("compaction checkpoint timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException(failure);
        }
    }

    private static String readLog(Path path) {
        try { return Files.readString(path); }
        catch (IOException failure) { return failure.toString(); }
    }

    private static ByteKey key(String text) { return ByteKey.copyOf(text.getBytes(StandardCharsets.UTF_8)); }
    private static Value value(String text) { return Value.copyOf(text.getBytes(StandardCharsets.UTF_8)); }

    public static final class CrashingWriter {
        public static void main(String[] args) {
            try {
                Path directory = Path.of(args[0]);
                seed(directory);
                CrashFiles files = new CrashFiles(ExitPoint.valueOf(args[1]));
                LsmStore store = LsmStore.open(directory, 64, 2, files);
                await(files.started);
                store.put(key("a"), value("fresh"));
                store.delete(key("b"));
                store.put(key("frozen"), Value.copyOf(new byte[40])); // 输出 6 后轮换为 WAL 7。
                store.put(key("active"), value("live"));
                files.writesComplete.countDown();
                files.worker.join(15_000);
                throw new AssertionError("checkpoint was not reached");
            } catch (Throwable failure) {
                failure.printStackTrace();
                Runtime.getRuntime().halt(1);
            }
        }
    }

    private static final class CrashFiles extends LsmStore.FileOps {
        private final ExitPoint point;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch writesComplete = new CountDownLatch(1);
        private Thread worker;
        private boolean merging;

        private CrashFiles(ExitPoint point) { this.point = point; }

        @Override
        Thread newWorker(Runnable task) {
            worker = super.newWorker(task);
            return worker;
        }

        @Override
        void writeCompaction(Path path, List<SsTableReader> inputs) throws IOException {
            started.countDown();
            await(writesComplete);
            merging = true;
            try { super.writeCompaction(path, inputs); }
            finally { merging = false; }
        }

        @Override
        void writeTable(Path path, Iterable<SsTableRecord> records) throws IOException {
            if (merging && point == ExitPoint.DURING_OUTPUT) {
                super.writeTable(path, () -> new Iterator<>() {
                    private final Iterator<SsTableRecord> delegate = records.iterator();

                    @Override
                    public boolean hasNext() {
                        try {
                            if (Files.size(path) > 8192) { Runtime.getRuntime().halt(CHECKPOINT_EXIT); }
                        } catch (IOException failure) { throw new UncheckedIOException(failure); }
                        return delegate.hasNext();
                    }

                    @Override
                    public SsTableRecord next() { return delegate.next(); }
                });
            } else {
                super.writeTable(path, records);
            }
        }

        @Override
        void commit(ManifestFile manifest, ManifestState next) throws IOException {
            boolean compacting = manifest.current().sstIds().size() >= 2 && next.sstIds().size() == 1;
            if (compacting && point == ExitPoint.BEFORE_COMMIT) { Runtime.getRuntime().halt(CHECKPOINT_EXIT); }
            super.commit(manifest, next);
            if (compacting && point == ExitPoint.AFTER_COMMIT) { Runtime.getRuntime().halt(CHECKPOINT_EXIT); }
        }

        @Override
        void deleteObsolete(Path path) throws IOException {
            super.deleteObsolete(path);
            if (path.getFileName().toString().endsWith(".sst") && point == ExitPoint.DURING_RECLAIM) {
                Runtime.getRuntime().halt(CHECKPOINT_EXIT);
            }
        }
    }

    private static final class PausedRecovery extends LsmStore.FileOps implements AutoCloseable {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        void writeTable(Path path, Iterable<SsTableRecord> records) throws IOException {
            started.countDown();
            await(release);
            super.writeTable(path, records);
        }

        @Override
        public void close() { release.countDown(); }
    }
}
