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

class LsmStoreProcessTest {
    private static final int HALTED_AT_CHECKPOINT = 73;
    private static final ByteKey KEY = key("key");
    private static final ByteKey DELETED = key("deleted");
    private static final ByteKey SHADOW = key("shadow");
    private static final ByteKey FROZEN_ONLY = key("frozen-only");
    private static final ByteKey ACTIVE_ONLY = key("active-only");
    private static final ByteKey EMPTY = ByteKey.copyOf(new byte[0]);
    private static final ByteKey BINARY = ByteKey.copyOf(new byte[]{0, (byte) 0xff});
    private static final Value A = value("old");
    private static final Value B = value("frozen");
    private static final Value C = value("active");
    private static final Value EMPTY_VALUE = Value.copyOf(new byte[0]);
    private static final Value LARGE_VALUE = Value.copyOf(new byte[8192]);

    @TempDir
    Path temporary;

    enum ExitPoint {
        BEFORE_ROTATION_COMMIT,
        AFTER_ROTATION_COMMIT,
        DURING_TABLE_WRITE,
        BEFORE_PUBLICATION_COMMIT,
        AFTER_PUBLICATION_COMMIT,
        AFTER_FLUSH;

        boolean hasActiveWrites() {
            return switch (this) {
                case BEFORE_ROTATION_COMMIT, AFTER_ROTATION_COMMIT -> false;
                default -> true;
            };
        }
    }

    @ParameterizedTest
    @EnumSource(ExitPoint.class)
    @Timeout(40)
    void recoversTheCommittedFileSetAfterUnclosedProcessExit(ExitPoint point) throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("database"));
        Path output = temporary.resolve("child-output.txt"); // 诊断文件不放入数据库目录。
        runChild(directory, output, point);

        ManifestState snapshot;
        try (ManifestFile manifest = ManifestFile.open(directory.resolve("MANIFEST"))) {
            snapshot = manifest.current();
        }
        assertCheckpointFiles(directory, point, snapshot);
        byte[] active = Files.readAllBytes(LsmStore.walPath(directory, snapshot.activeWalId()));
        byte[] frozen = snapshot.frozenWalId() == 0 ? null
                : Files.readAllBytes(LsmStore.walPath(directory, snapshot.frozenWalId()));

        PausedRecovery files = new PausedRecovery();
        try (LsmStore store = LsmStore.open(directory, files); PausedRecovery release = files) {
            assertData(store, point.hasActiveWrites());
            assertArrayEquals(active, Files.readAllBytes(LsmStore.walPath(directory, snapshot.activeWalId())));
            if (frozen != null) {
                await(files.started);
                assertArrayEquals(frozen,
                        Files.readAllBytes(LsmStore.walPath(directory, snapshot.frozenWalId())));
            }
            if (point == ExitPoint.BEFORE_ROTATION_COMMIT) {
                assertFalse(Files.exists(directory.resolve("4.wal"))); // 未提交的新 WAL 已清理。
            }
            if (point == ExitPoint.DURING_TABLE_WRITE || point == ExitPoint.BEFORE_PUBLICATION_COMMIT) {
                assertFalse(Files.exists(directory.resolve("5.sst"))); // 无引用输出不参与恢复。
            }
            if (point == ExitPoint.AFTER_PUBLICATION_COMMIT) {
                assertFalse(Files.exists(directory.resolve("2.wal"))); // 已失效 WAL 已清理。
            }
            release.close();
            store.flush(); // 恢复的 frozen 继续发布，再发布非空 active。
            assertEquals(0, store.activeBytes());
            assertEquals(0, store.frozenBytes());
            assertData(store, point.hasActiveWrites());
            store.put(key("continued"), C);
        }
        try (LsmStore store = LsmStore.open(directory)) {
            assertData(store, point.hasActiveWrites());
            assertEquals(Optional.of(C), store.get(key("continued")));
        }
    }

    private static void assertCheckpointFiles(Path directory, ExitPoint point, ManifestState snapshot)
            throws IOException {
        ManifestState expected = switch (point) {
            case BEFORE_ROTATION_COMMIT -> new ManifestState(3, 2, 0, 0, List.of(3L));
            case AFTER_ROTATION_COMMIT, DURING_TABLE_WRITE, BEFORE_PUBLICATION_COMMIT ->
                    new ManifestState(4, 4, 2, Files.size(directory.resolve("2.wal")), List.of(3L));
            case AFTER_PUBLICATION_COMMIT -> new ManifestState(5, 4, 0, 0, List.of(3L, 5L));
            case AFTER_FLUSH -> new ManifestState(7, 6, 0, 0, List.of(3L, 5L, 7L));
        };
        assertEquals(expected, snapshot);
        if (point == ExitPoint.BEFORE_ROTATION_COMMIT || point == ExitPoint.AFTER_ROTATION_COMMIT) {
            assertEquals(8, Files.size(directory.resolve("4.wal")));
            assertFalse(Files.exists(directory.resolve("5.sst")));
        }
        if (point == ExitPoint.DURING_TABLE_WRITE) {
            assertTrue(Files.size(directory.resolve("5.sst")) > 8192); // 真实 Writer 已输出数据块，尚无索引/页脚。
            assertThrows(IOException.class, () -> SsTableReader.open(directory.resolve("5.sst")));
        }
        if (point == ExitPoint.BEFORE_PUBLICATION_COMMIT || point == ExitPoint.AFTER_PUBLICATION_COMMIT) {
            assertTrue(Files.exists(directory.resolve("2.wal")));
            try (SsTableReader reader = SsTableReader.open(directory.resolve("5.sst"))) {
                assertEquals(Optional.of(B), reader.get(KEY));
                assertEquals(Optional.of(Tombstone.INSTANCE), reader.get(SHADOW));
            }
        }
    }

    private static void assertData(LsmStore store, boolean activeWrites) throws IOException {
        assertEquals(Optional.of(activeWrites ? C : B), store.get(KEY));
        assertEquals(Optional.of(activeWrites ? Tombstone.INSTANCE : B), store.get(DELETED));
        assertEquals(Optional.of(activeWrites ? C : Tombstone.INSTANCE), store.get(SHADOW));
        assertEquals(Optional.of(B), store.get(FROZEN_ONLY));
        assertEquals(activeWrites ? Optional.of(C) : Optional.empty(), store.get(ACTIVE_ONLY));
        assertEquals(Optional.of(EMPTY_VALUE), store.get(EMPTY));
        // 在轮换提交前后退出时，触发 put 尚未返回；其 WAL 已同步，恢复后也会出现。
        assertEquals(Optional.of(LARGE_VALUE), store.get(BINARY));
        assertEquals(Optional.empty(), store.get(key("missing")));
    }

    private static void runChild(Path directory, Path output, ExitPoint point) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java, "-cp", classPath, UnclosedWriter.class.getName(),
                directory.toString(), point.name())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), () -> "子进程超时：" + readOutput(output));
            assertEquals(HALTED_AT_CHECKPOINT, process.exitValue(), () -> readOutput(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "未能回收子进程");
            }
        }
    }

    private static String readOutput(Path output) {
        try {
            return Files.readString(output);
        } catch (IOException failure) {
            return failure.toString();
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IOException("test checkpoint timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException(failure);
        }
    }

    private static ByteKey key(String text) {
        return ByteKey.copyOf(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Value value(String text) {
        return Value.copyOf(text.getBytes(StandardCharsets.UTF_8));
    }

    /** halt 跳过 finally/close/shutdown hook；此入口仅由测试的独立 JVM 执行。 */
    public static final class UnclosedWriter {
        public static void main(String[] args) {
            try {
                writeAndHalt(Path.of(args[0]), ExitPoint.valueOf(args[1]));
                throw new AssertionError("checkpoint was not reached");
            } catch (Throwable failure) {
                failure.printStackTrace();
                Runtime.getRuntime().halt(1); // 避免出错后仍有非守护 worker 导致子进程挂住。
            }
        }

        private static void writeAndHalt(Path directory, ExitPoint point) throws IOException {
            // 先通过真实 LsmStore 发布旧值，后续检查 Tombstone 不让这些值复活。
            try (LsmStore store = LsmStore.open(directory)) {
                store.put(KEY, A);
                store.put(DELETED, A);
                store.put(SHADOW, A);
                store.put(EMPTY, A);
                store.put(BINARY, A);
                store.flush();
            }
            CrashFiles files = new CrashFiles(point);
            LsmStore store = LsmStore.open(directory, 8192, files);
            store.put(KEY, B);
            store.put(DELETED, B);
            store.delete(SHADOW);
            store.put(EMPTY, EMPTY_VALUE);
            store.put(FROZEN_ONLY, B);
            store.put(BINARY, LARGE_VALUE); // 最后一条跨过目标，触发轮换。
            await(files.started);
            store.put(KEY, C);
            store.delete(DELETED);
            store.put(SHADOW, C);
            store.put(ACTIVE_ONLY, C);
            files.proceed.countDown();
            store.flush();
            files.exitAt(ExitPoint.AFTER_FLUSH);
        }
    }

    private static final class CrashFiles extends LsmStore.FileOps {
        private final ExitPoint point;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch proceed = new CountDownLatch(1);

        private CrashFiles(ExitPoint point) {
            this.point = point;
        }

        private void exitAt(ExitPoint checkpoint) {
            if (point == checkpoint) {
                Runtime.getRuntime().halt(HALTED_AT_CHECKPOINT);
            }
        }

        @Override
        void commit(ManifestFile manifest, ManifestState next) throws IOException {
            if (next.revision() == 4) { exitAt(ExitPoint.BEFORE_ROTATION_COMMIT); }
            if (next.revision() == 5) { exitAt(ExitPoint.BEFORE_PUBLICATION_COMMIT); }
            super.commit(manifest, next);
            if (next.revision() == 4) { exitAt(ExitPoint.AFTER_ROTATION_COMMIT); }
            if (next.revision() == 5) { exitAt(ExitPoint.AFTER_PUBLICATION_COMMIT); }
        }

        @Override
        void writeTable(Path path, Iterable<SsTableRecord> records) throws IOException {
            started.countDown();
            await(proceed); // 锁外等待，确保新 active 的覆盖/删除已成功返回。
            if (point == ExitPoint.DURING_TABLE_WRITE) {
                super.writeTable(path, () -> new Iterator<>() {
                    private final Iterator<SsTableRecord> source = records.iterator();
                    private int delivered;

                    @Override
                    public boolean hasNext() {
                        if (delivered == 3) { exitAt(ExitPoint.DURING_TABLE_WRITE); }
                        return source.hasNext();
                    }

                    @Override
                    public SsTableRecord next() {
                        delivered++;
                        return source.next();
                    }
                });
            } else {
                super.writeTable(path, records);
            }
        }
    }

    private static final class PausedRecovery extends LsmStore.FileOps implements AutoCloseable {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch proceed = new CountDownLatch(1);

        @Override
        void writeTable(Path path, Iterable<SsTableRecord> records) throws IOException {
            started.countDown();
            await(proceed);
            super.writeTable(path, records);
        }

        @Override
        public void close() {
            proceed.countDown();
        }
    }
}
