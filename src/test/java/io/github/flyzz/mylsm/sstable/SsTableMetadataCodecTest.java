package io.github.flyzz.mylsm.sstable;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;
import io.github.flyzz.mylsm.sstable.SsTableMetadataCodec.Footer;
import io.github.flyzz.mylsm.sstable.SsTableMetadataCodec.IndexEntry;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsTableMetadataCodecTest {
    // 校验值由独立逐位 CRC32C 脚本生成。
    private static final byte[] HEADER = hex("4d59535400000001");
    private static final byte[] EMPTY_INDEX = hex("000000046c6d0c3a00000000");
    private static final byte[] EMPTY_FOOTER = hex("00000000000000080000000c000000014d594654b02090b7");
    private static final byte[] TWO_BLOCK_INDEX = hex(
            "000000269469aad100000002000000016b000000000000000800000017"
                    + "000000017a000000000000001f00000016");
    private static final byte[] TWO_BLOCK_FOOTER = hex(
            "00000000000000350000002e000000014d594654c2782685");
    private static final int MAX_DATA_FRAME_SIZE = SsTableBlockCodec.PREFIX_SIZE
            + SsTableBlockCodec.MAX_PAYLOAD_SIZE;

    @Test
    void matchesFixedHeaderAndRejectsUnknownMagicOrVersion() throws IOException {
        assertArrayEquals(HEADER, SsTableMetadataCodec.encodeHeader());
        SsTableMetadataCodec.decodeHeader(HEADER);
        for (int offset : new int[]{0, 7}) {
            byte[] invalid = HEADER.clone();
            invalid[offset] ^= 1;
            assertThrows(IOException.class, () -> SsTableMetadataCodec.decodeHeader(invalid));
        }
    }

    @Test
    void matchesFixedEmptyIndexAndFooter() throws IOException {
        assertArrayEquals(EMPTY_INDEX, SsTableMetadataCodec.encodeIndex(List.of(), 8));
        assertEquals(List.of(), SsTableMetadataCodec.decodeIndex(EMPTY_INDEX, 8));
        assertArrayEquals(EMPTY_FOOTER, SsTableMetadataCodec.encodeFooter(new Footer(8, 12)));
        assertEquals(new Footer(8, 12), SsTableMetadataCodec.decodeFooter(EMPTY_FOOTER, 44));
    }

    @Test
    void matchesFixedTwoBlockIndexAndFooter() throws IOException {
        assertArrayEquals(TWO_BLOCK_INDEX, SsTableMetadataCodec.encodeIndex(twoEntries(), 53));
        assertEquals(twoEntries(), SsTableMetadataCodec.decodeIndex(TWO_BLOCK_INDEX, 53));
        assertArrayEquals(TWO_BLOCK_FOOTER, SsTableMetadataCodec.encodeFooter(new Footer(53, 46)));
        assertEquals(new Footer(53, 46), SsTableMetadataCodec.decodeFooter(TWO_BLOCK_FOOTER, 123));
    }

    @Test
    void assemblesAndLocatesAnEmptyFileInMemory() throws IOException {
        byte[] bytes = ByteBuffer.allocate(44).put(SsTableMetadataCodec.encodeHeader())
                .put(SsTableMetadataCodec.encodeIndex(List.of(), 8))
                .put(SsTableMetadataCodec.encodeFooter(new Footer(8, 12))).array();
        SsTableMetadataCodec.decodeHeader(Arrays.copyOf(bytes, 8));
        Footer footer = SsTableMetadataCodec.decodeFooter(Arrays.copyOfRange(bytes, 20, 44), bytes.length);
        assertEquals(List.of(), SsTableMetadataCodec.decodeIndex(Arrays.copyOfRange(bytes,
                (int) footer.indexOffset(), (int) footer.indexOffset() + footer.indexLength()),
                footer.indexOffset()));
    }

    @Test
    void assemblesAndLocatesActualDataBlocksInMemory() throws IOException {
        byte[] first = SsTableBlockCodec.encode(List.of(new SsTableRecord(
                key(new byte[]{0x6b}), Value.copyOf(new byte[]{0x41}))));
        byte[] second = SsTableBlockCodec.encode(List.of(new SsTableRecord(
                key(new byte[]{0x7a}), Tombstone.INSTANCE)));
        int indexOffset = 8 + first.length + second.length;
        byte[] index = SsTableMetadataCodec.encodeIndex(twoEntries(), indexOffset);
        byte[] bytes = ByteBuffer.allocate(indexOffset + index.length + 24)
                .put(HEADER).put(first).put(second).put(index)
                .put(SsTableMetadataCodec.encodeFooter(new Footer(indexOffset, index.length))).array();
        assertEquals(123, bytes.length);
        Footer footer = SsTableMetadataCodec.decodeFooter(
                Arrays.copyOfRange(bytes, bytes.length - 24, bytes.length), bytes.length);
        List<IndexEntry> entries = SsTableMetadataCodec.decodeIndex(Arrays.copyOfRange(bytes,
                indexOffset, indexOffset + footer.indexLength()), footer.indexOffset());
        for (IndexEntry entry : entries) {
            byte[] data = Arrays.copyOfRange(bytes, (int) entry.offset(),
                    (int) entry.offset() + entry.frameLength());
            assertEquals(entry.maxKey(), SsTableBlockCodec.decode(data).getLast().key());
        }
    }

    @Test
    void preservesVariableBinaryKeysAndUnsignedOrdering() throws IOException {
        List<IndexEntry> entries = List.of(
                new IndexEntry(key(new byte[0]), 8, 21),
                new IndexEntry(key(new byte[]{0}), 29, 22),
                new IndexEntry(key(new byte[]{0, 1}), 51, 23),
                new IndexEntry(key(new byte[]{0x7f}), 74, 22),
                new IndexEntry(key(new byte[]{(byte) 0x80, (byte) 0xff}), 96, 23));
        assertEquals(entries, SsTableMetadataCodec.decodeIndex(
                SsTableMetadataCodec.encodeIndex(entries, 119), 119));
    }

    @Test
    void preservesOffsetsBeyondTheIntRangeWithoutAllocatingDataBlocks() throws IOException {
        List<IndexEntry> entries = new ArrayList<>();
        long offset = 8;
        for (int i = 0; i < 129; i++) {
            entries.add(new IndexEntry(key(new byte[]{(byte) i}), offset, MAX_DATA_FRAME_SIZE));
            offset += MAX_DATA_FRAME_SIZE;
        }
        assertTrue(offset > Integer.MAX_VALUE);
        assertEquals(entries, SsTableMetadataCodec.decodeIndex(
                SsTableMetadataCodec.encodeIndex(entries, offset), offset));
        Footer footer = new Footer(offset, 4096);
        assertEquals(footer, SsTableMetadataCodec.decodeFooter(
                SsTableMetadataCodec.encodeFooter(footer), offset + 4096 + 24));
    }

    @Test
    void doesNotMutateOrRetainInputArraysAndReturnsImmutableEntries() throws IOException {
        byte[] frame = TWO_BLOCK_INDEX.clone();
        List<IndexEntry> entries = SsTableMetadataCodec.decodeIndex(frame, 53);
        assertArrayEquals(TWO_BLOCK_INDEX, frame);
        Arrays.fill(frame, (byte) 0);
        assertEquals(twoEntries(), entries);
        assertThrows(UnsupportedOperationException.class, entries::clear);
        byte[] header = SsTableMetadataCodec.encodeHeader();
        header[0] = 0;
        assertArrayEquals(HEADER, SsTableMetadataCodec.encodeHeader());
        List<IndexEntry> input = new ArrayList<>(twoEntries());
        byte[] output = SsTableMetadataCodec.encodeIndex(input, 53);
        input.clear();
        assertArrayEquals(TWO_BLOCK_INDEX, output);
    }

    @Test
    void rejectsNullInputs() {
        assertThrows(NullPointerException.class, () -> SsTableMetadataCodec.decodeHeader(null));
        assertThrows(NullPointerException.class, () -> SsTableMetadataCodec.encodeIndex(null, 8));
        assertThrows(NullPointerException.class, () -> SsTableMetadataCodec.decodeIndex(null, 8));
        assertThrows(NullPointerException.class,
                () -> SsTableMetadataCodec.encodeIndex(Arrays.asList((IndexEntry) null), 8));
        assertThrows(NullPointerException.class, () -> new IndexEntry(null, 8, 21));
        assertThrows(NullPointerException.class, () -> SsTableMetadataCodec.encodeFooter(null));
        assertThrows(NullPointerException.class, () -> SsTableMetadataCodec.decodeFooter(null, 44));
    }

    @Test
    void rejectsEveryTruncationAndTrailingBytesInMetadata() {
        for (int length = 0; length < HEADER.length; length++) {
            byte[] bytes = Arrays.copyOf(HEADER, length);
            assertThrows(EOFException.class, () -> SsTableMetadataCodec.decodeHeader(bytes));
        }
        for (int length = 0; length < TWO_BLOCK_INDEX.length; length++) {
            byte[] bytes = Arrays.copyOf(TWO_BLOCK_INDEX, length);
            assertThrows(EOFException.class, () -> SsTableMetadataCodec.decodeIndex(bytes, 53));
        }
        for (int length = 0; length < TWO_BLOCK_FOOTER.length; length++) {
            byte[] bytes = Arrays.copyOf(TWO_BLOCK_FOOTER, length);
            assertThrows(EOFException.class, () -> SsTableMetadataCodec.decodeFooter(bytes, 123));
        }
        assertThrows(IOException.class,
                () -> SsTableMetadataCodec.decodeHeader(Arrays.copyOf(HEADER, 9)));
        assertThrows(IOException.class,
                () -> SsTableMetadataCodec.decodeIndex(Arrays.copyOf(TWO_BLOCK_INDEX, 47), 53));
        assertThrows(IOException.class,
                () -> SsTableMetadataCodec.decodeFooter(Arrays.copyOf(TWO_BLOCK_FOOTER, 25), 123));
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 3, 67_108_865, Integer.MAX_VALUE})
    void rejectsInvalidIndexPayloadLengthsBeforeReadingBody(int length) {
        byte[] prefix = ByteBuffer.allocate(8).putInt(length).putInt(0).array();
        IOException error = assertThrows(IOException.class,
                () -> SsTableMetadataCodec.decodeIndex(prefix, 8));
        assertFalse(error instanceof EOFException);
    }

    @Test
    void rejectsBitChangesThroughoutIndexAndFooter() {
        for (int offset = 0; offset < TWO_BLOCK_INDEX.length; offset++) {
            byte[] bytes = TWO_BLOCK_INDEX.clone();
            bytes[offset] ^= 1;
            assertThrows(IOException.class, () -> SsTableMetadataCodec.decodeIndex(bytes, 53));
        }
        for (int offset = 0; offset < TWO_BLOCK_FOOTER.length; offset++) {
            byte[] bytes = TWO_BLOCK_FOOTER.clone();
            bytes[offset] ^= 1;
            assertThrows(IOException.class, () -> SsTableMetadataCodec.decodeFooter(bytes, 123));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 3, Integer.MAX_VALUE})
    void rejectsInvalidCountsWithValidChecksum(int count) {
        byte[] frame = TWO_BLOCK_INDEX.clone();
        ByteBuffer.wrap(frame).putInt(8, count);
        updateIndexChecksum(frame);
        assertThrows(IOException.class, () -> SsTableMetadataCodec.decodeIndex(frame, 53));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 19, 16_777_208, Integer.MAX_VALUE})
    void rejectsInvalidKeyLengthsWithValidChecksum(int keyLength) {
        byte[] frame = TWO_BLOCK_INDEX.clone();
        ByteBuffer.wrap(frame).putInt(12, keyLength);
        updateIndexChecksum(frame);
        assertThrows(IOException.class, () -> SsTableMetadataCodec.decodeIndex(frame, 53));
    }

    @Test
    void rejectsAnIncompleteInnerEntryInACompleteFrame() {
        byte[] frame = ByteBuffer.allocate(8 + 4 + 18 + 14).putInt(4 + 18 + 14).putInt(0).putInt(2)
                .putInt(2).put(new byte[]{0, 1}).putLong(8).putInt(23).put(new byte[14]).array();
        updateIndexChecksum(frame);
        IOException error = assertThrows(IOException.class,
                () -> SsTableMetadataCodec.decodeIndex(frame, 31));
        assertFalse(error instanceof EOFException);
    }

    @Test
    void rejectsDuplicateDescendingAndImpossibleKeys() {
        for (int secondKey : new int[]{0x6a, 0x6b}) {
            List<IndexEntry> entries = List.of(twoEntries().getFirst(),
                    new IndexEntry(key(new byte[]{(byte) secondKey}), 31, 22));
            assertThrows(IllegalArgumentException.class, () -> SsTableMetadataCodec.encodeIndex(entries, 53));
            byte[] frame = TWO_BLOCK_INDEX.clone();
            frame[33] = (byte) secondKey;
            updateIndexChecksum(frame);
            assertThrows(IOException.class, () -> SsTableMetadataCodec.decodeIndex(frame, 53));
        }
        // 21 字节最小块只能容纳空键，不能声称它的最大键是一个字节。
        assertThrows(IllegalArgumentException.class, () -> SsTableMetadataCodec.encodeIndex(
                List.of(new IndexEntry(key(new byte[]{1}), 8, 21)), 29));
    }

    @Test
    void rejectsNonContiguousOrOutOfRangeDataBlocks() {
        for (long offset : new long[]{Long.MIN_VALUE, -1, 7, 9, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> SsTableMetadataCodec.encodeIndex(
                    List.of(new IndexEntry(key(new byte[]{1}), offset, 22)), 30));
            byte[] frame = TWO_BLOCK_INDEX.clone();
            ByteBuffer.wrap(frame).putLong(17, offset);
            updateIndexChecksum(frame);
            assertThrows(IOException.class, () -> SsTableMetadataCodec.decodeIndex(frame, 53));
        }
        for (long offset : new long[]{30, 32, Long.MAX_VALUE}) {
            byte[] frame = TWO_BLOCK_INDEX.clone();
            ByteBuffer.wrap(frame).putLong(34, offset);
            updateIndexChecksum(frame);
            assertThrows(IOException.class, () -> SsTableMetadataCodec.decodeIndex(frame, 53));
            assertThrows(IllegalArgumentException.class, () -> SsTableMetadataCodec.encodeIndex(
                    List.of(twoEntries().getFirst(), new IndexEntry(key(new byte[]{0x7a}), offset, 22)), 53));
        }
        for (long end : new long[]{-1, 7, 52, 54, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> SsTableMetadataCodec.encodeIndex(twoEntries(), end));
            assertThrows(IOException.class, () -> SsTableMetadataCodec.decodeIndex(TWO_BLOCK_INDEX, end));
        }
        assertThrows(IllegalArgumentException.class, () -> SsTableMetadataCodec.encodeIndex(List.of(), 9));
        assertThrows(IOException.class, () -> SsTableMetadataCodec.decodeIndex(EMPTY_INDEX, 9));
    }

    @Test
    void rejectsInvalidBlockFrameLengths() {
        for (int length : new int[]{Integer.MIN_VALUE, -1, 0, 20, 21, MAX_DATA_FRAME_SIZE + 1}) {
            assertThrows(IllegalArgumentException.class, () -> SsTableMetadataCodec.encodeIndex(
                    List.of(new IndexEntry(key(new byte[]{1}), 8, length)), 53));
            byte[] frame = TWO_BLOCK_INDEX.clone();
            ByteBuffer.wrap(frame).putInt(25, length);
            updateIndexChecksum(frame);
            assertThrows(IOException.class, () -> SsTableMetadataCodec.decodeIndex(frame, 53));
        }
    }

    @Test
    void enforcesIndexCapacityAtTheExactLimitAndOneByteOver() throws IOException {
        int maxKeyLength = SsTableBlockCodec.MAX_RECORD_SIZE - SsTableBlockCodec.RECORD_HEADER_SIZE;
        List<IndexEntry> entries = new ArrayList<>();
        long end = 8;
        for (int i = 0; i < 4; i++) {
            // 4 + 4 * (16 + maxKeyLength) 比 64 MiB 多 32 字节。
            byte[] bytes = new byte[maxKeyLength - (i == 3 ? 32 : 0)];
            bytes[0] = (byte) (i + 1);
            entries.add(new IndexEntry(key(bytes), end, MAX_DATA_FRAME_SIZE));
            end += MAX_DATA_FRAME_SIZE;
        }
        byte[] frame = SsTableMetadataCodec.encodeIndex(entries, end);
        assertEquals(SsTableMetadataCodec.MAX_INDEX_FRAME_SIZE, frame.length);
        assertEquals(entries, SsTableMetadataCodec.decodeIndex(frame, end));
        byte[] longer = new byte[maxKeyLength - 31];
        longer[0] = 4;
        entries.set(3, new IndexEntry(key(longer), entries.getLast().offset(), MAX_DATA_FRAME_SIZE));
        long indexOffset = end;
        assertThrows(IOException.class, () -> SsTableMetadataCodec.encodeIndex(entries, indexOffset));
    }

    @Test
    void rejectsFooterFieldsEvenWithRecomputedChecksum() {
        for (long offset : new long[]{-1, 7, 54, Long.MAX_VALUE}) {
            byte[] bytes = TWO_BLOCK_FOOTER.clone();
            ByteBuffer.wrap(bytes).putLong(0, offset);
            updateFooterChecksum(bytes);
            assertThrows(IOException.class, () -> SsTableMetadataCodec.decodeFooter(bytes, 123));
        }
        for (int length : new int[]{-1, 0, 11, SsTableMetadataCodec.MAX_INDEX_FRAME_SIZE + 1,
                Integer.MAX_VALUE}) {
            Footer footer = new Footer(53, length);
            assertThrows(IllegalArgumentException.class, () -> SsTableMetadataCodec.encodeFooter(footer));
            byte[] bytes = TWO_BLOCK_FOOTER.clone();
            ByteBuffer.wrap(bytes).putInt(8, length);
            updateFooterChecksum(bytes);
            assertThrows(IOException.class, () -> SsTableMetadataCodec.decodeFooter(bytes, 123));
        }
        for (int fieldOffset : new int[]{12, 16}) {
            byte[] bytes = TWO_BLOCK_FOOTER.clone();
            ByteBuffer.wrap(bytes).putInt(fieldOffset, 2);
            updateFooterChecksum(bytes);
            assertThrows(IOException.class, () -> SsTableMetadataCodec.decodeFooter(bytes, 123));
        }
    }

    @Test
    void validatesFooterAgainstFileSizeAndPreventsOverflow() throws IOException {
        for (long size : new long[]{-1, 0, 43, 122, 124, Long.MAX_VALUE}) {
            assertThrows(IOException.class, () -> SsTableMetadataCodec.decodeFooter(TWO_BLOCK_FOOTER, size));
        }
        assertThrows(IllegalArgumentException.class,
                () -> SsTableMetadataCodec.encodeFooter(new Footer(7, 12)));
        assertThrows(IllegalArgumentException.class,
                () -> SsTableMetadataCodec.encodeFooter(new Footer(Long.MAX_VALUE - 20, 12)));
        Footer largestFile = new Footer(Long.MAX_VALUE - 24 - 12, 12);
        assertEquals(largestFile, SsTableMetadataCodec.decodeFooter(
                SsTableMetadataCodec.encodeFooter(largestFile), Long.MAX_VALUE));
    }

    private static List<IndexEntry> twoEntries() {
        return List.of(new IndexEntry(key(new byte[]{0x6b}), 8, 23),
                new IndexEntry(key(new byte[]{0x7a}), 31, 22));
    }

    private static ByteKey key(byte[] bytes) {
        return ByteKey.copyOf(bytes);
    }

    private static byte[] hex(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    private static void updateIndexChecksum(byte[] frame) {
        CRC32C crc = new CRC32C();
        crc.update(frame, 0, 4);
        crc.update(frame, 8, frame.length - 8);
        ByteBuffer.wrap(frame).putInt(4, (int) crc.getValue());
    }

    private static void updateFooterChecksum(byte[] bytes) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, 20);
        ByteBuffer.wrap(bytes).putInt(20, (int) crc.getValue());
    }
}
