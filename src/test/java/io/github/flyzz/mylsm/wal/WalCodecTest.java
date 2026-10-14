package io.github.flyzz.mylsm.wal;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WalCodecTest {
    // 固定样例由独立的逐位 CRC32C 计算生成，防止 encode/decode 同时出错仍能往返。
    private static final byte[] PUT_K_A = HexFormat.of().parseHex(
            "0000000b4eb2e6de0100000001000000016b41");
    private static final byte[] DELETE_K = HexFormat.of().parseHex(
            "0000000a756519b50200000001000000006b");

    @Test
    void matchesFixedPutLayoutAndChecksum() throws IOException {
        WalRecord record = put(new byte[]{0x6b}, new byte[]{0x41});
        assertArrayEquals(PUT_K_A, WalCodec.encode(record));
        assertEquals(record, WalCodec.decode(PUT_K_A));
    }

    @Test
    void matchesFixedDeleteLayoutAndChecksum() throws IOException {
        WalRecord record = delete(new byte[]{0x6b});
        assertArrayEquals(DELETE_K, WalCodec.encode(record));
        assertEquals(record, WalCodec.decode(DELETE_K));
    }

    @ParameterizedTest
    @MethodSource("binaryRecords")
    void roundTripsBinaryKeysValuesAndDeletion(WalRecord record) throws IOException {
        assertEquals(record, WalCodec.decode(WalCodec.encode(record)));
    }

    static Stream<WalRecord> binaryRecords() {
        return Stream.of(
                put(new byte[0], new byte[0]),
                put(new byte[0], new byte[]{0, (byte) 0xff}),
                put(new byte[]{0, (byte) 0x80, (byte) 0xff}, new byte[0]),
                put(new byte[]{0, (byte) 0xff}, new byte[]{(byte) 0x80, 0, 1}),
                delete(new byte[0]),
                delete(new byte[]{0, (byte) 0x80, (byte) 0xff}));
    }

    @Test
    void emptyValueRemainsDistinctFromDeletion() throws IOException {
        WalRecord emptyPut = put(new byte[0], new byte[0]);
        WalRecord deletion = delete(new byte[0]);
        assertEquals(1, WalCodec.encode(emptyPut)[8]);
        assertEquals(2, WalCodec.encode(deletion)[8]);
        assertEquals(Value.copyOf(new byte[0]), WalCodec.decode(WalCodec.encode(emptyPut)).entry());
        assertEquals(Tombstone.INSTANCE, WalCodec.decode(WalCodec.encode(deletion)).entry());
    }

    @Test
    void rejectsNullInputs() {
        assertThrows(NullPointerException.class, () -> WalCodec.encode(null));
        assertThrows(NullPointerException.class, () -> WalCodec.decode(null));
    }

    @Test
    void doesNotMutateInputOrShareEncodedArrays() throws IOException {
        WalRecord record = put(new byte[]{0x6b}, new byte[]{0x41});
        byte[] firstEncoding = WalCodec.encode(record);
        byte[] secondEncoding = WalCodec.encode(record);
        WalRecord decoded = WalCodec.decode(firstEncoding);
        assertArrayEquals(PUT_K_A, firstEncoding);

        Arrays.fill(firstEncoding, (byte) 0);
        assertArrayEquals(PUT_K_A, secondEncoding);
        assertEquals(record, decoded);
        assertArrayEquals(PUT_K_A, WalCodec.encode(record));
    }

    @Test
    void acceptsPayloadExactlyAtLimit() throws IOException {
        // 最大尺寸分摊给键和值，验证上限包含固定载荷字段。
        int keySize = 1024;
        byte[] key = new byte[keySize];
        byte[] value = new byte[WalCodec.MAX_PAYLOAD_SIZE - WalCodec.MIN_PAYLOAD_SIZE - keySize];
        key[0] = (byte) 0xff;
        value[value.length - 1] = (byte) 0x80;
        WalRecord record = put(key, value);

        byte[] frame = WalCodec.encode(record);
        assertEquals(WalCodec.PREFIX_SIZE + WalCodec.MAX_PAYLOAD_SIZE, frame.length);
        assertEquals(record, WalCodec.decode(frame));
    }

    @Test
    void rejectsPutAndDeleteOneByteOverLimit() {
        byte[] overLimit = new byte[WalCodec.MAX_PAYLOAD_SIZE - WalCodec.MIN_PAYLOAD_SIZE + 1];
        assertThrows(IllegalArgumentException.class,
                () -> WalCodec.encode(put(new byte[0], overLimit)));
        assertThrows(IllegalArgumentException.class,
                () -> WalCodec.encode(delete(overLimit)));
    }

    @Test
    void reportsEveryIncompleteFrameBoundaryAsEof() {
        for (int length = 0; length < PUT_K_A.length; length++) {
            byte[] truncated = Arrays.copyOf(PUT_K_A, length);
            assertThrows(EOFException.class, () -> WalCodec.decode(truncated),
                    "truncated length=" + length);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 8, 16_777_217, Integer.MAX_VALUE})
    void rejectsIllegalPayloadLengthBeforeReadingBody(int payloadLength) {
        byte[] prefix = ByteBuffer.allocate(8).putInt(payloadLength).putInt(0).array();
        IOException error = assertThrows(IOException.class, () -> WalCodec.decode(prefix));
        assertFalse(error instanceof EOFException);
    }

    @Test
    void rejectsBytesAfterTheSingleFrame() {
        byte[] extraByte = Arrays.copyOf(PUT_K_A, PUT_K_A.length + 1);
        assertThrows(IOException.class, () -> WalCodec.decode(extraByte));
        byte[] twoFrames = ByteBuffer.allocate(PUT_K_A.length + DELETE_K.length)
                .put(PUT_K_A).put(DELETE_K).array();
        assertThrows(IOException.class, () -> WalCodec.decode(twoFrames));
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 8, 12, 16, 17, 18})
    void detectsChangedChecksumTypeLengthsKeyAndValue(int offset) {
        byte[] corrupted = PUT_K_A.clone();
        corrupted[offset] ^= 1;
        IOException error = assertThrows(IOException.class, () -> WalCodec.decode(corrupted));
        assertFalse(error instanceof EOFException);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3, 255})
    void rejectsUnknownOperationWithValidChecksum(int operation) {
        byte[] invalid = frameWithValidChecksum(operation, 0, 0, new byte[0]);
        assertThrows(IOException.class, () -> WalCodec.decode(invalid));
    }

    @Test
    void rejectsNegativeAndOverflowingKeyValueLengthsWithValidChecksum() {
        for (int[] lengths : new int[][]{
                {-1, 0}, {0, -1}, {Integer.MAX_VALUE, Integer.MAX_VALUE}}) {
            byte[] invalid = frameWithValidChecksum(1, lengths[0], lengths[1], new byte[0]);
            assertThrows(IOException.class, () -> WalCodec.decode(invalid));
        }
    }

    @Test
    void rejectsMismatchedKeyValueLengthsAndUnusedPayloadWithValidChecksum() {
        byte[] missingByte = frameWithValidChecksum(1, 1, 1, new byte[]{0x6b});
        byte[] unusedByte = frameWithValidChecksum(1, 0, 0, new byte[]{0x6b});
        assertThrows(IOException.class, () -> WalCodec.decode(missingByte));
        assertThrows(IOException.class, () -> WalCodec.decode(unusedByte));
    }

    @Test
    void rejectsDeleteWithValueEvenWithValidChecksum() {
        byte[] invalid = frameWithValidChecksum(2, 1, 1, new byte[]{0x6b, 0x41});
        assertThrows(IOException.class, () -> WalCodec.decode(invalid));
    }

    private static WalRecord put(byte[] key, byte[] value) {
        return new WalRecord(ByteKey.copyOf(key), Value.copyOf(value));
    }

    private static WalRecord delete(byte[] key) {
        return new WalRecord(ByteKey.copyOf(key), Tombstone.INSTANCE);
    }

    /** 手工构造校验合法但语义非法的帧，确保进入校验后的结构检查。 */
    private static byte[] frameWithValidChecksum(int operation, int keyLength,
                                               int valueLength, byte[] content) {
        int payloadLength = 9 + content.length;
        byte[] frame = ByteBuffer.allocate(8 + payloadLength)
                .putInt(payloadLength).putInt(0).put((byte) operation)
                .putInt(keyLength).putInt(valueLength).put(content).array();
        CRC32C crc = new CRC32C();
        crc.update(frame, 0, 4);
        crc.update(frame, 8, payloadLength);
        ByteBuffer.wrap(frame).putInt(4, (int) crc.getValue());
        return frame;
    }
}
