package io.github.flyzz.mylsm.sstable;

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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsTableWriterTest {
    private static final SsTableRecord SAMPLE = value(new byte[]{0x6b}, new byte[]{0x41});
    // 完整 k -> A 文件的 CRC 由独立逐位算法计算，校验 Writer 的最终布局。
    private static final byte[] SAMPLE_FILE = HexFormat.of().parseHex(
            "4d595354000000010000000f18b7c1bd000000010100000001000000016b41"
                    + "0000001565f896e400000001000000016b000000000000000800000017"
                    + "000000000000001f0000001d000000014d594654a03c6158");

    @TempDir
    Path directory;

    @Test
    void writesAnEmptyTableWithExactlyFortyFourBytes() throws IOException {
        Path path = directory.resolve("empty.sst");
        SsTableWriter.write(path, List.of());
        byte[] bytes = Files.readAllBytes(path);
        assertEquals(44, bytes.length);
        assertEquals(new Footer(8, 12), footer(bytes));
        assertEquals(List.of(), readRecords(path));
    }

    @Test
    void roundTripsBinaryRecordsEmptyValuesAndTombstones() throws IOException {
        List<SsTableRecord> records = List.of(value(new byte[0], new byte[0]),
                deletion(new byte[]{0}), value(new byte[]{0, 1}, new byte[]{(byte) 0xff, 0}),
                deletion(new byte[]{0x7f}), value(new byte[]{(byte) 0x80}, new byte[0]),
                deletion(new byte[]{(byte) 0xff}));
        Path path = directory.resolve("binary.sst");
        SsTableWriter.write(path, records);
        assertEquals(records, readRecords(path));
    }

    @Test
    void writesMultipleBlocksAndTheirActualMaximumKeys() throws IOException {
        List<SsTableRecord> records = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            records.add(value(new byte[]{(byte) i}, new byte[1400]));
        }
        Path path = directory.resolve("multiple.sst");
        SsTableWriter.write(path, records);
        byte[] bytes = Files.readAllBytes(path);
        List<IndexEntry> entries = index(bytes);
        assertEquals(5, entries.size());
        for (int i = 0; i < entries.size(); i++) {
            assertEquals(records.get(2 * i + 1).key(), entries.get(i).maxKey());
            assertEquals(8 + 4 + 2 * (9 + 1 + 1400), entries.get(i).frameLength());
        }
        assertEquals(records, readRecords(path));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void distinguishesExactlyAtTargetFromOneByteOver(int extraByte) throws IOException {
        // 两条记录各 2046 字节，加上 4 字节计数恰好 4096。
        List<SsTableRecord> records = List.of(value(new byte[]{0}, new byte[2036]),
                value(new byte[]{1}, new byte[2036 + extraByte]));
        Path path = directory.resolve("boundary.sst");
        SsTableWriter.write(path, records);
        List<IndexEntry> entries = index(Files.readAllBytes(path));
        assertEquals(extraByte == 0 ? 1 : 2, entries.size());
        if (extraByte == 0) {
            assertEquals(4096 + 8, entries.getFirst().frameLength());
        }
        assertEquals(records, readRecords(path));
    }

    @Test
    void keepsAnOversizedRecordWholeInItsOwnBlock() throws IOException {
        List<SsTableRecord> records = List.of(value(new byte[]{0}, new byte[]{1}),
                value(new byte[]{1}, new byte[10 * 1024]), deletion(new byte[]{2}));
        Path path = directory.resolve("large.sst");
        SsTableWriter.write(path, records);
        List<IndexEntry> entries = index(Files.readAllBytes(path));
        assertEquals(3, entries.size());
        assertEquals(8 + 4 + 9 + 1 + 10 * 1024, entries.get(1).frameLength());
        assertEquals(records, readRecords(path));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesExistingEmptyAndNonEmptyTargets(boolean nonEmpty) throws IOException {
        Path path = directory.resolve("existing.sst");
        byte[] original = nonEmpty ? new byte[]{1, 2, 3} : new byte[0];
        Files.write(path, original);
        RecordingFiles files = new RecordingFiles();
        assertThrows(FileAlreadyExistsException.class, () -> SsTableWriter.write(path, List.of(SAMPLE), files));
        assertArrayEquals(original, Files.readAllBytes(path));
        assertEquals(0, files.deleteCalls);
        assertEquals(0, files.closeCalls);
    }

    @Test
    void rejectsNullArgumentsBeforeCreatingFilesAndDoesNotCreateParents() {
        Path path = directory.resolve("unused.sst");
        RecordingFiles files = new RecordingFiles();
        assertThrows(NullPointerException.class, () -> SsTableWriter.write(null, List.of(), files));
        assertThrows(NullPointerException.class, () -> SsTableWriter.write(path, null, files));
        assertThrows(NullPointerException.class, () -> SsTableWriter.write(path, List.of(), null));
        assertEquals(0, files.createCalls);
        assertFalse(Files.exists(path));
        Path missingParent = directory.resolve("missing");
        assertThrows(NoSuchFileException.class,
                () -> SsTableWriter.write(missingParent.resolve("new.sst"), List.of(SAMPLE)));
        assertFalse(Files.exists(missingParent));
        assertThrows(FileAlreadyExistsException.class, () -> SsTableWriter.write(directory, List.of()));
        assertTrue(Files.isDirectory(directory));
    }

    @Test
    void rejectsNullDuplicateAndDescendingRecordsAndDeletesCreatedTargets() {
        Path nullPath = directory.resolve("null-record.sst");
        assertThrows(NullPointerException.class,
                () -> SsTableWriter.write(nullPath, Arrays.asList(SAMPLE, null)));
        assertFalse(Files.exists(nullPath));
        List<List<SsTableRecord>> invalidInputs = List.of(
                List.of(SAMPLE, deletion(SAMPLE.key().toByteArray())),
                List.of(deletion(new byte[]{(byte) 0x80}), deletion(new byte[]{0x7f})));
        for (List<SsTableRecord> records : invalidInputs) {
            Path path = directory.resolve("invalid.sst");
            assertThrows(IllegalArgumentException.class, () -> SsTableWriter.write(path, records));
            assertFalse(Files.exists(path));
        }
    }

    @Test
    void validatesKeyOrderAcrossWouldBeBlockBoundaries() {
        SsTableRecord large = value(new byte[]{1}, new byte[5000]);
        for (byte nextKey : new byte[]{0, 1}) {
            Path path = directory.resolve("cross-block.sst");
            assertThrows(IllegalArgumentException.class, () -> SsTableWriter.write(path,
                    List.of(large, value(new byte[]{nextKey}, new byte[5000]))));
            assertFalse(Files.exists(path));
        }
    }

    @Test
    void rejectsOversizedValueAndDeletionKey() {
        byte[] tooLarge = new byte[SsTableBlockCodec.MAX_RECORD_SIZE
                - SsTableBlockCodec.RECORD_HEADER_SIZE + 1];
        for (SsTableRecord record : List.of(value(new byte[0], tooLarge), deletion(tooLarge))) {
            Path path = directory.resolve("oversized.sst");
            assertThrows(IllegalArgumentException.class, () -> SsTableWriter.write(path, List.of(record)));
            assertFalse(Files.exists(path));
        }
    }

    @Test
    void consumesAnIterableExactlyOnceAndWritesBlocksBeforeInputEnds() throws IOException {
        Path path = directory.resolve("streamed.sst");
        int[] iteratorCalls = {0};
        Iterable<SsTableRecord> records = () -> {
            assertEquals(0, iteratorCalls[0]++);
            return new Iterator<>() {
                private int position;

                @Override
                public boolean hasNext() {
                    return position < 3;
                }

                @Override
                public SsTableRecord next() {
                    if (position == 2) {
                        try {
                            assertTrue(Files.size(path) > 8); // 第一块已在接收第二条时写出。
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                    return value(new byte[]{(byte) position++}, new byte[5000]);
                }
            };
        };
        SsTableWriter.write(path, records);
        assertEquals(1, iteratorCalls[0]);
        assertEquals(3, readRecords(path).size());
    }

    @Test
    void handlesShortWritesAndSyncsTheWholeFileBeforeClosing() throws IOException {
        Path path = directory.resolve("short.sst");
        RecordingFiles files = new RecordingFiles();
        files.maxWrite = 3;
        SsTableWriter.write(path, List.of(SAMPLE), files);
        assertTrue(files.writeCalls > 4);
        assertEquals(1, files.forceCalls);
        assertEquals(1, files.closeCalls);
        assertEquals(0, files.deleteCalls);
        assertEquals(List.of("force", "close"), files.events.subList(files.events.size() - 2, files.events.size()));
        assertArrayEquals(Files.readAllBytes(path), files.bytesAtForce);
        assertArrayEquals(SAMPLE_FILE, files.bytesAtForce);
        assertEquals(84, files.bytesAtForce.length);
        assertEquals(List.of(SAMPLE), readRecords(path));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @Timeout(2)
    void rejectsWritesWithoutProgressAndCleansUp(int noProgress) {
        Path path = directory.resolve("stalled.sst");
        RecordingFiles files = new RecordingFiles();
        files.noProgress = noProgress;
        assertThrows(IOException.class, () -> SsTableWriter.write(path, List.of(SAMPLE), files));
        assertEquals(1, files.writeCalls);
        assertEquals(1, files.closeCalls);
        assertEquals(1, files.deleteCalls);
        assertEquals(0, files.forceCalls);
        assertFalse(Files.exists(path));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 8, 9, 31, 32, 60, 61, 83})
    void cleansUpWriteFailuresInEveryFileRegion(long offset) {
        Path path = directory.resolve("write-failure.sst");
        RecordingFiles files = new RecordingFiles();
        files.failWriteAt = offset;
        assertSame(files.writeFailure, assertThrows(IOException.class,
                () -> SsTableWriter.write(path, List.of(SAMPLE), files)));
        assertEquals(1, files.closeCalls);
        assertEquals(1, files.deleteCalls);
        assertEquals(0, files.forceCalls);
        assertFalse(Files.exists(path));
    }

    @Test
    void doesNotCleanUpWhenCreationFails() {
        Path path = directory.resolve("create-failure.sst");
        RecordingFiles files = new RecordingFiles();
        files.failCreate = true;
        assertSame(files.createFailure, assertThrows(IOException.class,
                () -> SsTableWriter.write(path, List.of(SAMPLE), files)));
        assertEquals(0, files.closeCalls);
        assertEquals(0, files.deleteCalls);
        assertFalse(Files.exists(path));
    }

    @Test
    void removesCompleteFileWhenSyncFails() {
        Path path = directory.resolve("sync-failure.sst");
        RecordingFiles files = new RecordingFiles();
        files.failForce = true;
        assertSame(files.forceFailure, assertThrows(IOException.class,
                () -> SsTableWriter.write(path, List.of(SAMPLE), files)));
        assertEquals(84, files.bytesAtForce.length);
        assertEquals(1, files.closeCalls);
        assertEquals(1, files.deleteCalls);
        assertFalse(Files.exists(path));
    }

    @Test
    void treatsCloseFailureAfterSyncAsConstructionFailure() {
        Path path = directory.resolve("close-failure.sst");
        RecordingFiles files = new RecordingFiles();
        files.failClose = true;
        assertSame(files.closeFailure, assertThrows(IOException.class,
                () -> SsTableWriter.write(path, List.of(SAMPLE), files)));
        assertEquals(1, files.forceCalls);
        assertEquals(1, files.closeCalls);
        assertEquals(1, files.deleteCalls);
        assertFalse(Files.exists(path));
    }

    @Test
    void preservesSyncFailureAndBothCleanupFailures() throws IOException {
        Path path = directory.resolve("cleanup-failures.sst");
        RecordingFiles files = new RecordingFiles();
        files.failForce = true;
        files.failClose = true;
        files.failDelete = true;
        IOException error = assertThrows(IOException.class, () -> SsTableWriter.write(path, List.of(SAMPLE), files));
        assertSame(files.forceFailure, error);
        assertArrayEquals(new Throwable[]{files.closeFailure, files.deleteFailure}, error.getSuppressed());
        assertEquals(List.of("force", "close", "delete"),
                files.events.subList(files.events.size() - 3, files.events.size()));
        assertTrue(Files.exists(path)); // 文件甚至完整，也不能把失败返回当作成功发布。
        assertEquals(List.of(SAMPLE), readRecords(path));
    }

    @Test
    void preservesIteratorFailureWhileClosingAndDeleting() {
        Path path = directory.resolve("iterator-failure.sst");
        RecordingFiles files = new RecordingFiles();
        files.failClose = true;
        files.failDelete = true;
        IllegalStateException failure = new IllegalStateException("input failed");
        Iterable<SsTableRecord> input = () -> { throw failure; };
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> SsTableWriter.write(path, input, files));
        assertSame(failure, error);
        assertArrayEquals(new Throwable[]{files.closeFailure, files.deleteFailure}, error.getSuppressed());
        assertEquals(0, files.forceCalls);
        assertEquals(1, files.closeCalls);
        assertEquals(1, files.deleteCalls);
    }

    @Test
    void cleansUpWhenIterationFailsAfterSomeDataWasWritten() {
        Path path = directory.resolve("partial-input.sst");
        RecordingFiles files = new RecordingFiles();
        AssertionError failure = new AssertionError("input error");
        Iterable<SsTableRecord> input = () -> new Iterator<>() {
            private int position;

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public SsTableRecord next() {
                if (position == 2) {
                    assertTrue(files.writeCalls > 1);
                    throw failure;
                }
                return value(new byte[]{(byte) position++}, new byte[5000]);
            }
        };
        assertSame(failure, assertThrows(AssertionError.class, () -> SsTableWriter.write(path, input, files)));
        assertEquals(1, files.closeCalls);
        assertEquals(1, files.deleteCalls);
        assertFalse(Files.exists(path));
    }

    @Test
    void stopsAndDeletesWhenAccumulatedIndexWouldExceedLimit() {
        Path path = directory.resolve("index-too-large.sst");
        RecordingFiles files = new RecordingFiles();
        Iterable<SsTableRecord> input = () -> new Iterator<>() {
            private int position;

            @Override
            public boolean hasNext() {
                return position < 4;
            }

            @Override
            public SsTableRecord next() {
                byte[] key = new byte[SsTableBlockCodec.MAX_RECORD_SIZE - SsTableBlockCodec.RECORD_HEADER_SIZE];
                key[0] = (byte) ++position;
                return deletion(key);
            }
        };
        assertThrows(IOException.class, () -> SsTableWriter.write(path, input, files));
        assertEquals(0, files.forceCalls);
        assertEquals(1, files.closeCalls);
        assertEquals(1, files.deleteCalls);
        assertFalse(Files.exists(path));
    }

    private static SsTableRecord value(byte[] key, byte[] value) {
        return new SsTableRecord(ByteKey.copyOf(key), Value.copyOf(value));
    }

    private static SsTableRecord deletion(byte[] key) {
        return new SsTableRecord(ByteKey.copyOf(key), Tombstone.INSTANCE);
    }

    private static Footer footer(byte[] bytes) throws IOException {
        SsTableMetadataCodec.decodeHeader(Arrays.copyOf(bytes, 8));
        return SsTableMetadataCodec.decodeFooter(Arrays.copyOfRange(bytes, bytes.length - 24, bytes.length), bytes.length);
    }

    private static List<IndexEntry> index(byte[] bytes) throws IOException {
        Footer footer = footer(bytes);
        return SsTableMetadataCodec.decodeIndex(Arrays.copyOfRange(bytes, (int) footer.indexOffset(),
                (int) footer.indexOffset() + footer.indexLength()), footer.indexOffset());
    }

    /** 测试用整文件检查器，组合已验证的 Codec，不提前引入生产 Reader。 */
    private static List<SsTableRecord> readRecords(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        List<SsTableRecord> records = new ArrayList<>();
        for (IndexEntry entry : index(bytes)) {
            List<SsTableRecord> block = SsTableBlockCodec.decode(Arrays.copyOfRange(bytes,
                    (int) entry.offset(), (int) entry.offset() + entry.frameLength()));
            assertEquals(entry.maxKey(), block.getLast().key());
            if (!records.isEmpty()) {
                assertTrue(records.getLast().key().compareTo(block.getFirst().key()) < 0);
            }
            records.addAll(block);
        }
        return records;
    }

    /** 代理真实临时文件；注入边界故障，不用纯内存假文件替代实际 I/O。 */
    private static final class RecordingFiles implements SsTableWriter.FileOps, SsTableWriter.WriteIo {
        private final IOException createFailure = new IOException("create failed");
        private final IOException writeFailure = new IOException("write failed");
        private final IOException forceFailure = new IOException("force failed");
        private final IOException closeFailure = new IOException("close failed");
        private final IOException deleteFailure = new IOException("delete failed");
        private final List<String> events = new ArrayList<>();
        private FileChannel channel;
        private Path path;
        private int createCalls;
        private int writeCalls;
        private int forceCalls;
        private int closeCalls;
        private int deleteCalls;
        private int maxWrite = Integer.MAX_VALUE;
        private Integer noProgress;
        private long failWriteAt = Long.MAX_VALUE;
        private boolean failCreate;
        private boolean failForce;
        private boolean failClose;
        private boolean failDelete;
        private byte[] bytesAtForce;

        @Override
        public SsTableWriter.WriteIo createNew(Path path) throws IOException {
            createCalls++;
            if (failCreate) {
                throw createFailure;
            }
            this.path = path;
            channel = FileChannel.open(path, CREATE_NEW, WRITE);
            return this;
        }

        @Override
        public int write(ByteBuffer source, long offset) throws IOException {
            writeCalls++;
            events.add("write");
            if (offset >= failWriteAt) {
                throw writeFailure;
            }
            if (noProgress != null) {
                return noProgress;
            }
            int previousLimit = source.limit();
            int count = (int) Math.min(Math.min(source.remaining(), maxWrite), failWriteAt - offset);
            source.limit(source.position() + count);
            try {
                return channel.write(source, offset);
            } finally {
                source.limit(previousLimit);
            }
        }

        @Override
        public void force() throws IOException {
            forceCalls++;
            events.add("force");
            bytesAtForce = Files.readAllBytes(path);
            if (failForce) {
                throw forceFailure;
            }
            channel.force(true);
        }

        @Override
        public void close() throws IOException {
            closeCalls++;
            events.add("close");
            channel.close();
            if (failClose) {
                throw closeFailure;
            }
        }

        @Override
        public void delete(Path path) throws IOException {
            deleteCalls++;
            events.add("delete");
            if (failDelete) {
                throw deleteFailure;
            }
            Files.deleteIfExists(path);
        }
    }
}
