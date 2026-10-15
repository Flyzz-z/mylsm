package io.github.flyzz.mylsm.wal;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalRecoveryTest {
    private static final byte[] HEADER = HexFormat.of().parseHex("4d59574c00000001");
    private static final WalRecord PUT = new WalRecord(
            ByteKey.copyOf(new byte[]{0x6b}), Value.copyOf(new byte[]{0x41}));
    private static final WalRecord DELETE = new WalRecord(PUT.key(), Tombstone.INSTANCE);

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"new", "zero", "header"})
    void opensEmptyLogAndCanAppend(String kind) throws IOException {
        Path path = directory.resolve("log.wal");
        if (!kind.equals("new")) {
            Files.write(path, kind.equals("header") ? HEADER : new byte[0]);
        }
        List<WalRecord> records = new ArrayList<>();
        RecordingIo io = new RecordingIo(path);
        try (WalWriter writer = WalWriter.openRecovering(io, records::add)) {
            assertTrue(records.isEmpty());
            assertArrayEquals(HEADER, Files.readAllBytes(path));
            assertEquals(kind.equals("header") ? List.of() : List.of("write:0", "force:8"), io.events);
            writer.append(PUT);
        }
        assertArrayEquals(log(PUT), Files.readAllBytes(path));
        assertEquals(1, io.closeCalls);
    }

    @Test
    void replaysEveryOperationInFileOrderWithShortReadsAndAppendsAtRecoveredEnd() throws IOException {
        Path path = directory.resolve("log.wal");
        WalRecord replacement = new WalRecord(PUT.key(), Value.copyOf(new byte[]{0x42}));
        WalRecord emptyValue = new WalRecord(ByteKey.copyOf(new byte[0]), Value.copyOf(new byte[0]));
        WalRecord binary = new WalRecord(ByteKey.copyOf(new byte[]{0, (byte) 0xff}),
                Value.copyOf(new byte[]{(byte) 0x80, 0, 1}));
        List<WalRecord> expected = List.of(PUT, DELETE, replacement, emptyValue, binary);
        byte[] original = log(expected.toArray(WalRecord[]::new));
        Files.write(path, original);
        RecordingIo io = new RecordingIo(path);
        io.maxRead = 2;
        List<WalRecord> recovered = new ArrayList<>();

        try (WalWriter writer = WalWriter.openRecovering(io, recovered::add)) {
            assertEquals(expected, recovered);
            assertArrayEquals(original, Files.readAllBytes(path));
            assertTrue(io.events.isEmpty()); // 正常扫描不写、不截断，也不额外同步。
            writer.append(DELETE);
        }
        List<WalRecord> reopened = new ArrayList<>();
        try (WalWriter ignored = WalWriter.openRecovering(path, reopened::add)) {
            List<WalRecord> afterAppend = new ArrayList<>(expected);
            afterAppend.add(DELETE);
            assertEquals(afterAppend, reopened);
        }
    }

    @ParameterizedTest(name = "tail={0}, retainedBytes={1}")
    @MethodSource("tailBoundaries")
    void repairsEveryTruncatedTailBeforeAllowingAppend(WalRecord tail, int retainedBytes) throws IOException {
        Path path = directory.resolve("log.wal");
        byte[] prefix = log(PUT, DELETE);
        byte[] frame = WalCodec.encode(tail);
        Files.write(path, concat(prefix, Arrays.copyOf(frame, retainedBytes)));
        List<WalRecord> expected = new ArrayList<>(List.of(PUT, DELETE));
        boolean complete = retainedBytes == frame.length;
        boolean repair = retainedBytes > 0 && !complete;
        if (complete) {
            expected.add(tail);
        }
        long validEnd = prefix.length + (complete ? frame.length : 0);
        RecordingIo io = new RecordingIo(path);
        List<WalRecord> recovered = new ArrayList<>();

        try (WalWriter writer = WalWriter.openRecovering(io, recovered::add)) {
            assertEquals(expected, recovered);
            assertArrayEquals(log(expected.toArray(WalRecord[]::new)), Files.readAllBytes(path));
            assertEquals(repair ? List.of("truncate:" + validEnd, "force:" + validEnd) : List.of(),
                    io.events);
            writer.append(PUT);
            List<String> expectedEvents = new ArrayList<>();
            if (repair) {
                expectedEvents.add("truncate:" + validEnd);
                expectedEvents.add("force:" + validEnd);
            }
            expectedEvents.add("write:" + validEnd);
            expectedEvents.add("force:" + (validEnd + WalCodec.encode(PUT).length));
            assertEquals(expectedEvents, io.events);
        }
        expected.add(PUT);
        List<WalRecord> reopened = new ArrayList<>();
        try (WalWriter ignored = WalWriter.openRecovering(path, reopened::add)) {
            assertEquals(expected, reopened);
        }
    }

    static Stream<Arguments> tailBoundaries() {
        return Stream.of(PUT, DELETE).flatMap(record ->
                IntStream.rangeClosed(0, WalCodec.encode(record).length)
                        .mapToObj(length -> Arguments.of(record, length)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8, 18})
    void repairsIncompleteFirstRecordBackToHeader(int retainedBytes) throws IOException {
        Path path = directory.resolve("log.wal");
        Files.write(path, concat(HEADER, Arrays.copyOf(WalCodec.encode(PUT), retainedBytes)));
        List<WalRecord> records = new ArrayList<>();
        try (WalWriter writer = WalWriter.openRecovering(path, records::add)) {
            assertTrue(records.isEmpty());
            assertArrayEquals(HEADER, Files.readAllBytes(path));
            writer.append(DELETE);
        }
        try (WalWriter ignored = WalWriter.openRecovering(path, records::add)) {
            assertEquals(List.of(DELETE), records);
        }
    }

    @ParameterizedTest
    @MethodSource("io.github.flyzz.mylsm.wal.WalWriterTest#invalidHeaders")
    void rejectsInvalidHeaderWithoutReplayOrFileChanges(byte[] original) throws IOException {
        assertRejectedWithoutModification(original, null);
    }

    @ParameterizedTest
    @MethodSource("corruptRecords")
    void rejectsCompleteCorruptionAtMiddleAndEndWithoutRepair(byte[] corrupt) throws IOException {
        long offset = log(PUT).length;
        assertRejectedWithoutModification(concat(log(PUT), corrupt), offset);
        assertRejectedWithoutModification(concat(log(PUT), corrupt, WalCodec.encode(DELETE)), offset);
    }

    static Stream<byte[]> corruptRecords() {
        List<byte[]> frames = new ArrayList<>();
        byte[] badChecksum = WalCodec.encode(PUT);
        badChecksum[4] ^= 1;
        frames.add(badChecksum);
        // 重新计算 CRC，确保覆盖校验通过后才发现的结构错误。
        for (String kind : List.of("operation", "keyLength", "valueLength", "overflow", "deleteValue")) {
            byte[] frame = WalCodec.encode(PUT);
            ByteBuffer buffer = ByteBuffer.wrap(frame);
            switch (kind) {
                case "operation" -> frame[8] = 3;
                case "keyLength" -> buffer.putInt(9, -1);
                case "valueLength" -> buffer.putInt(13, 0);
                case "overflow" -> buffer.putInt(9, Integer.MAX_VALUE).putInt(13, Integer.MAX_VALUE);
                case "deleteValue" -> frame[8] = 2;
            }
            CRC32C crc = new CRC32C();
            crc.update(frame, 0, 4);
            crc.update(frame, 8, frame.length - 8);
            buffer.putInt(4, (int) crc.getValue());
            frames.add(frame);
        }
        return frames.stream();
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 8, 16_777_217, Integer.MAX_VALUE})
    void rejectsIllegalLengthEvenIfOnlyPrefixExists(int length) throws IOException {
        byte[] prefix = ByteBuffer.allocate(8).putInt(length).putInt(0).array();
        assertRejectedWithoutModification(concat(log(PUT), prefix), (long) log(PUT).length);
    }

    @Test
    void legalLengthIncreasedBeyondRemainingBytesHasDocumentedTruncationAmbiguity() throws IOException {
        Path path = directory.resolve("log.wal");
        byte[] frame = WalCodec.encode(DELETE);
        ByteBuffer.wrap(frame).putInt(0, WalCodec.MAX_PAYLOAD_SIZE);
        Files.write(path, concat(log(PUT), frame));
        List<WalRecord> records = new ArrayList<>();
        try (WalWriter ignored = WalWriter.openRecovering(path, records::add)) {
            assertEquals(List.of(PUT), records);
            assertArrayEquals(log(PUT), Files.readAllBytes(path));
        }
    }

    @Test
    void recoversPayloadExactlyAtLimit() throws IOException {
        Path path = directory.resolve("log.wal");
        WalRecord large = new WalRecord(ByteKey.copyOf(new byte[0]),
                Value.copyOf(new byte[WalCodec.MAX_PAYLOAD_SIZE - WalCodec.MIN_PAYLOAD_SIZE]));
        Files.write(path, log(large));
        List<WalRecord> records = new ArrayList<>();
        try (WalWriter ignored = WalWriter.openRecovering(path, records::add)) {
            assertEquals(List.of(large), records);
        }
    }

    @ParameterizedTest
    @MethodSource("readFailures")
    @Timeout(5)
    void readFailureIsNotTreatedAsRepairableTail(long failureOffset, String kind) throws IOException {
        Path path = directory.resolve("log.wal");
        byte[] original = log(PUT, DELETE);
        Files.write(path, original);
        RecordingIo io = new RecordingIo(path);
        io.failReadAt = failureOffset;
        io.readFailure = kind;
        io.maxRead = 2;
        IOException error = assertThrows(IOException.class, () -> WalWriter.openRecovering(io, record -> {}));
        if (kind.equals("error")) {
            assertSame(io.failure, error);
        } else if (kind.equals("eof")) {
            assertEquals(EOFException.class, error.getClass());
        }
        assertArrayEquals(original, Files.readAllBytes(path));
        assertTrue(io.events.isEmpty());
        assertEquals(1, io.closeCalls);
    }

    static Stream<Arguments> readFailures() {
        // 分别在文件头、记录前缀、载荷发生短读后注入异常 / 提前 EOF / 无进展。
        return Stream.of(2L, 10L, 18L).flatMap(offset ->
                Stream.of("error", "eof", "zero").map(kind -> Arguments.of(offset, kind)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"size", "truncate", "force"})
    void recoveryIoFailureClosesResourceAndDoesNotReturnWriter(String step) throws IOException {
        Path path = directory.resolve("log.wal");
        byte[] original = concat(log(PUT), Arrays.copyOf(WalCodec.encode(DELETE), 3));
        Files.write(path, original);
        RecordingIo io = new RecordingIo(path);
        io.failSize = step.equals("size");
        io.failTruncate = step.equals("truncate");
        io.failForce = step.equals("force");
        io.failClose = true;

        IOException error = assertThrows(IOException.class, () -> WalWriter.openRecovering(io, record -> {}));
        assertSame(io.failure, error);
        assertArrayEquals(new Throwable[]{io.closeFailure}, error.getSuppressed());
        assertEquals(1, io.closeCalls);
        assertArrayEquals(step.equals("force") ? log(PUT) : original, Files.readAllBytes(path));
        assertEquals(switch (step) {
            case "size" -> List.of();
            case "truncate" -> List.of("truncate:27");
            default -> List.of("truncate:27", "force:27");
        }, io.events);
        // 修复同步失败时文件可能已被截断；释放资源后仍可重新打开恢复。
        List<WalRecord> records = new ArrayList<>();
        try (WalWriter ignored = WalWriter.openRecovering(path, records::add)) {
            assertEquals(List.of(PUT), records);
        }
    }

    @Test
    void callbackFailureStopsScanWithoutRepairAndClosesResource() throws IOException {
        Path path = directory.resolve("log.wal");
        byte[] original = concat(log(PUT, DELETE), new byte[]{0});
        Files.write(path, original);
        RecordingIo io = new RecordingIo(path);
        io.failClose = true;
        IllegalStateException failure = new IllegalStateException("replay failed");
        List<WalRecord> replayed = new ArrayList<>();

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> WalWriter.openRecovering(io, record -> {
                    replayed.add(record);
                    if (record.equals(DELETE)) {
                        throw failure;
                    }
                })));
        assertEquals(List.of(PUT, DELETE), replayed);
        assertArrayEquals(new Throwable[]{io.closeFailure}, failure.getSuppressed());
        assertArrayEquals(original, Files.readAllBytes(path));
        assertTrue(io.events.isEmpty());
        assertEquals(1, io.closeCalls);
    }

    @Test
    void nullArgumentsFailBeforeCreatingFileAndOwnedIoIsClosed() throws IOException {
        Path path = directory.resolve("log.wal");
        assertThrows(NullPointerException.class, () -> WalWriter.openRecovering((Path) null, record -> {}));
        assertThrows(NullPointerException.class, () -> WalWriter.openRecovering(path, null));
        assertFalse(Files.exists(path));
        RecordingIo io = new RecordingIo(path);
        assertThrows(NullPointerException.class, () -> WalWriter.openRecovering(io, null));
        assertEquals(1, io.closeCalls);
        assertEquals(0, Files.size(path));
    }

    private void assertRejectedWithoutModification(byte[] original, Long badOffset) throws IOException {
        Path path = directory.resolve("log.wal");
        Files.write(path, original);
        RecordingIo io = new RecordingIo(path);
        List<WalRecord> records = new ArrayList<>();
        IOException error = assertThrows(IOException.class, () -> WalWriter.openRecovering(io, records::add));
        if (badOffset != null) {
            assertTrue(error.getMessage().contains("offset " + badOffset), error::getMessage);
            assertEquals(List.of(PUT), records); // 损坏记录本身不能交给回调。
        } else {
            assertTrue(records.isEmpty());
        }
        assertArrayEquals(original, Files.readAllBytes(path));
        assertTrue(io.events.isEmpty());
        assertEquals(1, io.closeCalls);
    }

    private static byte[] log(WalRecord... records) {
        byte[][] parts = new byte[records.length + 1][];
        parts[0] = HEADER;
        for (int i = 0; i < records.length; i++) {
            parts[i + 1] = WalCodec.encode(records[i]);
        }
        return concat(parts);
    }

    private static byte[] concat(byte[]... parts) {
        ByteBuffer buffer = ByteBuffer.allocate(Arrays.stream(parts).mapToInt(part -> part.length).sum());
        for (byte[] part : parts) {
            buffer.put(part);
        }
        return buffer.array();
    }

    /** 使用真实临时文件，记录修改顺序并在恢复边界注入故障。 */
    private static final class RecordingIo implements WalIo {
        private final WalIo delegate;
        final IOException failure = new IOException("Injected recovery I/O failure");
        final IOException closeFailure = new IOException("Injected close failure");
        final List<String> events = new ArrayList<>();
        int maxRead = Integer.MAX_VALUE;
        long failReadAt = Long.MAX_VALUE;
        String readFailure = "";
        boolean failSize;
        boolean failTruncate;
        boolean failForce;
        boolean failClose;
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
            if (offset >= failReadAt) {
                switch (readFailure) {
                    case "error" -> throw failure;
                    case "eof" -> { return -1; }
                    case "zero" -> { return 0; }
                }
            }
            int previousLimit = target.limit();
            int count = (int) Math.min(Math.min(target.remaining(), maxRead), failReadAt - offset);
            target.limit(target.position() + count);
            try {
                return delegate.read(target, offset);
            } finally {
                target.limit(previousLimit);
            }
        }

        @Override
        public int write(ByteBuffer source, long offset) throws IOException {
            events.add("write:" + offset);
            return delegate.write(source, offset);
        }

        @Override
        public void truncate(long size) throws IOException {
            events.add("truncate:" + size);
            if (failTruncate) {
                throw failure;
            }
            delegate.truncate(size);
        }

        @Override
        public void force() throws IOException {
            events.add("force:" + delegate.size());
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
