package io.github.flyzz.mylsm.lsm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManifestCodecTest {
    // CRC 常量由独立的逐位 CRC32C 脚本生成，未使用生产编解码器。
    private static final byte[] HEADER = hex("4d594d4600000001");
    private static final byte[] INITIAL = hex("00000024d4a0f081"
            + "000000000000000100000000000000010000000000000000000000000000000000000000");
    private static final byte[] ROTATED = hex("00000024203cf6ce"
            + "0000000000000002000000000000000200000000000000010000000000002ee000000000");
    private static final byte[] PUBLISHED = hex("0000002c0bd9cd9a"
            + "000000000000000300000000000000020000000000000000000000000000000000000001"
            + "0000000000000003");
    private static final byte[] TWO_TABLES = hex("00000034e2b04af7"
            + "00000000000000070000000000000014000000000000000f000000000000032000000002"
            + "00000000000000090000000000000004");

    @Test
    void matchesFixedHeaderAndThreeStateHistory() throws IOException {
        assertArrayEquals(HEADER, ManifestCodec.encodeHeader());
        ManifestCodec.decodeHeader(HEADER);
        List<ManifestState> states = List.of(new ManifestState(1, 1, 0, 0, List.of()),
                new ManifestState(2, 2, 1, 12000, List.of()),
                new ManifestState(3, 2, 0, 0, List.of(3L)));
        List<byte[]> frames = List.of(INITIAL, ROTATED, PUBLISHED);
        for (int i = 0; i < states.size(); i++) {
            assertArrayEquals(frames.get(i), ManifestCodec.encode(states.get(i)));
            assertEquals(states.get(i), ManifestCodec.decode(frames.get(i)));
        }
        states.getFirst().requireInitial();
        states.get(0).requireSuccessor(states.get(1));
        states.get(1).requireSuccessor(states.get(2));
        assertEquals(52, HEADER.length + INITIAL.length);
    }

    @Test
    void preservesLongFieldsAndVersionOrderWithoutSortingIds() throws IOException {
        ManifestState state = new ManifestState(7, 20, 15, 800, List.of(9L, 4L));
        assertArrayEquals(TWO_TABLES, ManifestCodec.encode(state));
        assertEquals(state, ManifestCodec.decode(TWO_TABLES));
        ManifestState large = new ManifestState(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE - 1,
                Long.MAX_VALUE, List.of(4_294_967_296L, 1L));
        assertEquals(large, ManifestCodec.decode(ManifestCodec.encode(large)));
    }

    @Test
    void decodingOneFrameDoesNotPretendToValidateTheHistory() throws IOException {
        ManifestState state = ManifestCodec.decode(PUBLISHED);
        assertThrows(IllegalArgumentException.class, state::requireInitial);
        ManifestState skipped = ManifestCodec.decode(ManifestCodec.encode(
                new ManifestState(2, 2, 0, 0, List.of(3L))));
        assertThrows(IllegalArgumentException.class,
                () -> ManifestCodec.decode(INITIAL).requireSuccessor(skipped));
    }

    @Test
    void ownsItsArraysAndDecodedState() throws IOException {
        byte[] input = TWO_TABLES.clone();
        ManifestState state = ManifestCodec.decode(input);
        assertArrayEquals(TWO_TABLES, input);
        Arrays.fill(input, (byte) 0);
        assertEquals(List.of(9L, 4L), state.sstIds());
        assertThrows(UnsupportedOperationException.class, state.sstIds()::clear);
        byte[] encoded = ManifestCodec.encode(state);
        Arrays.fill(encoded, (byte) 0);
        assertArrayEquals(TWO_TABLES, ManifestCodec.encode(state));
        byte[] header = ManifestCodec.encodeHeader();
        header[0] = 0;
        assertArrayEquals(HEADER, ManifestCodec.encodeHeader());
    }

    @Test
    void rejectsNullCodecArguments() {
        assertThrows(NullPointerException.class, () -> ManifestCodec.decodeHeader(null));
        assertThrows(NullPointerException.class, () -> ManifestCodec.encode(null));
        assertThrows(NullPointerException.class, () -> ManifestCodec.decode(null));
    }

    @Test
    void rejectsEveryTruncationAndTrailingBytes() {
        for (int length = 0; length < HEADER.length; length++) {
            byte[] bytes = Arrays.copyOf(HEADER, length);
            assertThrows(EOFException.class, () -> ManifestCodec.decodeHeader(bytes));
        }
        for (byte[] frame : List.of(INITIAL, ROTATED, PUBLISHED, TWO_TABLES)) {
            for (int length = 0; length < frame.length; length++) {
                byte[] bytes = Arrays.copyOf(frame, length);
                assertThrows(EOFException.class, () -> ManifestCodec.decode(bytes));
            }
            IOException error = assertThrows(IOException.class,
                    () -> ManifestCodec.decode(Arrays.copyOf(frame, frame.length + 1)));
            assertFalse(error instanceof EOFException);
        }
        assertThrows(IOException.class, () -> ManifestCodec.decodeHeader(Arrays.copyOf(HEADER, 9)));
        byte[] twoFrames = ByteBuffer.allocate(INITIAL.length + ROTATED.length)
                .put(INITIAL).put(ROTATED).array();
        assertThrows(IOException.class, () -> ManifestCodec.decode(twoFrames));
    }

    @Test
    void rejectsChangedMagicVersionAndEveryFrameByte() {
        for (int offset : new int[]{0, 7}) {
            byte[] bytes = HEADER.clone();
            bytes[offset] ^= 1;
            assertThrows(IOException.class, () -> ManifestCodec.decodeHeader(bytes));
        }
        for (int offset = 0; offset < TWO_TABLES.length; offset++) {
            byte[] bytes = TWO_TABLES.clone();
            bytes[offset] ^= 1;
            assertThrows(IOException.class, () -> ManifestCodec.decode(bytes));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 35, 37, 16_777_216, 16_777_217, Integer.MAX_VALUE})
    void rejectsIllegalDeclaredLengthsBeforeTryingToReadTheBody(int length) {
        byte[] prefix = ByteBuffer.allocate(8).putInt(length).putInt(0).array();
        IOException error = assertThrows(IOException.class, () -> ManifestCodec.decode(prefix));
        assertFalse(error instanceof EOFException);
    }

    @Test
    void distinguishesAPermittedButIncompleteFrameFromAnIllegalLength() {
        byte[] prefix = ByteBuffer.allocate(8).putInt(44).putInt(0).array();
        assertThrows(EOFException.class, () -> ManifestCodec.decode(prefix));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 3, 2_097_148, 536_870_914, Integer.MAX_VALUE})
    void rejectsCountsThatDoNotMatchTheFrameEvenWithValidCrc(int count) {
        byte[] frame = TWO_TABLES.clone();
        ByteBuffer.wrap(frame).putInt(40, count);
        updateChecksum(frame);
        IOException error = assertThrows(IOException.class, () -> ManifestCodec.decode(frame));
        assertFalse(error instanceof EOFException);
    }

    @Test
    void rejectsInvalidNumericFieldsEvenWithValidCrc() {
        for (int offset : new int[]{8, 16}) {
            for (long value : new long[]{Long.MIN_VALUE, -1, 0}) {
                assertInvalidLong(TWO_TABLES, offset, value);
            }
        }
        for (long value : new long[]{Long.MIN_VALUE, -1, 20}) {
            assertInvalidLong(TWO_TABLES, 24, value);
        }
        for (long value : new long[]{Long.MIN_VALUE, -1, 0, 7}) {
            assertInvalidLong(TWO_TABLES, 32, value);
        }
        assertInvalidLong(TWO_TABLES, 24, 0); // 无 frozen 却留下非零长度。
        assertInvalidLong(INITIAL, 32, 8); // 同样拒绝给无 frozen 的状态附带长度。
        for (long value : new long[]{Long.MIN_VALUE, -1, 0, 20, 15, 9}) {
            assertInvalidLong(TWO_TABLES, 52, value);
        }
    }

    @Test
    void acceptsTheLargestAlignedPayloadAndRejectsTheNextCount() throws IOException {
        int maxCount = ManifestCodec.MAX_SSTABLE_COUNT;
        List<Long> ids = new ArrayList<>(maxCount);
        for (long id = 1; id <= maxCount; id++) {
            ids.add(id);
        }
        ManifestState state = new ManifestState(9, Long.MAX_VALUE, 0, 0, ids);
        byte[] frame = ManifestCodec.encode(state);
        // 36 + N*8 无法恰好等于 16 MiB；最大合法载荷距离上限还有 4 字节。
        assertEquals(ManifestCodec.MAX_PAYLOAD_SIZE - 4 + ManifestCodec.PREFIX_SIZE, frame.length);
        assertEquals(state, ManifestCodec.decode(frame));
        byte[] oneMore = Arrays.copyOf(frame, frame.length + Long.BYTES);
        ByteBuffer.wrap(oneMore).putInt(0, frame.length).putInt(40, maxCount + 1)
                .putLong(oneMore.length - Long.BYTES, maxCount + 1L);
        updateChecksum(oneMore);
        IOException error = assertThrows(IOException.class, () -> ManifestCodec.decode(oneMore));
        assertFalse(error instanceof EOFException);
    }

    private static void assertInvalidLong(byte[] source, int offset, long value) {
        byte[] frame = source.clone();
        ByteBuffer.wrap(frame).putLong(offset, value);
        updateChecksum(frame);
        IOException error = assertThrows(IOException.class, () -> ManifestCodec.decode(frame));
        assertFalse(error instanceof EOFException);
    }

    private static void updateChecksum(byte[] frame) {
        CRC32C crc = new CRC32C();
        crc.update(frame, 0, 4);
        crc.update(frame, 8, frame.length - 8);
        ByteBuffer.wrap(frame).putInt(4, (int) crc.getValue());
    }

    private static byte[] hex(String text) {
        return HexFormat.of().parseHex(text);
    }
}
