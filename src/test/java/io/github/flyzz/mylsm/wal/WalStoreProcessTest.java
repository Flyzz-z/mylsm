package io.github.flyzz.mylsm.wal;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalStoreProcessTest {
    private static final ByteKey KEY = ByteKey.copyOf(new byte[]{0x6b});
    private static final ByteKey DELETED = ByteKey.copyOf(new byte[]{0x64});
    private static final ByteKey EMPTY = ByteKey.copyOf(new byte[0]);
    private static final Value A = Value.copyOf(new byte[]{0x41});
    private static final Value B = Value.copyOf(new byte[]{0, (byte) 0xff, 0x42});

    @TempDir
    Path directory;

    @Test
    @Timeout(30)
    void recoversSuccessfulWritesFromAnotherProcessThatHaltedWithoutClosing() throws Exception {
        Path path = directory.resolve("log.wal");
        Path output = directory.resolve("child-output.txt");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java, "-cp", classPath, UnclosedWriter.class.getName(), path.toString())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "写入子进程超时");
            assertEquals(0, process.exitValue(), () -> readOutput(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "未能回收写入子进程");
            }
        }
        byte[] original = Files.readAllBytes(path);
        try (WalStore store = WalStore.open(path)) {
            assertEquals(Optional.of(B), store.get(KEY));
            assertEquals(Optional.of(Tombstone.INSTANCE), store.get(DELETED));
            assertEquals(Optional.of(Value.copyOf(new byte[0])), store.get(EMPTY));
            assertEquals(Optional.empty(), store.get(ByteKey.copyOf(new byte[]{0x78})));
            assertArrayEquals(original, Files.readAllBytes(path)); // 重放不改写或增加日志。
            store.put(KEY, A);
        }
        try (WalStore store = WalStore.open(path)) {
            assertEquals(Optional.of(A), store.get(KEY));
            assertEquals(Optional.of(Tombstone.INSTANCE), store.get(DELETED));
        }
    }

    private static String readOutput(Path output) {
        try {
            return Files.readString(output);
        } catch (IOException failure) {
            return failure.toString();
        }
    }

    /** 独立 JVM 入口；halt 跳过关闭与 shutdown hook，只能依赖此前成功的逐条同步。 */
    public static final class UnclosedWriter {
        public static void main(String[] args) throws IOException {
            WalStore store = WalStore.open(Path.of(args[0]));
            store.put(KEY, A);
            store.delete(KEY);
            store.put(KEY, B);
            store.put(DELETED, A);
            store.delete(DELETED);
            store.put(EMPTY, Value.copyOf(new byte[0]));
            Runtime.getRuntime().halt(0);
        }
    }
}
