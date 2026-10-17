package io.github.flyzz.mylsm.sstable;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;
import io.github.flyzz.mylsm.sstable.SsTableMetadataCodec.Footer;
import io.github.flyzz.mylsm.sstable.SsTableMetadataCodec.IndexEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;

import static java.nio.file.StandardOpenOption.READ;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsTableMergerTest {
    @TempDir
    Path directory;

    private int nextFile;

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    void emptyInputsHaveNoPositionAndCanBeExhaustedRepeatedly(int count) throws IOException {
        List<SsTableReader> readers = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                readers.add(SsTableReader.open(table(List.of())));
            }
            try (SsTableMerger merger = SsTableMerger.open(readers)) {
                assertThrows(IllegalStateException.class, merger::record);
                assertFalse(merger.next());
                assertFalse(merger.next());
                assertThrows(IllegalStateException.class, merger::record);
            }
            for (SsTableReader reader : readers) {
                assertEquals(Optional.empty(), reader.get(key(0)));
            }
        } finally {
            closeReaders(readers);
        }
    }

    @Test
    void choosesNewestValueOrTombstoneForEachKeyAndConsumesEveryDuplicate() throws IOException {
        try (SsTableReader oldest = SsTableReader.open(table(List.of(value(1, 1), value(2, 1), value(4, 1))));
             SsTableReader middle = SsTableReader.open(table(List.of(value(1, 2), deletion(2), value(3, 2))));
             SsTableReader newest = SsTableReader.open(table(List.of(deletion(1), value(2, 3), value(5, 3))));
             SsTableMerger merger = SsTableMerger.open(List.of(oldest, middle, newest))) {
            List<SsTableRecord> actual = collect(merger);
            assertEquals(List.of(deletion(1), value(2, 3), value(3, 2), value(4, 1), value(5, 3)), actual);
            // 输出已严格递增且唯一，可以直接写成合法 SSTable；这里尚未发布文件集。
            Path output = table(actual);
            try (SsTableReader reader = SsTableReader.open(output)) {
                List<SsTableRecord> restored = new ArrayList<>();
                reader.scan(restored::add);
                assertEquals(actual, restored);
            }
        }
    }

    @Test
    void inputListOrderDeterminesRecencyAndEmptyTablesDoNotAffectIt() throws IOException {
        try (SsTableReader a = SsTableReader.open(table(List.of(value(1, 10))));
             SsTableReader empty = SsTableReader.open(table(List.of()));
             SsTableReader b = SsTableReader.open(table(List.of(deletion(1))))) {
            try (SsTableMerger merger = SsTableMerger.open(List.of(a, empty, b))) {
                assertEquals(List.of(deletion(1)), collect(merger));
            }
            try (SsTableMerger merger = SsTableMerger.open(List.of(b, empty, a))) {
                assertEquals(List.of(value(1, 10)), collect(merger));
            }
        }
    }

    @Test
    void preservesUnsignedBinaryKeysEmptyValuesAndLargeRecordsAcrossBlocks() throws IOException {
        SsTableRecord emptyKey = new SsTableRecord(ByteKey.copyOf(new byte[0]), Value.copyOf(new byte[0]));
        SsTableRecord large = new SsTableRecord(key(128), Value.copyOf(new byte[10 * 1024]));
        List<SsTableRecord> older = List.of(emptyKey, value(0, 1), value(127, 2), large, value(255, 3));
        List<SsTableRecord> newer = List.of(value(0, 4), deletion(127), deletion(255));
        Path olderPath = table(older);
        Path newerPath = table(newer);
        byte[] originalOlder = Files.readAllBytes(olderPath);
        byte[] originalNewer = Files.readAllBytes(newerPath);
        assertTrue(index(originalOlder).size() > 1);
        try (SsTableReader first = SsTableReader.open(olderPath);
             SsTableReader second = SsTableReader.open(newerPath);
             SsTableMerger merger = SsTableMerger.open(List.of(first, second))) {
            assertEquals(List.of(emptyKey, value(0, 4), deletion(127), large, deletion(255)), collect(merger));
        }
        assertArrayEquals(originalOlder, Files.readAllBytes(olderPath));
        assertArrayEquals(originalNewer, Files.readAllBytes(newerPath));
    }

    @Test
    void oneInputIsStreamedWithoutChangingItsStates() throws IOException {
        List<SsTableRecord> records = List.of(deletion(0), value(1, 2), deletion(2));
        try (SsTableReader reader = SsTableReader.open(table(records));
             SsTableMerger merger = SsTableMerger.open(List.of(reader))) {
            assertTrue(merger.next());
            assertEquals(records.getFirst(), merger.record());
            assertSame(merger.record(), merger.record());
            assertTrue(merger.next());
            assertEquals(records.get(1), merger.record());
            assertTrue(merger.next());
            assertEquals(records.getLast(), merger.record());
            assertFalse(merger.next());
            assertFalse(merger.next());
            assertThrows(IllegalStateException.class, merger::record);
            assertThrows(IllegalStateException.class, () -> reader.get(key(1)));
        }
    }

    @Test
    void manyInterleavedGenerationsAgreeWithAnIndependentLatestStateModel() throws IOException {
        Random random = new Random(20261017);
        Map<ByteKey, SsTableRecord> expected = new TreeMap<>();
        List<SsTableReader> readers = new ArrayList<>();
        try {
            for (int generation = 0; generation < 7; generation++) {
                Map<ByteKey, SsTableRecord> tableState = new TreeMap<>();
                for (int operation = 0; operation < 120; operation++) {
                    int key = random.nextInt(256);
                    byte[] bytes = new byte[300];
                    Arrays.fill(bytes, (byte) generation);
                    SsTableRecord record = random.nextInt(4) == 0 ? deletion(key)
                            : new SsTableRecord(key(key), Value.copyOf(bytes));
                    tableState.put(record.key(), record);
                    expected.put(record.key(), record);
                }
                readers.add(SsTableReader.open(table(List.copyOf(tableState.values()))));
            }
            try (SsTableMerger merger = SsTableMerger.open(readers)) {
                assertEquals(List.copyOf(expected.values()), collect(merger));
            }
        } finally {
            closeReaders(readers);
        }
    }

    @Test
    void opensWithoutDataReadsAndDoesNotLoadUnneededLaterBlocks() throws IOException {
        Path firstPath = table(List.of(large(1), large(3), large(5)));
        Path secondPath = table(List.of(large(2), large(4), large(6)));
        List<IndexEntry> firstIndex = index(Files.readAllBytes(firstPath));
        List<IndexEntry> secondIndex = index(Files.readAllBytes(secondPath));
        RecordingInput left = new RecordingInput(firstPath);
        RecordingInput right = new RecordingInput(secondPath);
        try (SsTableReader first = SsTableReader.open(left);
             SsTableReader second = SsTableReader.open(right)) {
            left.bytesRead = 0;
            right.bytesRead = 0;
            try (SsTableMerger merger = SsTableMerger.open(List.of(first, second))) {
                assertEquals(0, left.bytesRead + right.bytesRead);
                assertTrue(merger.next());
                assertEquals(key(1), merger.record().key());
                // 首次初始化两路头；输出 1 前仅把左路推进到 3，未触及两路末块。
                assertEquals(firstIndex.get(0).frameLength() + firstIndex.get(1).frameLength(), left.bytesRead);
                assertEquals(secondIndex.getFirst().frameLength(), right.bytesRead);
                int bytesBeforeRecord = left.bytesRead + right.bytesRead;
                assertEquals(key(1), merger.record().key());
                assertEquals(bytesBeforeRecord, left.bytesRead + right.bytesRead);
            }
            assertEquals(0, left.closeCalls + right.closeCalls);
            assertTrue(first.get(key(5)).isPresent());
            assertTrue(second.get(key(6)).isPresent());
        }
        assertEquals(1, left.closeCalls);
        assertEquals(1, right.closeCalls);
    }

    @Test
    void earlyAndRepeatedCloseReleaseOnlyTheMergersCursors() throws IOException {
        RecordingInput input = new RecordingInput(table(List.of(value(1, 1), value(2, 2))));
        try (SsTableReader reader = SsTableReader.open(input)) {
            SsTableMerger merger = SsTableMerger.open(List.of(reader));
            assertTrue(merger.next());
            merger.close();
            assertThrows(IllegalStateException.class, merger::next);
            assertThrows(IllegalStateException.class, merger::record);
            assertEquals(0, input.closeCalls);
            assertEquals(Optional.of(value(2, 2).entry()), reader.get(key(2)));
            try (SsTableReader.Cursor replacement = reader.openCursor()) {
                merger.close();
                assertThrows(IllegalStateException.class, reader::openCursor);
                assertTrue(replacement.next());
                assertEquals(value(1, 1), replacement.record());
            }
        }
        assertEquals(1, input.closeCalls);
    }

    @Test
    void failedAcquisitionReleasesEarlierCursorsAndPreservesTheCallersOccupiedCursor() throws IOException {
        try (SsTableReader first = SsTableReader.open(table(List.of(value(1, 1))));
             SsTableReader occupied = SsTableReader.open(table(List.of(value(2, 2))));
             SsTableReader.Cursor ownedByCaller = occupied.openCursor()) {
            assertThrows(IllegalStateException.class, () -> SsTableMerger.open(List.of(first, occupied)));
            assertEquals(Optional.of(value(1, 1).entry()), first.get(key(1)));
            assertTrue(ownedByCaller.next());
            assertEquals(value(2, 2), ownedByCaller.record());
        }
    }

    @Test
    void duplicateReaderFailsWithoutLeavingItOccupied() throws IOException {
        try (SsTableReader reader = SsTableReader.open(table(List.of(value(1, 1))))) {
            assertThrows(IllegalStateException.class, () -> SsTableMerger.open(List.of(reader, reader)));
            assertEquals(Optional.of(value(1, 1).entry()), reader.get(key(1)));
        }
    }

    @Test
    void rejectsNullInputsBeforeAcquiringAnyCursor() throws IOException {
        try (SsTableReader reader = SsTableReader.open(table(List.of(value(1, 1))))) {
            assertThrows(NullPointerException.class, () -> SsTableMerger.open(null));
            assertThrows(NullPointerException.class, () -> SsTableMerger.open(Arrays.asList(reader, null)));
            assertEquals(Optional.of(value(1, 1).entry()), reader.get(key(1)));
        }
    }

    @Test
    void primingIoFailurePropagatesOriginalErrorAndReleasesEveryAcquiredCursor() throws IOException {
        RecordingInput input = new RecordingInput(table(List.of(value(1, 2))));
        try (SsTableReader first = SsTableReader.open(table(List.of(value(0, 1))));
             SsTableReader bad = SsTableReader.open(input);
             SsTableReader last = SsTableReader.open(table(List.of(value(2, 3))));
             SsTableMerger merger = SsTableMerger.open(List.of(first, bad, last))) {
            input.failureOffset = 8;
            assertSame(input.failure, assertThrows(IOException.class, merger::next));
            int readsAfterFailure = input.bytesRead;
            assertThrows(IllegalStateException.class, merger::record);
            assertThrows(IllegalStateException.class, merger::next);
            assertEquals(readsAfterFailure, input.bytesRead);
            assertThrows(IllegalStateException.class, () -> bad.get(key(1)));
            assertEquals(Optional.of(value(0, 1).entry()), first.get(key(0)));
            assertEquals(Optional.of(value(2, 3).entry()), last.get(key(2)));
            assertEquals(0, input.closeCalls);
        }
        assertEquals(1, input.closeCalls);
    }

    @Test
    void corruptionInAnOverwrittenInputCannotBeSkippedOrExposedAsPartialSuccess() throws IOException {
        Path olderPath = table(List.of(large(1), large(9)));
        byte[] bytes = Files.readAllBytes(olderPath);
        IndexEntry secondBlock = index(bytes).get(1);
        bytes[(int) secondBlock.offset() + 4] ^= 1;
        Files.write(olderPath, bytes);
        try (SsTableReader older = SsTableReader.open(olderPath);
             SsTableReader newer = SsTableReader.open(table(List.of(value(0, 1), value(1, 2))));
             SsTableMerger merger = SsTableMerger.open(List.of(older, newer))) {
            assertTrue(merger.next());
            assertEquals(value(0, 1), merger.record());
            // 键 1 虽由 newer 获胜，older 也必须推进；其下一块坏了，整次 next 失败。
            assertThrows(IOException.class, merger::next);
            assertThrows(IllegalStateException.class, merger::record);
            assertThrows(IllegalStateException.class, merger::next);
            assertEquals(Optional.of(value(1, 2).entry()), newer.get(key(1)));
        }
        assertArrayEquals(bytes, Files.readAllBytes(olderPath));
    }

    @Test
    void externalReaderClosureInvalidatesTheMergeAndReleasesOtherInputs() throws IOException {
        try (SsTableReader first = SsTableReader.open(table(List.of(value(1, 1))));
             SsTableReader second = SsTableReader.open(table(List.of(value(2, 2))));
             SsTableMerger merger = SsTableMerger.open(List.of(first, second))) {
            second.close(); // 违反借用期间契约，必须明确失败，不能当作该输入已耗尽。
            assertThrows(IllegalStateException.class, merger::next);
            assertThrows(IllegalStateException.class, merger::record);
            assertEquals(Optional.of(value(1, 1).entry()), first.get(key(1)));
        }
    }

    private Path table(List<SsTableRecord> records) throws IOException {
        Path path = directory.resolve("input-" + nextFile++ + ".sst");
        SsTableWriter.write(path, records);
        return path;
    }

    private static List<SsTableRecord> collect(SsTableMerger merger) throws IOException {
        List<SsTableRecord> records = new ArrayList<>();
        while (merger.next()) {
            records.add(merger.record());
        }
        return records;
    }

    private static void closeReaders(List<SsTableReader> readers) throws IOException {
        for (SsTableReader reader : readers) {
            reader.close();
        }
    }

    private static ByteKey key(int value) {
        return ByteKey.copyOf(new byte[]{(byte) value});
    }

    private static SsTableRecord value(int key, int value) {
        return new SsTableRecord(key(key), Value.copyOf(new byte[]{(byte) value}));
    }

    private static SsTableRecord deletion(int key) {
        return new SsTableRecord(key(key), Tombstone.INSTANCE);
    }

    private static SsTableRecord large(int key) {
        return new SsTableRecord(key(key), Value.copyOf(new byte[5000]));
    }

    private static List<IndexEntry> index(byte[] bytes) throws IOException {
        Footer footer = SsTableMetadataCodec.decodeFooter(Arrays.copyOfRange(bytes,
                bytes.length - SsTableMetadataCodec.FOOTER_SIZE, bytes.length), bytes.length);
        return SsTableMetadataCodec.decodeIndex(Arrays.copyOfRange(bytes, (int) footer.indexOffset(),
                (int) footer.indexOffset() + footer.indexLength()), footer.indexOffset());
    }

    private static final class RecordingInput implements SsTableReader.ReadIo {
        private final FileChannel channel;
        private final IOException failure = new IOException("input read failed");
        private long failureOffset = -1;
        private int bytesRead;
        private int closeCalls;

        private RecordingInput(Path path) throws IOException {
            channel = FileChannel.open(path, READ);
        }

        @Override
        public long size() throws IOException {
            return channel.size();
        }

        @Override
        public int read(ByteBuffer target, long offset) throws IOException {
            if (offset == failureOffset) {
                throw failure;
            }
            int count = channel.read(target, offset);
            if (count > 0) {
                bytesRead += count;
            }
            return count;
        }

        @Override
        public void close() throws IOException {
            closeCalls++;
            channel.close();
        }
    }
}
