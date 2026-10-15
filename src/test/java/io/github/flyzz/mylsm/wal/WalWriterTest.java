package io.github.flyzz.mylsm.wal;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WalWriterTest {
    private static final byte[] HEADER = HexFormat.of().parseHex("4d59574c00000001");
    private static final WalRecord PUT = new WalRecord(
            ByteKey.copyOf(new byte[]{0x6b}), Value.copyOf(new byte[]{0x41}));
    private static final WalRecord DELETE = new WalRecord(PUT.key(), Tombstone.INSTANCE);

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initializesNewAndZeroByteFiles(boolean existing) throws IOException {
        Path path = directory.resolve("log.wal");
        if (existing) {
            Files.createFile(path);
        }
        try (WalWriter writer = WalWriter.openEmpty(path)) {
            assertArrayEquals(HEADER, Files.readAllBytes(path));
            writer.append(PUT);
            writer.append(DELETE);
            assertArrayEquals(concat(HEADER, WalCodec.encode(PUT), WalCodec.encode(DELETE)),
                    Files.readAllBytes(path));
        }
    }

    @Test
    void reopensHeaderOnlyFileWithoutDuplicatingHeader() throws IOException {
        Path path = directory.resolve("log.wal");
        try (WalWriter ignored = WalWriter.openEmpty(path)) {
            assertArrayEquals(HEADER, Files.readAllBytes(path));
        }
        try (WalWriter writer = WalWriter.openEmpty(path)) {
            writer.append(PUT);
            assertArrayEquals(concat(HEADER, WalCodec.encode(PUT)), Files.readAllBytes(path));
        }
    }

    @ParameterizedTest
    @MethodSource("invalidHeaders")
    void rejectsInvalidHeadersWithoutModifyingAndClosesResource(byte[] original) throws IOException {
        Path path = directory.resolve("log.wal");
        Files.write(path, original);
        RecordingIo io = new RecordingIo(path);

        assertThrows(IOException.class, () -> WalWriter.openEmpty(io));
        assertArrayEquals(original, Files.readAllBytes(path));
        assertEquals(1, io.closeCalls);
        assertEquals(0, io.writeCalls);
        assertEquals(0, io.forceCalls);
    }

    static Stream<byte[]> invalidHeaders() {
        return Stream.concat(
                IntStream.range(1, HEADER.length).mapToObj(length -> Arrays.copyOf(HEADER, length)),
                Stream.of(HexFormat.of().parseHex("0000000000000001"),
                        HexFormat.of().parseHex("4d59574c00000002"),
                        HexFormat.of().parseHex("4d59574cffffffff")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void requiresRecoveryForExistingCompleteOrPartialRecord(boolean complete) throws IOException {
        Path path = directory.resolve("log.wal");
        byte[] frame = WalCodec.encode(PUT);
        byte[] original = concat(HEADER, complete ? frame : Arrays.copyOf(frame, 3));
        Files.write(path, original);

        assertThrows(IOException.class, () -> WalWriter.openEmpty(path));
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test
    void rejectsNullPathAndDoesNotCreateParentDirectories() {
        assertThrows(NullPointerException.class, () -> WalWriter.openEmpty((Path) null));
        Path missing = directory.resolve("missing");
        assertThrows(NoSuchFileException.class,
                () -> WalWriter.openEmpty(missing.resolve("log.wal")));
        assertFalse(Files.exists(missing));
    }

    @Test
    void handlesShortWritesAndSyncsOnlyAfterCompleteHeaderOrFrame() throws IOException {
        Path path = directory.resolve("log.wal");
        RecordingIo io = new RecordingIo(path);
        io.maxWrite = 3;
        try (WalWriter writer = WalWriter.openEmpty(io)) {
            assertEquals(List.of(8L), io.syncedSizes);
            writer.append(PUT);
            assertEquals(List.of(8L, 27L), io.syncedSizes);
            writer.append(DELETE);
            assertEquals(List.of(8L, 27L, 45L), io.syncedSizes);
            assertArrayEquals(concat(HEADER, WalCodec.encode(PUT), WalCodec.encode(DELETE)),
                    Files.readAllBytes(path));
        }
        assertEquals(1, io.closeCalls);
        assertEquals(3, io.forceCalls); // close 不承担写入同步。
    }

    @Test
    void handlesShortHeaderReads() throws IOException {
        Path path = directory.resolve("log.wal");
        Files.write(path, HEADER);
        RecordingIo io = new RecordingIo(path);
        io.maxRead = 2;
        try (WalWriter writer = WalWriter.openEmpty(io)) {
            assertEquals(4, io.readCalls);
            writer.append(PUT);
            assertArrayEquals(concat(HEADER, WalCodec.encode(PUT)), Files.readAllBytes(path));
        }
    }

    @Test
    void invalidRecordDoesNotWriteSyncOrInvalidateWriter() throws IOException {
        Path path = directory.resolve("log.wal");
        RecordingIo io = new RecordingIo(path);
        try (WalWriter writer = WalWriter.openEmpty(io)) {
            int previousWrites = io.writeCalls;
            byte[] tooLarge = new byte[WalCodec.MAX_PAYLOAD_SIZE - WalCodec.MIN_PAYLOAD_SIZE + 1];
            WalRecord overLimit = new WalRecord(ByteKey.copyOf(new byte[0]), Value.copyOf(tooLarge));
            assertThrows(NullPointerException.class, () -> writer.append(null));
            assertThrows(IllegalArgumentException.class, () -> writer.append(overLimit));
            assertEquals(previousWrites, io.writeCalls);
            assertEquals(1, io.forceCalls);
            assertArrayEquals(HEADER, Files.readAllBytes(path));

            writer.append(PUT);
            assertArrayEquals(concat(HEADER, WalCodec.encode(PUT)), Files.readAllBytes(path));
        }
    }

    @Test
    void partialWriteFailureStopsAllSubsequentAppends() throws IOException {
        Path path = directory.resolve("log.wal");
        RecordingIo io = new RecordingIo(path);
        try (WalWriter writer = WalWriter.openEmpty(io)) {
            io.failWriteAt = HEADER.length + 3;
            assertSame(io.failure, assertThrows(IOException.class, () -> writer.append(PUT)));
            byte[] expected = concat(HEADER, Arrays.copyOf(WalCodec.encode(PUT), 3));
            assertArrayEquals(expected, Files.readAllBytes(path));
            int previousWrites = io.writeCalls;
            assertThrows(IllegalStateException.class, () -> writer.append(DELETE));
            assertThrows(IllegalStateException.class, () -> writer.append(null));
            assertEquals(previousWrites, io.writeCalls);
            assertEquals(1, io.forceCalls);
            assertArrayEquals(expected, Files.readAllBytes(path));
        }
        assertEquals(1, io.closeCalls);
    }

    @Test
    void forceFailureCanLeaveCompleteRecordButWriterRemainsFailed() throws IOException {
        Path path = directory.resolve("log.wal");
        RecordingIo io = new RecordingIo(path);
        try (WalWriter writer = WalWriter.openEmpty(io)) {
            io.failForce = true;
            assertSame(io.failure, assertThrows(IOException.class, () -> writer.append(PUT)));
            byte[] contents = Files.readAllBytes(path);
            assertEquals(PUT, WalCodec.decode(Arrays.copyOfRange(contents, HEADER.length, contents.length)));
            int previousWrites = io.writeCalls;
            assertThrows(IllegalStateException.class, () -> writer.append(DELETE));
            assertEquals(previousWrites, io.writeCalls);
            assertEquals(2, io.forceCalls);
            assertArrayEquals(contents, Files.readAllBytes(path));
        }
    }

    @Test
    @Timeout(5)
    void zeroByteWriteFailsInsteadOfSpinningAndInvalidatesWriter() throws IOException {
        Path path = directory.resolve("log.wal");
        RecordingIo io = new RecordingIo(path);
        try (WalWriter writer = WalWriter.openEmpty(io)) {
            io.zeroWrite = true;
            assertThrows(IOException.class, () -> writer.append(PUT));
            assertThrows(IllegalStateException.class, () -> writer.append(PUT));
            assertArrayEquals(HEADER, Files.readAllBytes(path));
            assertEquals(1, io.forceCalls);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"size", "write", "force"})
    void initializationFailureClosesResourceAndPropagatesOriginalException(String step) throws IOException {
        Path path = directory.resolve("log.wal");
        RecordingIo io = new RecordingIo(path);
        io.failSize = step.equals("size");
        io.failWriteAt = step.equals("write") ? 3 : Long.MAX_VALUE;
        io.failForce = step.equals("force");

        assertSame(io.failure, assertThrows(IOException.class, () -> WalWriter.openEmpty(io)));
        assertEquals(1, io.closeCalls);
        byte[] expected = switch (step) {
            case "size" -> new byte[0];
            case "write" -> Arrays.copyOf(HEADER, 3);
            default -> HEADER;
        };
        assertArrayEquals(expected, Files.readAllBytes(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"error", "eof", "zero"})
    @Timeout(5)
    void headerReadFailureClosesResourceWithoutModifyingFile(String kind) throws IOException {
        Path path = directory.resolve("log.wal");
        Files.write(path, HEADER);
        RecordingIo io = new RecordingIo(path);
        io.readFailure = kind;

        IOException error = assertThrows(IOException.class, () -> WalWriter.openEmpty(io));
        if (kind.equals("error")) {
            assertSame(io.failure, error);
        } else if (kind.equals("eof")) {
            assertEquals(EOFException.class, error.getClass());
        }
        assertEquals(1, io.closeCalls);
        assertArrayEquals(HEADER, Files.readAllBytes(path));
    }

    @Test
    void cleanupFailureIsSuppressedOnOriginalOpenFailure() throws IOException {
        RecordingIo io = new RecordingIo(directory.resolve("log.wal"));
        io.failForce = true;
        io.failClose = true;

        IOException error = assertThrows(IOException.class, () -> WalWriter.openEmpty(io));
        assertSame(io.failure, error);
        assertArrayEquals(new Throwable[]{io.closeFailure}, error.getSuppressed());
        assertEquals(1, io.closeCalls);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closeIsIdempotentAndAlwaysStopsAppendsEvenOnCloseFailure(boolean failClose) throws IOException {
        RecordingIo io = new RecordingIo(directory.resolve("log.wal"));
        WalWriter writer = WalWriter.openEmpty(io);
        io.failClose = failClose;
        if (failClose) {
            assertSame(io.closeFailure, assertThrows(IOException.class, writer::close));
        } else {
            writer.close();
        }
        writer.close();
        assertThrows(IllegalStateException.class, () -> writer.append(PUT));
        assertThrows(IllegalStateException.class, () -> writer.append(null));
        assertEquals(1, io.closeCalls);
    }

    private static byte[] concat(byte[]... parts) {
        int size = Arrays.stream(parts).mapToInt(part -> part.length).sum();
        ByteBuffer result = ByteBuffer.allocate(size);
        for (byte[] part : parts) {
            result.put(part);
        }
        return result.array();
    }

    /** 所有成功读写仍委托真实文件，仅在指定边界制造故障。 */
    private static final class RecordingIo implements WalIo {
        private final WalIo delegate;
        final IOException failure = new IOException("Injected I/O failure");
        final IOException closeFailure = new IOException("Injected close failure");
        final List<Long> syncedSizes = new ArrayList<>();
        int maxWrite = Integer.MAX_VALUE;
        int maxRead = Integer.MAX_VALUE;
        long failWriteAt = Long.MAX_VALUE;
        String readFailure = "";
        boolean failSize;
        boolean failForce;
        boolean failClose;
        boolean zeroWrite;
        int writeCalls;
        int readCalls;
        int forceCalls;
        int closeCalls;

        RecordingIo(Path path) throws IOException {
            delegate = FileChannelWalIo.open(path);
        }

        @Override
        public long size() throws IOException {
            if (failSize) {
                throw failure;
            }
            return delegate.size();
        }

        @Override
        public int read(ByteBuffer target, long offset) throws IOException {
            readCalls++;
            switch (readFailure) {
                case "error" -> throw failure;
                case "eof" -> { return -1; }
                case "zero" -> { return 0; }
            }
            int previousLimit = target.limit();
            target.limit(target.position() + Math.min(target.remaining(), maxRead));
            try {
                return delegate.read(target, offset);
            } finally {
                target.limit(previousLimit);
            }
        }

        @Override
        public int write(ByteBuffer source, long offset) throws IOException {
            writeCalls++;
            if (offset >= failWriteAt) {
                throw failure;
            }
            if (zeroWrite) {
                return 0;
            }
            int count = (int) Math.min(Math.min(source.remaining(), maxWrite), failWriteAt - offset);
            int previousLimit = source.limit();
            source.limit(source.position() + count);
            try {
                return delegate.write(source, offset);
            } finally {
                source.limit(previousLimit);
            }
        }

        @Override
        public void truncate(long size) throws IOException {
            delegate.truncate(size);
        }

        @Override
        public void force() throws IOException {
            forceCalls++;
            if (failForce) {
                throw failure;
            }
            delegate.force();
            syncedSizes.add(delegate.size());
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
