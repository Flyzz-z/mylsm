package io.github.flyzz.mylsm.sstable;

import io.github.flyzz.mylsm.memtable.TreeMapMemTable;
import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;
import io.github.flyzz.mylsm.sstable.SsTableMetadataCodec.Footer;
import io.github.flyzz.mylsm.sstable.SsTableMetadataCodec.IndexEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32C;

import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsTableReaderTest {
    private static final SsTableRecord SAMPLE = value(new byte[]{0x6b}, new byte[]{0x41});

    @TempDir
    Path directory;

    @Test
    void opensEmptyTableWithoutReadingData() throws IOException {
        Path path = directory.resolve("empty.sst");
        SsTableWriter.write(path, List.of());
        RecordingInput input = new RecordingInput(path);
        try (SsTableReader reader = SsTableReader.open(input)) {
            input.clearReads();
            assertEquals(Optional.empty(), reader.get(key(new byte[0])));
            assertEquals(Optional.empty(), reader.get(key(new byte[]{(byte) 0xff})));
            assertTrue(input.readOffsets.isEmpty());
        }
        assertEquals(1, input.closeCalls);
    }

    @Test
    void preservesBinaryKeysEmptyValuesAndTombstones() throws IOException {
        List<SsTableRecord> records = List.of(value(new byte[0], new byte[0]),
                deletion(new byte[]{0}), value(new byte[]{0, 1}, new byte[]{(byte) 0xff, 0}),
                deletion(new byte[]{0, (byte) 0xff}), value(new byte[]{0x7f}, new byte[0]),
                value(new byte[]{(byte) 0x80}, new byte[]{1}), deletion(new byte[]{(byte) 0xff}));
        Path path = directory.resolve("binary.sst");
        SsTableWriter.write(path, records);
        byte[] original = Files.readAllBytes(path);
        try (SsTableReader reader = SsTableReader.open(path)) {
            for (SsTableRecord record : records) {
                assertEquals(Optional.of(record.entry()), reader.get(record.key()));
            }
            for (byte[] missing : new byte[][]{{0, 0}, {1}, {0x7f, 0}, {(byte) 0xff, 0}}) {
                assertEquals(Optional.empty(), reader.get(key(missing)));
            }
        }
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test
    void locatesEveryBlockBoundaryAndMissingKeyGap() throws IOException {
        List<SsTableRecord> records = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            byte[] value = new byte[1300];
            value[0] = (byte) i;
            records.add(new SsTableRecord(integerKey(i * 2), Value.copyOf(value)));
        }
        Path path = directory.resolve("boundaries.sst");
        SsTableWriter.write(path, records);
        assertEquals(4, index(Files.readAllBytes(path)).size());
        try (SsTableReader reader = SsTableReader.open(path)) {
            for (SsTableRecord record : records) {
                assertEquals(Optional.of(record.entry()), reader.get(record.key()));
            }
            for (int i = 1; i <= 25; i += 2) {
                assertEquals(Optional.empty(), reader.get(integerKey(i)));
            }
        }
    }

    @Test
    void queriesAValueLargerThanTheBlockTarget() throws IOException {
        byte[] bytes = new byte[10 * 1024];
        bytes[bytes.length - 1] = (byte) 0xff;
        SsTableRecord record = value(new byte[]{1}, bytes);
        Path path = directory.resolve("large.sst");
        SsTableWriter.write(path, List.of(record));
        try (SsTableReader reader = SsTableReader.open(path)) {
            assertEquals(Optional.of(record.entry()), reader.get(record.key()));
        }
    }

    @Test
    void readsAConvertedMemTableSnapshotThroughPublicApis() throws IOException {
        TreeMapMemTable table = new TreeMapMemTable();
        ByteKey a = key(new byte[]{0x61});
        ByteKey b = key(new byte[]{0x62});
        table.put(a, Value.copyOf(new byte[]{1}));
        table.put(b, Value.copyOf(new byte[]{2}));
        table.put(a, Value.copyOf(new byte[]{3}));
        table.delete(b);
        Path path = directory.resolve("snapshot.sst");
        SsTableWriter.write(path, table.snapshot().stream()
                .map(record -> new SsTableRecord(record.key(), record.entry())).toList());
        try (SsTableReader reader = SsTableReader.open(path)) {
            assertEquals(Optional.of(Value.copyOf(new byte[]{3})), reader.get(a));
            assertEquals(Optional.of(Tombstone.INSTANCE), reader.get(b));
            assertEquals(Optional.empty(), reader.get(key(new byte[]{0x63})));
        }
    }

    @Test
    void loadsOnlyMetadataOnOpenAndOnlyTheCandidateBlockOnGet() throws IOException {
        Path path = threeBlockTable();
        byte[] bytes = Files.readAllBytes(path);
        Footer footer = footer(bytes);
        List<IndexEntry> entries = index(bytes);
        RecordingInput input = new RecordingInput(path);
        try (SsTableReader reader = SsTableReader.open(input)) {
            assertTrue(input.readOffsets.stream().noneMatch(offset -> offset >= 8 && offset < footer.indexOffset()));
            input.clearReads();
            assertTrue(reader.get(key(new byte[]{2})).isPresent());
            IndexEntry candidate = entries.get(1);
            assertEquals(candidate.offset(), input.readOffsets.getFirst().longValue());
            assertTrue(input.readOffsets.stream().allMatch(offset -> offset >= candidate.offset()
                    && offset < candidate.offset() + candidate.frameLength()));
            assertEquals(candidate.frameLength(), input.bytesRead);
            input.clearReads();
            assertEquals(Optional.empty(), reader.get(key(new byte[]{4})));
            assertTrue(input.readOffsets.isEmpty());
        }
    }

    @Test
    void handlesShortMetadataAndDataReads() throws IOException {
        Path path = sampleTable();
        RecordingInput input = new RecordingInput(path);
        input.maxRead = 2;
        try (SsTableReader reader = SsTableReader.open(input)) {
            assertTrue(input.readOffsets.size() > 3);
            input.clearReads();
            assertEquals(Optional.of(SAMPLE.entry()), reader.get(SAMPLE.key()));
            assertTrue(input.readOffsets.size() > 1);
            assertEquals(23, input.bytesRead);
        }
    }

    @Test
    void rejectsNullWithoutInvalidatingReaderAndDoesNotCreateMissingFiles() throws IOException {
        assertThrows(NullPointerException.class, () -> SsTableReader.open((Path) null));
        assertThrows(NullPointerException.class, () -> SsTableReader.open((SsTableReader.ReadIo) null));
        Path missing = directory.resolve("missing.sst");
        assertThrows(NoSuchFileException.class, () -> SsTableReader.open(missing));
        assertFalse(Files.exists(missing));
        try (SsTableReader reader = SsTableReader.open(sampleTable())) {
            assertThrows(NullPointerException.class, () -> reader.get(null));
            assertEquals(Optional.of(SAMPLE.entry()), reader.get(SAMPLE.key()));
        }
    }

    @Test
    void rejectsEveryTruncatedCompletedFileAndReleasesResourcesWithoutRepair() throws IOException {
        Path path = sampleTable();
        byte[] original = Files.readAllBytes(path);
        for (int length = 0; length < original.length; length++) {
            byte[] truncated = Arrays.copyOf(original, length);
            Files.write(path, truncated);
            RecordingInput input = new RecordingInput(path);
            assertThrows(IOException.class, () -> SsTableReader.open(input), "length=" + length);
            assertEquals(1, input.closeCalls);
            assertArrayEquals(truncated, Files.readAllBytes(path));
        }
    }

    @Test
    void rejectsCorruptMetadataIncludingRecomputedChecksumsWithoutChangingFiles() throws IOException {
        Path path = sampleTable();
        byte[] original = Files.readAllBytes(path);
        int footerStart = original.length - 24;
        Footer footer = footer(original);
        int indexStart = (int) footer.indexOffset();
        List<byte[]> invalidFiles = new ArrayList<>();
        for (int position : new int[]{0, 7, footerStart + 20, indexStart + 4}) {
            byte[] bytes = original.clone();
            bytes[position] ^= 1;
            invalidFiles.add(bytes);
        }
        for (int[] field : new int[][]{{12, 2}, {16, 0}, {8, SsTableMetadataCodec.MAX_INDEX_FRAME_SIZE + 1}}) {
            byte[] bytes = original.clone();
            ByteBuffer.wrap(bytes).putInt(footerStart + field[0], field[1]);
            updateFooterChecksum(bytes);
            invalidFiles.add(bytes);
        }
        for (int[] field : new int[][]{{0, Integer.MAX_VALUE}, {8, Integer.MAX_VALUE}, {12, -1}}) {
            byte[] bytes = original.clone();
            ByteBuffer.wrap(bytes).putInt(indexStart + field[0], field[1]);
            updateFrameChecksum(bytes, indexStart, footer.indexLength());
            invalidFiles.add(bytes);
        }
        byte[] badOffset = original.clone();
        ByteBuffer.wrap(badOffset).putLong(indexStart + 17, 9);
        updateFrameChecksum(badOffset, indexStart, footer.indexLength());
        invalidFiles.add(badOffset);
        for (byte[] bytes : invalidFiles) {
            Files.write(path, bytes);
            RecordingInput input = new RecordingInput(path);
            assertThrows(IOException.class, () -> SsTableReader.open(input));
            assertEquals(1, input.closeCalls);
            assertArrayEquals(bytes, Files.readAllBytes(path));
        }
    }

    @Test
    void rejectsDuplicateIndexKeysBeforeReadingData() throws IOException {
        Path path = writeBlocks(List.of(List.of(deletion(new byte[]{1})),
                List.of(deletion(new byte[]{2}))), List.of());
        byte[] bytes = Files.readAllBytes(path);
        Footer footer = footer(bytes);
        bytes[(int) footer.indexOffset() + 33] = 1; // 第二个变长索引项的单字节 maxKey。
        updateFrameChecksum(bytes, (int) footer.indexOffset(), footer.indexLength());
        Files.write(path, bytes);
        RecordingInput input = new RecordingInput(path);
        assertThrows(IOException.class, () -> SsTableReader.open(input));
        assertEquals(1, input.closeCalls);
        assertTrue(input.readOffsets.stream().noneMatch(offset -> offset >= 8 && offset < footer.indexOffset()));
    }

    @Test
    void checksFooterFileBoundsBeforeReadingOrAllocatingTheClaimedIndex() throws IOException {
        Path path = sampleTable();
        byte[] bytes = Files.readAllBytes(path);
        byte[] footer = SsTableMetadataCodec.encodeFooter(new Footer(8, SsTableMetadataCodec.MAX_INDEX_FRAME_SIZE));
        System.arraycopy(footer, 0, bytes, bytes.length - 24, 24);
        Files.write(path, bytes);
        RecordingInput input = new RecordingInput(path);
        assertThrows(IOException.class, () -> SsTableReader.open(input));
        assertEquals(List.of(0L, 60L), input.readOffsets);
        assertEquals(1, input.closeCalls);
    }

    @Test
    void reportsSizeFailureAndPreservesCloseFailureOnOpen() throws IOException {
        RecordingInput input = new RecordingInput(sampleTable());
        input.failSize = true;
        input.failClose = true;
        IOException error = assertThrows(IOException.class, () -> SsTableReader.open(input));
        assertSame(input.failure, error);
        assertArrayEquals(new Throwable[]{input.closeFailure}, error.getSuppressed());
        assertTrue(input.readOffsets.isEmpty());
        assertEquals(1, input.closeCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"error", "zero", "eof"})
    @Timeout(3)
    void releasesResourcesOnMetadataReadFailure(String mode) throws IOException {
        Path path = sampleTable();
        for (long position : new long[]{0, 31, 60}) {
            RecordingInput input = new RecordingInput(path);
            input.failureOffset = position;
            input.failureMode = mode;
            assertThrows(IOException.class, () -> SsTableReader.open(input));
            assertEquals(1, input.closeCalls);
        }
    }

    @Test
    void retainsPrimaryReadFailureWhenClosingOnOpenAlsoFails() throws IOException {
        RecordingInput input = new RecordingInput(sampleTable());
        input.failureOffset = 0;
        input.failClose = true;
        IOException error = assertThrows(IOException.class, () -> SsTableReader.open(input));
        assertSame(input.failure, error);
        assertArrayEquals(new Throwable[]{input.closeFailure}, error.getSuppressed());
        assertEquals(1, input.closeCalls);
    }

    @Test
    void keepsUnrelatedCorruptBlockLazyThenStopsAfterAccessingIt() throws IOException {
        Path path = threeBlockTable();
        byte[] bytes = Files.readAllBytes(path);
        List<IndexEntry> entries = index(bytes);
        bytes[(int) entries.get(1).offset() + 4] ^= 1;
        Files.write(path, bytes);
        try (SsTableReader reader = SsTableReader.open(path)) {
            assertTrue(reader.get(key(new byte[]{1})).isPresent());
            assertTrue(reader.get(key(new byte[]{3})).isPresent());
            assertEquals(Optional.empty(), reader.get(key(new byte[]{4})));
            assertThrows(IOException.class, () -> reader.get(key(new byte[]{2})));
            assertThrows(IllegalStateException.class, () -> reader.get(key(new byte[]{1})));
        }
        assertArrayEquals(bytes, Files.readAllBytes(path));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validatesWholeBlockBeforeReturningAnEarlyMatchingKey(boolean corruptOrder) throws IOException {
        Path path = writeBlocks(List.of(List.of(deletion(new byte[]{0x61}), deletion(new byte[]{0x62}))), List.of());
        byte[] bytes = Files.readAllBytes(path);
        IndexEntry entry = index(bytes).getFirst();
        if (corruptOrder) {
            bytes[(int) entry.offset() + 31] = 0x61; // 第二条键重复。
        } else {
            bytes[(int) entry.offset() + 22] = 3; // 第二条 kind 非法。
        }
        updateFrameChecksum(bytes, (int) entry.offset(), entry.frameLength());
        Files.write(path, bytes);
        try (SsTableReader reader = SsTableReader.open(path)) {
            assertThrows(IOException.class, () -> reader.get(key(new byte[]{0x61})));
            assertThrows(IllegalStateException.class, () -> reader.get(key(new byte[]{0x61})));
        }
    }

    @Test
    void rejectsMismatchBetweenIndexLengthAndEmbeddedFrameLength() throws IOException {
        Path path = sampleTable();
        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.putInt(8, buffer.getInt(8) + 1);
        Files.write(path, bytes);
        try (SsTableReader reader = SsTableReader.open(path)) {
            assertThrows(IOException.class, () -> reader.get(SAMPLE.key()));
        }
        assertArrayEquals(bytes, Files.readAllBytes(path));
    }

    @Test
    void checksActualMaximumKeyAgainstIndexBeforeReturning() throws IOException {
        Path path = writeBlocks(List.of(List.of(deletion(new byte[]{0x61}), deletion(new byte[]{0x63}))),
                List.of(key(new byte[]{0x62})));
        try (SsTableReader reader = SsTableReader.open(path)) {
            assertThrows(IOException.class, () -> reader.get(key(new byte[]{0x61})));
        }
    }

    @Test
    void rejectsAValidBlockOverlappingThePreviousIndexedKeyRange() throws IOException {
        Path path = writeBlocks(List.of(
                List.of(deletion(new byte[]{0x62}), deletion(new byte[]{0x64})),
                List.of(deletion(new byte[]{0x63}), deletion(new byte[]{0x66}))), List.of());
        try (SsTableReader reader = SsTableReader.open(path)) {
            assertEquals(Optional.of(Tombstone.INSTANCE), reader.get(key(new byte[]{0x62})));
            assertThrows(IOException.class, () -> reader.get(key(new byte[]{0x66})));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"error", "zero", "eof"})
    @Timeout(3)
    void stopsServingAfterDataReadFailureButStillAllowsClose(String mode) throws IOException {
        RecordingInput input = new RecordingInput(sampleTable());
        SsTableReader reader = SsTableReader.open(input);
        input.failureOffset = 8;
        input.failureMode = mode;
        assertThrows(IOException.class, () -> reader.get(SAMPLE.key()));
        int readCalls = input.readOffsets.size();
        assertThrows(IllegalStateException.class, () -> reader.get(SAMPLE.key()));
        assertEquals(readCalls, input.readOffsets.size());
        reader.close();
        reader.close();
        assertEquals(1, input.closeCalls);
    }

    @Test
    void failsOnActualEofAfterOpenWithoutModifyingTheTruncatedFile() throws IOException {
        Path path = sampleTable();
        try (SsTableReader reader = SsTableReader.open(path)) {
            // 模拟底层文件在读时失去字节；正常使用契约禁止外部修改文件。
            try (FileChannel channel = FileChannel.open(path, WRITE)) {
                channel.truncate(8);
            }
            byte[] truncated = Files.readAllBytes(path);
            assertThrows(IOException.class, () -> reader.get(SAMPLE.key()));
            assertThrows(IllegalStateException.class, () -> reader.get(SAMPLE.key()));
            assertArrayEquals(truncated, Files.readAllBytes(path));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closesAtMostOnceAndRefusesQueriesEvenWhenCloseFails(boolean failClose) throws IOException {
        RecordingInput input = new RecordingInput(sampleTable());
        SsTableReader reader = SsTableReader.open(input);
        input.failClose = failClose;
        if (failClose) {
            assertSame(input.closeFailure, assertThrows(IOException.class, reader::close));
        } else {
            reader.close();
        }
        assertThrows(IllegalStateException.class, () -> reader.get(SAMPLE.key()));
        assertThrows(IllegalStateException.class, () -> reader.get(null));
        reader.close();
        assertEquals(1, input.closeCalls);
    }

    @Test
    void scansAnEmptyFileWithoutCallbacksOrDataReads() throws IOException {
        Path path = directory.resolve("empty-scan.sst");
        SsTableWriter.write(path, List.of());
        RecordingInput input = new RecordingInput(path);
        try (SsTableReader reader = SsTableReader.open(input)) {
            input.clearReads();
            List<SsTableRecord> records = new ArrayList<>();
            reader.scan(records::add);
            assertTrue(records.isEmpty());
            assertTrue(input.readOffsets.isEmpty());
        }
    }

    @Test
    void scansEveryBinaryRecordAndTombstoneInOrderAcrossBlocks() throws IOException {
        List<SsTableRecord> records = List.of(value(new byte[0], new byte[0]),
                deletion(new byte[]{0}), value(new byte[]{0, 1}, new byte[3000]),
                value(new byte[]{0, (byte) 0xff}, new byte[3000]), deletion(new byte[]{0x7f}),
                value(new byte[]{(byte) 0x80}, new byte[0]), deletion(new byte[]{(byte) 0xff}));
        Path path = directory.resolve("ordered-scan.sst");
        SsTableWriter.write(path, records);
        byte[] original = Files.readAllBytes(path);
        assertTrue(index(original).size() > 1);
        try (SsTableReader reader = SsTableReader.open(path)) {
            List<SsTableRecord> first = new ArrayList<>();
            reader.scan(first::add);
            assertEquals(records, first);
            List<SsTableRecord> second = new ArrayList<>();
            reader.scan(second::add);
            assertEquals(records, second);
            for (SsTableRecord record : records) {
                assertEquals(Optional.of(record.entry()), reader.get(record.key()));
            }
        }
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void propagatesCallbackFailureWithoutInvalidatingOrPrefetchingLaterBlocks(boolean throwError) throws IOException {
        Path path = threeBlockTable();
        List<IndexEntry> entries = index(Files.readAllBytes(path));
        RecordingInput input = new RecordingInput(path);
        Throwable failure = throwError ? new AssertionError("callback failed")
                : new IllegalArgumentException("callback failed");
        try (SsTableReader reader = SsTableReader.open(input)) {
            input.clearReads();
            List<SsTableRecord> produced = new ArrayList<>();
            assertSame(failure, assertThrows(failure.getClass(), () -> reader.scan(record -> {
                produced.add(record);
                assertEquals(key(new byte[]{1}), record.key());
                assertTrue(input.readOffsets.stream().allMatch(offset -> offset >= entries.getFirst().offset()
                        && offset < entries.get(1).offset()));
                if (failure instanceof Error error) {
                    throw error;
                }
                throw (RuntimeException) failure;
            })));
            assertEquals(1, produced.size()); // 已产生的回调副作用不回滚。
            assertEquals(entries.getFirst().frameLength(), input.bytesRead);
            assertTrue(reader.get(key(new byte[]{3})).isPresent());
            List<SsTableRecord> retried = new ArrayList<>();
            reader.scan(retried::add);
            assertEquals(List.of(key(new byte[]{1}), key(new byte[]{2}), key(new byte[]{3})),
                    retried.stream().map(SsTableRecord::key).toList());
        }
    }

    @Test
    void rejectsReentryAndCloseDuringCallbacksWithoutChangingReaderState() throws IOException {
        RecordingInput input = new RecordingInput(sampleTable());
        try (SsTableReader reader = SsTableReader.open(input)) {
            List<SsTableRecord> produced = new ArrayList<>();
            reader.scan(record -> {
                assertThrows(IllegalStateException.class, () -> reader.get(record.key()));
                assertThrows(IllegalStateException.class, () -> reader.scan(ignored -> {}));
                assertThrows(IllegalStateException.class, reader::openCursor);
                assertThrows(IllegalStateException.class, reader::close);
                assertEquals(0, input.closeCalls);
                produced.add(record);
            });
            assertEquals(List.of(SAMPLE), produced);
            assertEquals(Optional.of(SAMPLE.entry()), reader.get(SAMPLE.key()));
        }
        assertEquals(1, input.closeCalls);
    }

    @Test
    void rejectsNullCallbackWithoutInvalidatingReader() throws IOException {
        try (SsTableReader reader = SsTableReader.open(sampleTable())) {
            assertThrows(NullPointerException.class, () -> reader.scan(null));
            assertEquals(Optional.of(SAMPLE.entry()), reader.get(SAMPLE.key()));
            List<SsTableRecord> records = new ArrayList<>();
            reader.scan(records::add);
            assertEquals(List.of(SAMPLE), records);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void emitsOnlyEarlierValidBlocksWhenScanFindsCorruption(int brokenBlock) throws IOException {
        Path path = threeBlockTable();
        byte[] bytes = Files.readAllBytes(path);
        List<IndexEntry> entries = index(bytes);
        bytes[(int) entries.get(brokenBlock).offset() + 4] ^= 1;
        Files.write(path, bytes);
        try (SsTableReader reader = SsTableReader.open(path)) {
            List<SsTableRecord> records = new ArrayList<>();
            assertThrows(IOException.class, () -> reader.scan(records::add));
            assertEquals(brokenBlock, records.size());
            for (int i = 0; i < records.size(); i++) {
                assertEquals(key(new byte[]{(byte) (i + 1)}), records.get(i).key());
            }
            assertThrows(IllegalStateException.class, () -> reader.get(key(new byte[]{1})));
            assertThrows(IllegalStateException.class, () -> reader.scan(ignored -> {}));
        }
        assertArrayEquals(bytes, Files.readAllBytes(path));
    }

    @Test
    void doesNotEmitPartOfAMalformedBlockWithValidChecksum() throws IOException {
        Path path = writeBlocks(List.of(List.of(deletion(new byte[]{0x61})),
                List.of(deletion(new byte[]{0x62}), deletion(new byte[]{0x63}))), List.of());
        byte[] bytes = Files.readAllBytes(path);
        IndexEntry second = index(bytes).get(1);
        bytes[(int) second.offset() + 22] = 3; // 坏块的第二条 kind 非法，第一条本身仍合法。
        updateFrameChecksum(bytes, (int) second.offset(), second.frameLength());
        Files.write(path, bytes);
        try (SsTableReader reader = SsTableReader.open(path)) {
            List<SsTableRecord> records = new ArrayList<>();
            assertThrows(IOException.class, () -> reader.scan(records::add));
            assertEquals(List.of(deletion(new byte[]{0x61})), records);
        }
    }

    @Test
    void validatesCrossBlockOrderingBeforeCallingConsumer() throws IOException {
        List<SsTableRecord> first = List.of(deletion(new byte[]{0x62}), deletion(new byte[]{0x64}));
        Path path = writeBlocks(List.of(first,
                List.of(deletion(new byte[]{0x63}), deletion(new byte[]{0x66}))), List.of());
        try (SsTableReader reader = SsTableReader.open(path)) {
            List<SsTableRecord> records = new ArrayList<>();
            assertThrows(IOException.class, () -> reader.scan(records::add));
            assertEquals(first, records);
        }
    }

    @Test
    void validatesIndexedMaximumBeforeCallingConsumer() throws IOException {
        List<SsTableRecord> first = List.of(deletion(new byte[]{0x61}));
        Path path = writeBlocks(List.of(first,
                List.of(deletion(new byte[]{0x63}), deletion(new byte[]{0x65}))),
                List.of(key(new byte[]{0x61}), key(new byte[]{0x64})));
        try (SsTableReader reader = SsTableReader.open(path)) {
            List<SsTableRecord> records = new ArrayList<>();
            assertThrows(IOException.class, () -> reader.scan(records::add));
            assertEquals(first, records);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "error", "zero", "eof"})
    @Timeout(3)
    void handlesShortScanReadsAndLaterReadFailures(String mode) throws IOException {
        List<SsTableRecord> first = List.of(deletion(new byte[]{1}));
        List<SsTableRecord> second = List.of(deletion(new byte[]{2}));
        Path path = writeBlocks(List.of(first, second), List.of());
        long secondOffset = index(Files.readAllBytes(path)).get(1).offset();
        RecordingInput input = new RecordingInput(path);
        try (SsTableReader reader = SsTableReader.open(input)) {
            input.maxRead = 2;
            if (!mode.equals("none")) {
                input.failureOffset = secondOffset;
                input.failureMode = mode;
            }
            input.clearReads();
            List<SsTableRecord> records = new ArrayList<>();
            if (mode.equals("none")) {
                reader.scan(records::add);
                assertEquals(List.of(first.getFirst(), second.getFirst()), records);
            } else {
                assertThrows(IOException.class, () -> reader.scan(records::add));
                assertEquals(first, records);
                assertThrows(IllegalStateException.class, () -> reader.scan(ignored -> {}));
            }
            assertTrue(input.readOffsets.size() > 2);
        }
        assertEquals(1, input.closeCalls);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsScanAfterCloseOrFailureWithoutCallbacks(boolean failed) throws IOException {
        RecordingInput input = new RecordingInput(sampleTable());
        SsTableReader reader = SsTableReader.open(input);
        if (failed) {
            input.failureOffset = 8;
            assertThrows(IOException.class, () -> reader.get(SAMPLE.key()));
        } else {
            reader.close();
        }
        int[] callbacks = {0};
        assertThrows(IllegalStateException.class, () -> reader.scan(ignored -> callbacks[0]++));
        assertEquals(0, callbacks[0]);
        reader.close();
        assertEquals(1, input.closeCalls);
    }

    @Test
    void emptyCursorHasNoPositionAndDoesNotReadDataEvenAfterRepeatedExhaustion() throws IOException {
        Path path = directory.resolve("empty-cursor.sst");
        SsTableWriter.write(path, List.of());
        RecordingInput input = new RecordingInput(path);
        try (SsTableReader reader = SsTableReader.open(input)) {
            input.clearReads();
            try (SsTableReader.Cursor cursor = reader.openCursor()) {
                assertThrows(IllegalStateException.class, cursor::record);
                assertFalse(cursor.next());
                assertFalse(cursor.next());
                assertThrows(IllegalStateException.class, cursor::record);
                // 即使空表已耗尽，仍须显式关闭游标后才能进行其他读操作。
                assertThrows(IllegalStateException.class, reader::openCursor);
                assertThrows(IllegalStateException.class, () -> reader.get(SAMPLE.key()));
            }
            assertEquals(Optional.empty(), reader.get(SAMPLE.key()));
            assertTrue(input.readOffsets.isEmpty());
        }
        assertEquals(1, input.closeCalls);
    }

    @Test
    void cursorLoadsOnlyTheNeededBlockAndRepeatedRecordDoesNotAdvance() throws IOException {
        SsTableRecord a = deletion(new byte[]{1});
        SsTableRecord b = deletion(new byte[]{2});
        SsTableRecord c = deletion(new byte[]{3});
        Path path = writeBlocks(List.of(List.of(a, b), List.of(c)), List.of());
        List<IndexEntry> entries = index(Files.readAllBytes(path));
        RecordingInput input = new RecordingInput(path);
        try (SsTableReader reader = SsTableReader.open(input)) {
            input.clearReads();
            try (SsTableReader.Cursor cursor = reader.openCursor()) {
                assertTrue(input.readOffsets.isEmpty());
                assertThrows(IllegalStateException.class, cursor::record);
                assertTrue(cursor.next());
                assertEquals(a, cursor.record());
                assertSame(cursor.record(), cursor.record());
                assertEquals(entries.getFirst().frameLength(), input.bytesRead);
                int callsAfterFirstBlock = input.readOffsets.size();
                assertTrue(cursor.next());
                assertEquals(b, cursor.record());
                assertEquals(callsAfterFirstBlock, input.readOffsets.size());
                assertTrue(cursor.next());
                assertEquals(c, cursor.record());
                assertEquals(entries.stream().mapToInt(IndexEntry::frameLength).sum(), input.bytesRead);
                assertFalse(cursor.next());
                assertThrows(IllegalStateException.class, cursor::record);
                int callsAfterLastBlock = input.readOffsets.size();
                assertFalse(cursor.next());
                assertEquals(callsAfterLastBlock, input.readOffsets.size());
            }
        }
    }

    @Test
    void cursorPreservesBinaryRecordsAndTombstonesAcrossBlocks() throws IOException {
        List<SsTableRecord> records = List.of(value(new byte[0], new byte[0]),
                deletion(new byte[]{0}), value(new byte[]{0, 1}, new byte[5000]),
                value(new byte[]{0, (byte) 0xff}, new byte[5000]), deletion(new byte[]{0x7f}),
                value(new byte[]{(byte) 0x80}, new byte[0]), deletion(new byte[]{(byte) 0xff}));
        Path path = directory.resolve("binary-cursor.sst");
        SsTableWriter.write(path, records);
        byte[] original = Files.readAllBytes(path);
        assertTrue(index(original).size() > 1);
        try (SsTableReader reader = SsTableReader.open(path);
             SsTableReader.Cursor cursor = reader.openCursor()) {
            List<SsTableRecord> actual = new ArrayList<>();
            while (cursor.next()) {
                actual.add(cursor.record());
            }
            assertEquals(records, actual);
        }
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test
    void independentReadersCanPauseAndAlternateCursorAdvancement() throws IOException {
        Path path = threeBlockTable();
        try (SsTableReader first = SsTableReader.open(path);
             SsTableReader second = SsTableReader.open(path);
             SsTableReader.Cursor left = first.openCursor();
             SsTableReader.Cursor right = second.openCursor()) {
            assertTrue(left.next());
            assertTrue(right.next());
            assertEquals(left.record(), right.record());
            assertTrue(right.next());
            assertEquals(key(new byte[]{1}), left.record().key());
            assertEquals(key(new byte[]{2}), right.record().key());
            assertTrue(left.next());
            assertEquals(left.record(), right.record());
            assertTrue(left.next());
            assertFalse(left.next());
            assertTrue(right.next());
            assertEquals(key(new byte[]{3}), right.record().key());
            assertFalse(right.next());
        }
    }

    @Test
    void earlyCursorCloseRestoresReaderAndAnOldCloseCannotReleaseANewCursor() throws IOException {
        RecordingInput input = new RecordingInput(threeBlockTable());
        try (SsTableReader reader = SsTableReader.open(input)) {
            SsTableReader.Cursor first = reader.openCursor();
            assertTrue(first.next());
            assertThrows(IllegalStateException.class, () -> reader.get(key(new byte[]{1})));
            assertThrows(IllegalStateException.class, () -> reader.scan(ignored -> {}));
            assertThrows(IllegalStateException.class, reader::openCursor);
            assertEquals(key(new byte[]{1}), first.record().key());
            first.close();
            assertEquals(0, input.closeCalls);
            assertThrows(IllegalStateException.class, first::next);
            assertThrows(IllegalStateException.class, first::record);
            assertTrue(reader.get(key(new byte[]{3})).isPresent());
            List<SsTableRecord> rescanned = new ArrayList<>();
            reader.scan(rescanned::add);
            assertEquals(3, rescanned.size());
            try (SsTableReader.Cursor second = reader.openCursor()) {
                first.close();
                assertThrows(IllegalStateException.class, reader::openCursor);
                assertTrue(second.next());
                assertEquals(key(new byte[]{1}), second.record().key());
            }
        }
        assertEquals(1, input.closeCalls);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void cursorReportsCorruptionWithoutReturningBadOrStaleRecords(int brokenBlock) throws IOException {
        Path path = threeBlockTable();
        byte[] bytes = Files.readAllBytes(path);
        List<IndexEntry> entries = index(bytes);
        bytes[(int) entries.get(brokenBlock).offset() + 4] ^= 1;
        Files.write(path, bytes);
        RecordingInput input = new RecordingInput(path);
        try (SsTableReader reader = SsTableReader.open(input);
             SsTableReader.Cursor cursor = reader.openCursor()) {
            for (int i = 0; i < brokenBlock; i++) {
                assertTrue(cursor.next());
                assertEquals(key(new byte[]{(byte) (i + 1)}), cursor.record().key());
            }
            assertThrows(IOException.class, cursor::next);
            int callsAfterFailure = input.readOffsets.size();
            assertThrows(IllegalStateException.class, cursor::record);
            assertThrows(IllegalStateException.class, cursor::next);
            assertThrows(IllegalStateException.class, () -> reader.get(key(new byte[]{1})));
            assertThrows(IllegalStateException.class, () -> reader.scan(ignored -> {}));
            assertThrows(IllegalStateException.class, reader::openCursor);
            assertEquals(callsAfterFailure, input.readOffsets.size());
        }
        assertEquals(1, input.closeCalls);
        assertArrayEquals(bytes, Files.readAllBytes(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"maximum", "overlap", "malformed"})
    void cursorValidatesTheWholeBlockAndItsIndexedBoundariesBeforeEmission(String mode) throws IOException {
        List<SsTableRecord> first = List.of(deletion(new byte[]{1}));
        List<SsTableRecord> second = mode.equals("overlap")
                ? List.of(deletion(new byte[]{1}), deletion(new byte[]{3}))
                : List.of(deletion(new byte[]{2}), deletion(new byte[]{3}));
        List<ByteKey> overrides = mode.equals("maximum")
                ? List.of(key(new byte[]{1}), key(new byte[]{4})) : List.of();
        Path path = writeBlocks(List.of(first, second), overrides);
        if (mode.equals("malformed")) {
            byte[] bytes = Files.readAllBytes(path);
            IndexEntry block = index(bytes).get(1);
            bytes[(int) block.offset() + 22] = 3; // 第二条 kind 非法；重算 CRC 以验证内容检查。
            updateFrameChecksum(bytes, (int) block.offset(), block.frameLength());
            Files.write(path, bytes);
        }
        try (SsTableReader reader = SsTableReader.open(path);
             SsTableReader.Cursor cursor = reader.openCursor()) {
            assertTrue(cursor.next());
            assertEquals(first.getFirst(), cursor.record());
            assertThrows(IOException.class, cursor::next);
            assertThrows(IllegalStateException.class, cursor::record);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "error", "zero", "eof"})
    @Timeout(3)
    void cursorHandlesShortReadsAndExplicitIoFailures(String mode) throws IOException {
        Path path = threeBlockTable();
        long secondOffset = index(Files.readAllBytes(path)).get(1).offset();
        RecordingInput input = new RecordingInput(path);
        try (SsTableReader reader = SsTableReader.open(input);
             SsTableReader.Cursor cursor = reader.openCursor()) {
            input.maxRead = 17;
            input.clearReads();
            assertTrue(cursor.next());
            assertEquals(key(new byte[]{1}), cursor.record().key());
            if (mode.equals("none")) {
                assertTrue(cursor.next());
                assertTrue(cursor.next());
                assertEquals(key(new byte[]{3}), cursor.record().key());
                assertFalse(cursor.next());
            } else {
                input.failureOffset = secondOffset;
                input.failureMode = mode;
                IOException failure = assertThrows(IOException.class, cursor::next);
                if (mode.equals("error")) {
                    assertSame(input.failure, failure);
                }
                assertThrows(IllegalStateException.class, cursor::next);
                assertThrows(IllegalStateException.class, cursor::record);
            }
            assertTrue(input.readOffsets.size() > 3);
        }
        assertEquals(1, input.closeCalls);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingReaderInvalidatesAnActiveCursorEvenWhenFileCloseFails(boolean failClose) throws IOException {
        RecordingInput input = new RecordingInput(sampleTable());
        SsTableReader reader = SsTableReader.open(input);
        SsTableReader.Cursor cursor = reader.openCursor();
        assertTrue(cursor.next());
        input.failClose = failClose;
        if (failClose) {
            assertSame(input.closeFailure, assertThrows(IOException.class, reader::close));
        } else {
            reader.close();
        }
        assertThrows(IllegalStateException.class, cursor::next);
        assertThrows(IllegalStateException.class, cursor::record);
        assertThrows(IllegalStateException.class, reader::openCursor);
        cursor.close();
        reader.close();
        assertEquals(1, input.closeCalls);
    }

    private Path sampleTable() throws IOException {
        Path path = directory.resolve("sample.sst");
        SsTableWriter.write(path, List.of(SAMPLE));
        return path;
    }

    private Path threeBlockTable() throws IOException {
        Path path = directory.resolve("three.sst");
        SsTableWriter.write(path, List.of(value(new byte[]{1}, new byte[5000]),
                value(new byte[]{2}, new byte[5000]), value(new byte[]{3}, new byte[5000])));
        return path;
    }

    /** 构造块各自合法、但块间或索引键可能矛盾的文件，用于检验 Reader 的交叉检查。 */
    private Path writeBlocks(List<List<SsTableRecord>> blocks, List<ByteKey> overrideKeys) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(SsTableMetadataCodec.encodeHeader());
        List<IndexEntry> entries = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            List<SsTableRecord> block = blocks.get(i);
            byte[] frame = SsTableBlockCodec.encode(block);
            ByteKey maxKey = overrideKeys.isEmpty() ? block.getLast().key() : overrideKeys.get(i);
            entries.add(new IndexEntry(maxKey, output.size(), frame.length));
            output.writeBytes(frame);
        }
        int indexOffset = output.size();
        byte[] index = SsTableMetadataCodec.encodeIndex(entries, indexOffset);
        output.writeBytes(index);
        output.writeBytes(SsTableMetadataCodec.encodeFooter(new Footer(indexOffset, index.length)));
        Path path = directory.resolve("blocks.sst");
        Files.write(path, output.toByteArray());
        return path;
    }

    private static ByteKey key(byte[] bytes) {
        return ByteKey.copyOf(bytes);
    }

    private static ByteKey integerKey(int value) {
        return key(ByteBuffer.allocate(4).putInt(value).array());
    }

    private static SsTableRecord value(byte[] key, byte[] value) {
        return new SsTableRecord(key(key), Value.copyOf(value));
    }

    private static SsTableRecord deletion(byte[] key) {
        return new SsTableRecord(key(key), Tombstone.INSTANCE);
    }

    private static Footer footer(byte[] bytes) throws IOException {
        return SsTableMetadataCodec.decodeFooter(Arrays.copyOfRange(bytes, bytes.length - 24, bytes.length), bytes.length);
    }

    private static List<IndexEntry> index(byte[] bytes) throws IOException {
        Footer footer = footer(bytes);
        return SsTableMetadataCodec.decodeIndex(Arrays.copyOfRange(bytes, (int) footer.indexOffset(),
                (int) footer.indexOffset() + footer.indexLength()), footer.indexOffset());
    }

    private static void updateFrameChecksum(byte[] bytes, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, offset, 4);
        crc.update(bytes, offset + 8, length - 8);
        ByteBuffer.wrap(bytes).putInt(offset + 4, (int) crc.getValue());
    }

    private static void updateFooterChecksum(byte[] bytes) {
        int offset = bytes.length - 24;
        CRC32C crc = new CRC32C();
        crc.update(bytes, offset, 20);
        ByteBuffer.wrap(bytes).putInt(offset + 20, (int) crc.getValue());
    }

    /** 代理真实只读通道，记录访问区域并注入短读、EOF、无进展或异常。 */
    private static final class RecordingInput implements SsTableReader.ReadIo {
        private final FileChannel channel;
        private final IOException failure = new IOException("read failed");
        private final IOException closeFailure = new IOException("close failed");
        private final List<Long> readOffsets = new ArrayList<>();
        private int bytesRead;
        private int maxRead = Integer.MAX_VALUE;
        private long failureOffset = -1;
        private String failureMode = "error";
        private boolean failSize;
        private boolean failClose;
        private int closeCalls;

        private RecordingInput(Path path) throws IOException {
            channel = FileChannel.open(path, READ);
        }

        private void clearReads() {
            readOffsets.clear();
            bytesRead = 0;
        }

        @Override
        public long size() throws IOException {
            if (failSize) {
                throw failure;
            }
            return channel.size();
        }

        @Override
        public int read(ByteBuffer target, long offset) throws IOException {
            readOffsets.add(offset);
            if (offset == failureOffset) {
                return switch (failureMode) {
                    case "zero" -> 0;
                    case "eof" -> -1;
                    default -> throw failure;
                };
            }
            int previousLimit = target.limit();
            target.limit(target.position() + Math.min(target.remaining(), maxRead));
            try {
                int count = channel.read(target, offset);
                if (count > 0) {
                    bytesRead += count;
                }
                return count;
            } finally {
                target.limit(previousLimit);
            }
        }

        @Override
        public void close() throws IOException {
            closeCalls++;
            channel.close();
            if (failClose) {
                throw closeFailure;
            }
        }
    }
}
