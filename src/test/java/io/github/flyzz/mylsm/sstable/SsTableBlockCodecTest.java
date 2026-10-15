package io.github.flyzz.mylsm.sstable;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;
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

class SsTableBlockCodecTest {
    // 固定样例使用独立的逐位 CRC32C 脚本计算，避免编解码共同出错仍能往返。
    private static final byte[] VALUE_K_A = HexFormat.of().parseHex(
            "0000000f18b7c1bd000000010100000001000000016b41");
    private static final byte[] TOMBSTONE_K = HexFormat.of().parseHex(
            "0000000ebce2e3f2000000010200000001000000006b");
    private static final byte[] EMPTY_A_AND_DELETED_B = HexFormat.of().parseHex(
            "00000018173fbfaa000000020100000001000000006102000000010000000062");

    @Test
    void matchesFixedValueBlock() throws IOException {
        List<SsTableRecord> records = List.of(value(new byte[]{0x6b}, new byte[]{0x41}));
        assertArrayEquals(VALUE_K_A, SsTableBlockCodec.encode(records));
        assertEquals(records, SsTableBlockCodec.decode(VALUE_K_A));
    }

    @Test
    void matchesFixedTombstoneBlock() throws IOException {
        List<SsTableRecord> records = List.of(deletion(new byte[]{0x6b}));
        assertArrayEquals(TOMBSTONE_K, SsTableBlockCodec.encode(records));
        assertEquals(records, SsTableBlockCodec.decode(TOMBSTONE_K));
    }

    @Test
    void matchesFixedMultiRecordBlockAndDistinguishesEmptyValueFromDeletion() throws IOException {
        List<SsTableRecord> records = List.of(value(new byte[]{0x61}, new byte[0]),
                deletion(new byte[]{0x62}));
        assertArrayEquals(EMPTY_A_AND_DELETED_B, SsTableBlockCodec.encode(records));
        assertEquals(records, SsTableBlockCodec.decode(EMPTY_A_AND_DELETED_B));
    }

    @Test
    void roundTripsBinaryKeysCommonPrefixesAndUnsignedOrdering() throws IOException {
        List<SsTableRecord> records = List.of(
                value(new byte[0], new byte[0]),
                deletion(new byte[]{0}),
                value(new byte[]{0, 0}, new byte[]{(byte) 0xff, 0, (byte) 0x80}),
                value(new byte[]{0, (byte) 0xff}, new byte[0]),
                deletion(new byte[]{0x7f}),
                value(new byte[]{(byte) 0x80}, new byte[]{1}),
                deletion(new byte[]{(byte) 0xff}));
        assertEquals(records, SsTableBlockCodec.decode(SsTableBlockCodec.encode(records)));
        List<SsTableRecord> emptyKeyDeletion = List.of(deletion(new byte[0]));
        assertEquals(emptyKeyDeletion,
                SsTableBlockCodec.decode(SsTableBlockCodec.encode(emptyKeyDeletion)));
    }

    @Test
    void isolatesInputOutputAndDecodedRecords() throws IOException {
        List<SsTableRecord> records = new ArrayList<>(List.of(
                value(new byte[]{0x6b}, new byte[]{0x41})));
        byte[] first = SsTableBlockCodec.encode(records);
        byte[] second = SsTableBlockCodec.encode(records);
        List<SsTableRecord> decoded = SsTableBlockCodec.decode(first);
        assertArrayEquals(VALUE_K_A, first);
        records.clear();
        Arrays.fill(first, (byte) 0);
        assertArrayEquals(VALUE_K_A, second);
        assertEquals(List.of(value(new byte[]{0x6b}, new byte[]{0x41})), decoded);
        assertThrows(UnsupportedOperationException.class, decoded::clear);
        byte[] keyCopy = decoded.getFirst().key().toByteArray();
        keyCopy[0] = 0;
        assertArrayEquals(new byte[]{0x6b}, decoded.getFirst().key().toByteArray());
    }

    @Test
    void rejectsNullAndEmptyInputs() {
        assertThrows(NullPointerException.class, () -> SsTableBlockCodec.encode(null));
        assertThrows(NullPointerException.class, () -> SsTableBlockCodec.decode(null));
        assertThrows(NullPointerException.class,
                () -> SsTableBlockCodec.encode(Arrays.asList(deletion(new byte[0]), null)));
        assertThrows(IllegalArgumentException.class, () -> SsTableBlockCodec.encode(List.of()));
    }

    @Test
    void rejectsDuplicateAndDescendingKeysDuringEncoding() {
        assertThrows(IllegalArgumentException.class, () -> SsTableBlockCodec.encode(List.of(
                value(new byte[]{1}, new byte[0]), deletion(new byte[]{1}))));
        assertThrows(IllegalArgumentException.class, () -> SsTableBlockCodec.encode(List.of(
                deletion(new byte[]{(byte) 0x80}), deletion(new byte[]{0x7f}))));
        assertThrows(IllegalArgumentException.class, () -> SsTableBlockCodec.encode(List.of(
                deletion(new byte[]{0, 1}), deletion(new byte[]{0}))));
    }

    @Test
    void acceptsMaximumRecordAndBlockIncludingTheirHeaders() throws IOException {
        int keyLength = 1024;
        byte[] key = new byte[keyLength];
        byte[] bytes = new byte[SsTableBlockCodec.MAX_RECORD_SIZE
                - SsTableBlockCodec.RECORD_HEADER_SIZE - keyLength];
        key[0] = (byte) 0x80;
        bytes[bytes.length - 1] = (byte) 0xff;
        List<SsTableRecord> records = List.of(value(key, bytes));
        byte[] frame = SsTableBlockCodec.encode(records);
        assertEquals(SsTableBlockCodec.PREFIX_SIZE + SsTableBlockCodec.MAX_PAYLOAD_SIZE, frame.length);
        assertEquals(records, SsTableBlockCodec.decode(frame));
    }

    @Test
    void rejectsValueAndTombstoneOneByteOverRecordLimit() {
        byte[] oversized = new byte[SsTableBlockCodec.MAX_RECORD_SIZE
                - SsTableBlockCodec.RECORD_HEADER_SIZE + 1];
        assertThrows(IllegalArgumentException.class,
                () -> SsTableBlockCodec.encode(List.of(value(new byte[0], oversized))));
        assertThrows(IllegalArgumentException.class,
                () -> SsTableBlockCodec.encode(List.of(deletion(oversized))));
    }

    @Test
    void enforcesTotalBlockLimitAcrossIndividuallyLegalRecords() throws IOException {
        int valueLength = SsTableBlockCodec.MAX_RECORD_SIZE / 2
                - SsTableBlockCodec.RECORD_HEADER_SIZE - 1;
        List<SsTableRecord> records = List.of(
                value(new byte[]{0}, new byte[valueLength]),
                value(new byte[]{1}, new byte[valueLength]));
        byte[] frame = SsTableBlockCodec.encode(records);
        assertEquals(SsTableBlockCodec.PREFIX_SIZE + SsTableBlockCodec.MAX_PAYLOAD_SIZE, frame.length);
        assertEquals(records, SsTableBlockCodec.decode(frame));
        assertThrows(IllegalArgumentException.class, () -> SsTableBlockCodec.encode(List.of(
                records.getFirst(), value(new byte[]{1}, new byte[valueLength + 1]))));
    }

    @Test
    void acceptsBlocksAboveTheFutureFourKiBTarget() throws IOException {
        List<SsTableRecord> records = List.of(value(new byte[0], new byte[10 * 1024]));
        assertEquals(records, SsTableBlockCodec.decode(SsTableBlockCodec.encode(records)));
    }

    @Test
    void rejectsEveryTruncatedFrameBoundary() {
        for (int length = 0; length < EMPTY_A_AND_DELETED_B.length; length++) {
            byte[] truncated = Arrays.copyOf(EMPTY_A_AND_DELETED_B, length);
            assertThrows(EOFException.class, () -> SsTableBlockCodec.decode(truncated),
                    "truncated length=" + length);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 4, 12, 16_777_221, Integer.MAX_VALUE})
    void rejectsInvalidPayloadLengthBeforeReadingBody(int length) {
        byte[] prefix = ByteBuffer.allocate(8).putInt(length).putInt(0).array();
        IOException failure = assertThrows(IOException.class, () -> SsTableBlockCodec.decode(prefix));
        assertFalse(failure instanceof EOFException);
    }

    @Test
    void rejectsBytesOutsideTheSingleFrame() {
        assertThrows(IOException.class, () -> SsTableBlockCodec.decode(
                Arrays.copyOf(VALUE_K_A, VALUE_K_A.length + 1)));
        byte[] twoBlocks = ByteBuffer.allocate(VALUE_K_A.length + TOMBSTONE_K.length)
                .put(VALUE_K_A).put(TOMBSTONE_K).array();
        assertThrows(IOException.class, () -> SsTableBlockCodec.decode(twoBlocks));
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 8, 12, 16, 20, 21, 22, 31})
    void detectsBitChangesThroughoutTheBlock(int offset) {
        byte[] corrupted = EMPTY_A_AND_DELETED_B.clone();
        corrupted[offset] ^= 1;
        assertThrows(IOException.class, () -> SsTableBlockCodec.decode(corrupted));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 3, Integer.MAX_VALUE})
    void rejectsInvalidCountsWithValidChecksum(int count) {
        byte[] invalid = EMPTY_A_AND_DELETED_B.clone();
        ByteBuffer.wrap(invalid).putInt(8, count);
        updateChecksum(invalid);
        assertThrows(IOException.class, () -> SsTableBlockCodec.decode(invalid));
    }

    @Test
    void rejectsEmptyBlockEvenWithValidChecksum() {
        assertThrows(IOException.class,
                () -> SsTableBlockCodec.decode(frame(ByteBuffer.allocate(4).putInt(0).array())));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3, 255})
    void rejectsUnknownKindWithValidChecksum(int kind) {
        assertThrows(IOException.class,
                () -> SsTableBlockCodec.decode(singleRecordFrame(kind, 0, 0, new byte[0])));
    }

    @Test
    void rejectsNegativeOverflowingAndUnavailableLengthsWithValidChecksum() {
        for (int[] lengths : new int[][]{
                {-1, 0}, {0, -1}, {Integer.MAX_VALUE, Integer.MAX_VALUE}, {1, 1}}) {
            byte[] invalid = singleRecordFrame(1, lengths[0], lengths[1], new byte[0]);
            assertThrows(IOException.class, () -> SsTableBlockCodec.decode(invalid));
        }
    }

    @Test
    void rejectsTombstoneWithValueEvenWithValidChecksum() {
        assertThrows(IOException.class,
                () -> SsTableBlockCodec.decode(singleRecordFrame(2, 1, 1, new byte[]{1, 2})));
    }

    @Test
    void rejectsUnusedPayloadBytesWithValidChecksum() {
        assertThrows(IOException.class,
                () -> SsTableBlockCodec.decode(singleRecordFrame(1, 0, 0, new byte[]{1})));
        byte[] invalid = EMPTY_A_AND_DELETED_B.clone();
        ByteBuffer.wrap(invalid).putInt(8, 1);
        updateChecksum(invalid);
        assertThrows(IOException.class, () -> SsTableBlockCodec.decode(invalid));
    }

    @Test
    void rejectsTruncatedInnerRecordHeaderWithCompleteOuterFrame() {
        byte[] payload = ByteBuffer.allocate(4 + 10 + 8).putInt(2)
                .put((byte) 1).putInt(1).putInt(0).put((byte) 0)
                .put(new byte[8]).array();
        IOException failure = assertThrows(IOException.class, () -> SsTableBlockCodec.decode(frame(payload)));
        assertFalse(failure instanceof EOFException);
    }

    @ParameterizedTest
    @ValueSource(ints = {0x60, 0x61})
    void rejectsDescendingAndDuplicateDecodedKeysWithValidChecksum(int secondKey) {
        byte[] invalid = EMPTY_A_AND_DELETED_B.clone();
        invalid[31] = (byte) secondKey;
        updateChecksum(invalid);
        assertThrows(IOException.class, () -> SsTableBlockCodec.decode(invalid));
    }

    private static SsTableRecord value(byte[] key, byte[] value) {
        return new SsTableRecord(ByteKey.copyOf(key), Value.copyOf(value));
    }

    private static SsTableRecord deletion(byte[] key) {
        return new SsTableRecord(ByteKey.copyOf(key), Tombstone.INSTANCE);
    }

    private static byte[] singleRecordFrame(int kind, int keyLength, int valueLength, byte[] content) {
        byte[] payload = ByteBuffer.allocate(4 + 9 + content.length).putInt(1)
                .put((byte) kind).putInt(keyLength).putInt(valueLength).put(content).array();
        return frame(payload);
    }

    /** 手工构造合法 CRC 的输入，测试校验和之外的结构不变量。 */
    private static byte[] frame(byte[] payload) {
        byte[] frame = ByteBuffer.allocate(8 + payload.length)
                .putInt(payload.length).putInt(0).put(payload).array();
        updateChecksum(frame);
        return frame;
    }

    private static void updateChecksum(byte[] frame) {
        CRC32C crc = new CRC32C();
        crc.update(frame, 0, 4);
        crc.update(frame, 8, frame.length - 8);
        ByteBuffer.wrap(frame).putInt(4, (int) crc.getValue());
    }
}
