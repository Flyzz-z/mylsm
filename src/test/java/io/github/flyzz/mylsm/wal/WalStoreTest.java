package io.github.flyzz.mylsm.wal;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Entry;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalStoreTest {
    private static final byte[] HEADER = HexFormat.of().parseHex("4d59574c00000001");
    private static final ByteKey KEY = ByteKey.copyOf(new byte[]{0x6b});
    private static final Value A = Value.copyOf(new byte[]{0x41});
    private static final Value B = Value.copyOf(new byte[]{0x42});
    private static final WalRecord PUT_A = new WalRecord(KEY, A);

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initializesNewOrZeroByteStoreAndPreservesThreeQueryStatesAfterReopen(boolean existing)
            throws IOException {
        Path path = directory.resolve("log.wal");
        if (existing) {
            Files.createFile(path);
        }
        ByteKey absent = ByteKey.copyOf(new byte[]{0x01});
        ByteKey deleted = ByteKey.copyOf(new byte[]{0x02});
        ByteKey emptyKey = ByteKey.copyOf(new byte[0]);
        Value emptyValue = Value.copyOf(new byte[0]);
        ByteKey binaryKey = ByteKey.copyOf(new byte[]{0, (byte) 0xff});
        Value binaryValue = Value.copyOf(new byte[]{(byte) 0x80, 0, (byte) 0xff});
        try (WalStore store = WalStore.open(path)) {
            assertArrayEquals(HEADER, Files.readAllBytes(path));
            assertEquals(Optional.empty(), store.get(KEY));
            store.put(KEY, A);
            store.delete(KEY);
            assertEquals(Optional.of(Tombstone.INSTANCE), store.get(KEY));
            store.put(KEY, B);
            store.delete(deleted); // 删除未出现过的键也要留下 tombstone。
            store.delete(deleted);
            store.put(emptyKey, emptyValue);
            store.put(binaryKey, binaryValue);
            assertEquals(Optional.of(B), store.get(KEY));
            assertEquals(Optional.of(Tombstone.INSTANCE), store.get(deleted));
            assertEquals(Optional.empty(), store.get(absent));
            assertEquals(Optional.of(emptyValue), store.get(emptyKey));
            assertEquals(Optional.of(binaryValue), store.get(binaryKey));
        }
        byte[] original = Files.readAllBytes(path);
        try (WalStore store = WalStore.open(path)) {
            assertEquals(Optional.of(B), store.get(KEY));
            assertEquals(Optional.of(Tombstone.INSTANCE), store.get(deleted));
            assertEquals(Optional.empty(), store.get(absent));
            assertEquals(Optional.of(emptyValue), store.get(emptyKey));
            assertEquals(Optional.of(binaryValue), store.get(binaryKey));
            assertArrayEquals(original, Files.readAllBytes(path)); // 重放与查询不能再次写日志。
            store.delete(emptyKey);
        }
        try (WalStore store = WalStore.open(path)) {
            assertEquals(Optional.of(Tombstone.INSTANCE), store.get(emptyKey));
            assertEquals(Optional.of(B), store.get(KEY));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void memoryChangesOnlyAfterCompleteRecordAndSuccessfulSync(boolean deletion) throws IOException {
        Path path = directory.resolve("log.wal");
        RecordingIo io = new RecordingIo(path);
        try (WalStore store = WalStore.open(io)) {
            store.put(KEY, A);
            Entry target = deletion ? Tombstone.INSTANCE : B;
            byte[] expectedFile = concat(HEADER, WalCodec.encode(PUT_A),
                    WalCodec.encode(new WalRecord(KEY, target)));
            io.maxWrite = 2;
            io.beforeWrite = () -> assertEquals(Optional.of(A), store.get(KEY));
            io.beforeForce = () -> {
                assertEquals(Optional.of(A), store.get(KEY));
                assertArrayEquals(expectedFile, Files.readAllBytes(path));
            };
            int previousForces = io.forceCalls;
            if (deletion) {
                store.delete(KEY);
            } else {
                store.put(KEY, B);
            }
            assertEquals(previousForces + 1, io.forceCalls);
            assertEquals(Optional.of(target), store.get(KEY));
            assertArrayEquals(expectedFile, Files.readAllBytes(path));
        }
    }

    @Test
    void invalidParametersDoNotLogChangeMemoryOrInvalidateStore() throws IOException {
        Path path = directory.resolve("log.wal");
        RecordingIo io = new RecordingIo(path);
        try (WalStore store = WalStore.open(io)) {
            store.put(KEY, A);
            byte[] original = Files.readAllBytes(path);
            int writes = io.writeCalls;
            int forces = io.forceCalls;
            assertThrows(NullPointerException.class, () -> store.put(null, A));
            assertThrows(NullPointerException.class, () -> store.put(KEY, null));
            assertThrows(NullPointerException.class, () -> store.delete(null));
            assertThrows(NullPointerException.class, () -> store.get(null));
            byte[] tooLarge = new byte[WalCodec.MAX_PAYLOAD_SIZE - WalCodec.MIN_PAYLOAD_SIZE + 1];
            Value oversizedValue = Value.copyOf(tooLarge);
            ByteKey oversizedKey = ByteKey.copyOf(tooLarge);
            assertThrows(IllegalArgumentException.class, () -> store.put(KEY, oversizedValue));
            assertThrows(IllegalArgumentException.class, () -> store.delete(oversizedKey));
            assertArrayEquals(original, Files.readAllBytes(path));
            assertEquals(writes, io.writeCalls);
            assertEquals(forces, io.forceCalls);
            assertEquals(Optional.of(A), store.get(KEY));
            store.put(KEY, B);
            assertEquals(Optional.of(B), store.get(KEY));
        }
    }

    @ParameterizedTest(name = "failure={0}, deletion={1}")
    @MethodSource("writeFailures")
    void writeFailureStopsReadsAndWritesAndRecoveryUsesActualLog(String kind, boolean deletion)
            throws IOException {
        Path path = directory.resolve("log.wal");
        RecordingIo io = new RecordingIo(path);
        Entry target = deletion ? Tombstone.INSTANCE : B;
        byte[] targetFrame = WalCodec.encode(new WalRecord(KEY, target));
        try (WalStore store = WalStore.open(io)) {
            store.put(KEY, A);
            long appendOffset = Files.size(path);
            io.beforeWrite = () -> assertEquals(Optional.of(A), store.get(KEY));
            io.beforeForce = () -> assertEquals(Optional.of(A), store.get(KEY));
            io.failWriteAt = kind.equals("partial") ? appendOffset + 3 : Long.MAX_VALUE;
            io.failAfterWrite = kind.equals("complete");
            io.failForce = kind.equals("force");
            assertSame(io.failure, assertThrows(IOException.class, () -> {
                if (deletion) {
                    store.delete(KEY);
                } else {
                    store.put(KEY, B);
                }
            }));
            byte[] expectedTail = kind.equals("partial") ? Arrays.copyOf(targetFrame, 3) : targetFrame;
            byte[] expectedFile = concat(HEADER, WalCodec.encode(PUT_A), expectedTail);
            assertArrayEquals(expectedFile, Files.readAllBytes(path));
            int writes = io.writeCalls;
            int forces = io.forceCalls;
            assertUnavailable(store);
            assertEquals(writes, io.writeCalls);
            assertEquals(forces, io.forceCalls);
            assertArrayEquals(expectedFile, Files.readAllBytes(path));
        }
        assertEquals(1, io.closeCalls);
        try (WalStore store = WalStore.open(path)) {
            // 调用报错但完整记录仍会恢复；残缺记录被截去，保留此前的 A。
            assertEquals(Optional.of(kind.equals("partial") ? A : target), store.get(KEY));
            store.put(KEY, B);
        }
        try (WalStore store = WalStore.open(path)) {
            assertEquals(Optional.of(B), store.get(KEY));
        }
    }

    static Stream<Arguments> writeFailures() {
        return Stream.of("partial", "complete", "force")
                .flatMap(kind -> Stream.of(false, true).map(deletion -> Arguments.of(kind, deletion)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"header", "record", "read", "truncate", "force"})
    void failedOpenClosesResourceAndDoesNotExposePartiallyRecoveredStore(String kind) throws IOException {
        Path path = directory.resolve("log.wal");
        byte[] corrupt = WalCodec.encode(new WalRecord(KEY, B));
        corrupt[4] ^= 1;
        byte[] original = switch (kind) {
            case "header" -> new byte[8];
            case "record" -> concat(HEADER, WalCodec.encode(PUT_A), corrupt);
            default -> concat(HEADER, WalCodec.encode(PUT_A), Arrays.copyOf(corrupt, 3));
        };
        Files.write(path, original);
        RecordingIo io = new RecordingIo(path);
        io.failRead = kind.equals("read");
        io.failTruncate = kind.equals("truncate");
        io.failForce = kind.equals("force");
        io.failClose = true;

        IOException error = assertThrows(IOException.class, () -> WalStore.open(io));
        assertArrayEquals(new Throwable[]{io.closeFailure}, error.getSuppressed());
        assertEquals(1, io.closeCalls);
        if (kind.equals("record")) {
            assertTrue(error.getMessage().contains("offset 27"));
        }
        assertArrayEquals(kind.equals("force") ? concat(HEADER, WalCodec.encode(PUT_A)) : original,
                Files.readAllBytes(path));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repeatedCloseIsSafeAndCloseFailureStillStopsAllOperations(boolean failClose) throws IOException {
        RecordingIo io = new RecordingIo(directory.resolve("log.wal"));
        WalStore store = WalStore.open(io);
        store.put(KEY, A);
        io.failClose = failClose;
        if (failClose) {
            assertSame(io.closeFailure, assertThrows(IOException.class, store::close));
        } else {
            store.close();
        }
        store.close();
        assertUnavailable(store);
        assertEquals(1, io.closeCalls);
    }

    @Test
    void rejectsNullPathAndRequiresExistingParentDirectory() {
        assertThrows(NullPointerException.class, () -> WalStore.open((Path) null));
        Path parent = directory.resolve("missing");
        assertThrows(NoSuchFileException.class, () -> WalStore.open(parent.resolve("log.wal")));
        assertFalse(Files.exists(parent));
    }

    private static void assertUnavailable(WalStore store) {
        assertThrows(IllegalStateException.class, () -> store.get(KEY));
        assertThrows(IllegalStateException.class, () -> store.put(KEY, B));
        assertThrows(IllegalStateException.class, () -> store.delete(KEY));
        assertThrows(IllegalStateException.class, () -> store.get(null));
        assertThrows(IllegalStateException.class, () -> store.put(null, null));
        assertThrows(IllegalStateException.class, () -> store.delete(null));
    }

    private static byte[] concat(byte[]... parts) {
        ByteBuffer buffer = ByteBuffer.allocate(Arrays.stream(parts).mapToInt(part -> part.length).sum());
        for (byte[] part : parts) {
            buffer.put(part);
        }
        return buffer.array();
    }

    @FunctionalInterface
    private interface IoCheck {
        void run() throws IOException;
    }

    /** 保留真实文件读写，在写入/同步边界检查 store 的可见状态。 */
    private static final class RecordingIo implements WalIo {
        private final WalIo delegate;
        final IOException failure = new IOException("Injected store I/O failure");
        final IOException closeFailure = new IOException("Injected close failure");
        IoCheck beforeWrite = () -> {};
        IoCheck beforeForce = () -> {};
        int maxWrite = Integer.MAX_VALUE;
        long failWriteAt = Long.MAX_VALUE;
        boolean failAfterWrite;
        boolean failForce;
        boolean failRead;
        boolean failTruncate;
        boolean failClose;
        int writeCalls;
        int forceCalls;
        int closeCalls;

        RecordingIo(Path path) throws IOException {
            delegate = FileChannelWalIo.open(path);
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public int read(ByteBuffer target, long offset) throws IOException {
            if (failRead) {
                throw failure;
            }
            return delegate.read(target, offset);
        }

        @Override
        public int write(ByteBuffer source, long offset) throws IOException {
            writeCalls++;
            beforeWrite.run();
            if (offset >= failWriteAt) {
                throw failure;
            }
            int previousLimit = source.limit();
            int count = (int) Math.min(Math.min(source.remaining(), maxWrite), failWriteAt - offset);
            source.limit(source.position() + count);
            try {
                int written = delegate.write(source, offset);
                if (failAfterWrite && !source.hasRemaining()) {
                    throw failure;
                }
                return written;
            } finally {
                source.limit(previousLimit);
            }
        }

        @Override
        public void truncate(long size) throws IOException {
            if (failTruncate) {
                throw failure;
            }
            delegate.truncate(size);
        }

        @Override
        public void force() throws IOException {
            forceCalls++;
            beforeForce.run();
            if (failForce) {
                throw failure;
            }
            delegate.force();
        }

        @Override
        public void close() throws IOException {
            closeCalls++;
            delegate.close();
            if (failClose) {
                throw closeFailure;
            }
        }
    }
}
