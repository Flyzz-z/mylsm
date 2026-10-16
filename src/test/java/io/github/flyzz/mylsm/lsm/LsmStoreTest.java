package io.github.flyzz.mylsm.lsm;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Entry;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;
import io.github.flyzz.mylsm.sstable.SsTableReader;
import io.github.flyzz.mylsm.sstable.SsTableRecord;
import io.github.flyzz.mylsm.sstable.SsTableWriter;
import io.github.flyzz.mylsm.wal.WalCodec;
import io.github.flyzz.mylsm.wal.WalRecord;
import io.github.flyzz.mylsm.wal.WalWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LsmStoreTest {
    @TempDir
    Path directory;

    @Test
    void initializesAnExistingEmptyDirectoryAndReopensBinaryThreeStateData() throws IOException {
        ByteKey empty = ByteKey.copyOf(new byte[0]);
        ByteKey binary = ByteKey.copyOf(new byte[]{0, (byte) 0xff});
        Value binaryValue = Value.copyOf(new byte[]{(byte) 0x80, 0});
        try (LsmStore store = LsmStore.open(directory)) {
            assertEquals(0, store.activeBytes());
            assertEquals(Optional.empty(), store.get(key("missing")));
            store.put(empty, value(""));
            store.put(binary, binaryValue);
            store.put(key("k"), value("1"));
            store.delete(key("k"));
            store.put(key("k"), value("2"));
            store.delete(key("absent"));
        }
        assertTrue(Files.exists(directory.resolve("1.wal")));
        byte[] manifest = Files.readAllBytes(directory.resolve("MANIFEST"));
        byte[] wal = Files.readAllBytes(directory.resolve("1.wal"));
        try (LsmStore store = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("")), store.get(empty));
            assertEquals(Optional.of(binaryValue), store.get(binary));
            assertEquals(Optional.of(value("2")), store.get(key("k")));
            assertEquals(Optional.of(Tombstone.INSTANCE), store.get(key("absent")));
            assertArrayEquals(wal, Files.readAllBytes(directory.resolve("1.wal")));
            assertArrayEquals(manifest, Files.readAllBytes(directory.resolve("MANIFEST")));
        }
        try (var children = Files.list(directory)) {
            assertEquals(2, children.count()); // close 未强刷成 SSTable。
        }
    }

    @Test
    void tracksLatestLogicalBytesRatherThanWalHistory() throws IOException {
        try (LsmStore store = LsmStore.open(directory)) {
            store.put(key("k"), value("1"));
            assertEquals(11, store.activeBytes());
            store.put(key("k"), value("12345"));
            assertEquals(15, store.activeBytes());
            store.delete(key("k"));
            assertEquals(10, store.activeBytes());
            store.delete(key("k"));
            assertEquals(10, store.activeBytes());
            store.put(key(""), value(""));
            assertEquals(19, store.activeBytes());
            assertEquals(0, store.frozenBytes());
        }
        try (LsmStore store = LsmStore.open(directory)) {
            assertEquals(19, store.activeBytes());
        }
    }

    @Test
    void latestRecordWinsAcrossBothMemoryGenerationsAndTwoTables() throws IOException {
        seedTwoTablesAndFrozen();
        byte[] frozen = Files.readAllBytes(directory.resolve("4.wal"));
        byte[] manifest = Files.readAllBytes(directory.resolve("MANIFEST"));
        PausedFiles paused = new PausedFiles();
        try (LsmStore store = LsmStore.open(directory, paused); PausedFiles release = paused) {
            assertEquals(21, store.activeBytes());
            assertEquals(32, store.frozenBytes());
            assertEquals(Optional.of(value("1")), store.get(key("a")));
            assertEquals(Optional.of(value("2")), store.get(key("b")));
            assertEquals(Optional.of(Tombstone.INSTANCE), store.get(key("c")));
            assertEquals(Optional.of(Tombstone.INSTANCE), store.get(key("d")));
            assertEquals(Optional.of(value("4")), store.get(key("e")));
            assertEquals(Optional.of(Tombstone.INSTANCE), store.get(key("f")));
            assertEquals(Optional.empty(), store.get(key("z")));
            store.put(key("d"), value("5"));
            store.put(key("c"), value("5"));
            store.delete(key("b"));
            assertArrayEquals(frozen, Files.readAllBytes(directory.resolve("4.wal")));
            assertArrayEquals(manifest, Files.readAllBytes(directory.resolve("MANIFEST")));
        }
        try (LsmStore store = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("5")), store.get(key("d")));
            assertEquals(Optional.of(value("5")), store.get(key("c")));
            assertEquals(Optional.of(Tombstone.INSTANCE), store.get(key("b")));
        }
        assertFalse(Files.exists(directory.resolve("4.wal"))); // close 等待冻结代发布后回收旧 WAL。
        // 历史中的 1.wal、2.wal 已失效，不需要存在。
        assertFalse(Files.exists(directory.resolve("1.wal")));
        assertFalse(Files.exists(directory.resolve("2.wal")));
    }

    @Test
    void readsPublishedFilesAfterIgnoringAnObsoleteLogWithConflictingValues() throws IOException {
        seedTwoTablesAndFrozen();
        writeLog(directory.resolve("1.wal"), new WalRecord(key("a"), value("wrong")));
        try (LsmStore store = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("1")), store.get(key("a")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.wal", "4.wal", "3.sst", "5.sst", "MANIFEST"})
    void refusesMissingReferencesInsteadOfCreatingEmptyFiles(String missing) throws IOException {
        seedTwoTablesAndFrozen();
        Path orphan = directory.resolve("100.sst");
        Files.write(orphan, new byte[]{7});
        Path path = directory.resolve(missing);
        Files.delete(path);
        assertThrows(IOException.class, () -> LsmStore.open(directory));
        assertFalse(Files.exists(path));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(orphan));
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.wal", "4.wal", "3.sst", "MANIFEST"})
    void refusesAReferencedDirectory(String name) throws IOException {
        seedTwoTablesAndFrozen();
        Path path = directory.resolve(name);
        Files.delete(path);
        Files.createDirectory(path);
        assertThrows(IOException.class, () -> LsmStore.open(directory));
        assertTrue(Files.isDirectory(path));
    }

    @Test
    void initializationRequiresAnExistingEmptyDirectory() throws IOException {
        assertThrows(NullPointerException.class, () -> LsmStore.open(null));
        Path missing = directory.resolve("missing");
        assertThrows(IOException.class, () -> LsmStore.open(missing));
        assertFalse(Files.exists(missing));
        Path regular = directory.resolve("not-a-directory");
        Files.write(regular, new byte[]{7});
        assertThrows(IOException.class, () -> LsmStore.open(regular));
        assertThrows(IOException.class, () -> LsmStore.open(directory));
        assertFalse(Files.exists(directory.resolve("MANIFEST")));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(regular));
    }

    @Test
    void rejectsEveryIncompleteInitializationManifestWithoutOverwritingTheWal() throws IOException {
        Path path = directory.resolve("MANIFEST");
        byte[] wal = writeLog(directory.resolve("1.wal"), new WalRecord(key("k"), value("1")));
        Path orphan = directory.resolve("100.sst");
        Files.write(orphan, new byte[]{7});
        byte[] full = ByteBuffer.allocate(52).put(ManifestCodec.encodeHeader())
                .put(ManifestCodec.encode(new ManifestState(1, 1, 0, 0, List.of()))).array();
        for (int length = 0; length < full.length; length++) {
            byte[] bytes = Arrays.copyOf(full, length);
            Files.write(path, bytes);
            assertThrows(IOException.class, () -> LsmStore.open(directory));
            assertArrayEquals(bytes, Files.readAllBytes(path));
            assertArrayEquals(wal, Files.readAllBytes(directory.resolve("1.wal")));
            assertArrayEquals(new byte[]{7}, Files.readAllBytes(orphan));
        }
    }

    @Test
    void activeTailIsRepairedButFrozenLogIsUntouched() throws IOException {
        seedTwoTablesAndFrozen();
        Path path = directory.resolve("6.wal");
        byte[] active = Files.readAllBytes(path);
        byte[] frozen = Files.readAllBytes(directory.resolve("4.wal"));
        Files.write(path, Arrays.copyOf(active, active.length + 1));
        PausedFiles paused = new PausedFiles();
        try (LsmStore store = LsmStore.open(directory, paused); PausedFiles release = paused) {
            assertArrayEquals(active, Files.readAllBytes(path));
            assertEquals(Optional.of(value("4")), store.get(key("e")));
            store.put(key("g"), value("5"));
            assertArrayEquals(frozen, Files.readAllBytes(directory.resolve("4.wal")));
        }
        assertFalse(Files.exists(directory.resolve("4.wal")));
        try (LsmStore store = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("5")), store.get(key("g")));
        }
    }

    @Test
    void aReferencedZeroByteActiveLogIsNotReinitialized() throws IOException {
        seedTwoTablesAndFrozen();
        Path path = directory.resolve("6.wal");
        Files.write(path, new byte[0]);
        assertThrows(IOException.class, () -> LsmStore.open(directory));
        assertEquals(0, Files.size(path));
    }

    @Test
    void sealedLengthDetectsEveryFrozenTruncationIncludingRecordBoundaries() throws IOException {
        seedTwoTablesAndFrozen();
        Path path = directory.resolve("4.wal");
        byte[] original = Files.readAllBytes(path);
        for (int length = 0; length < original.length; length++) {
            byte[] bytes = Arrays.copyOf(original, length);
            Files.write(path, bytes);
            assertThrows(IOException.class, () -> LsmStore.open(directory));
            assertArrayEquals(bytes, Files.readAllBytes(path));
        }
    }

    @Test
    void corruptFrozenLogAndInvalidSstMetadataFailBeforeActiveRepair() throws IOException {
        seedTwoTablesAndFrozen();
        Path activePath = directory.resolve("6.wal");
        byte[] active = Files.readAllBytes(activePath);
        byte[] withTail = Arrays.copyOf(active, active.length + 1);
        Files.write(activePath, withTail);
        Path frozenPath = directory.resolve("4.wal");
        byte[] frozen = Files.readAllBytes(frozenPath);
        byte[] corrupt = frozen.clone();
        corrupt[12] ^= 1;
        Files.write(frozenPath, corrupt);
        assertThrows(IOException.class, () -> LsmStore.open(directory));
        assertArrayEquals(corrupt, Files.readAllBytes(frozenPath));
        assertArrayEquals(withTail, Files.readAllBytes(activePath));
        Files.write(frozenPath, frozen);
        Path tablePath = directory.resolve("3.sst");
        byte[] table = Files.readAllBytes(tablePath);
        table[table.length - 1] ^= 1;
        Files.write(tablePath, table);
        assertThrows(IOException.class, () -> LsmStore.open(directory));
        assertArrayEquals(table, Files.readAllBytes(tablePath));
        assertArrayEquals(withTail, Files.readAllBytes(activePath));
    }

    @Test
    void corruptAccessedDataBlockFailsTheWholeStoreInsteadOfReturningOlderData() throws IOException {
        seedTwoTablesAndFrozen();
        Path tablePath = directory.resolve("3.sst");
        byte[] table = Files.readAllBytes(tablePath);
        table[12] ^= 1; // 数据块 checksum；open 的元信息仍合法。
        Files.write(tablePath, table);
        try (LsmStore store = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("4")), store.get(key("e")));
            assertThrows(IOException.class, () -> store.get(key("a")));
            assertStopped(store);
        }
        assertArrayEquals(table, Files.readAllBytes(tablePath));
    }

    @Test
    void cleansOrphansButAllocatesPastAllCanonicalInternalNames() throws IOException {
        seedTwoTablesAndFrozen();
        Files.write(directory.resolve("999.wal"), new byte[]{7});
        Files.write(directory.resolve("1000.sst"), new byte[]{8});
        Files.write(directory.resolve("notes.txt"), new byte[]{9});
        Files.write(directory.resolve("0007.wal"), new byte[]{10}); // 非规范内部名称。
        PausedFiles paused = new PausedFiles();
        try (LsmStore store = LsmStore.open(directory, paused); PausedFiles release = paused) {
            paused.awaitStarted(); // 后台已经为输出预留 1001。
            assertEquals(Optional.of(value("1")), store.get(key("a")));
            assertEquals(1002, store.allocateFileId());
            assertEquals(1003, store.allocateFileId());
        }
        assertFalse(Files.exists(directory.resolve("999.wal")));
        assertFalse(Files.exists(directory.resolve("1000.sst")));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(directory.resolve("notes.txt")));
        assertArrayEquals(new byte[]{10}, Files.readAllBytes(directory.resolve("0007.wal")));
    }

    @Test
    void fileIdExhaustionAndOutOfRangeNamesNeverWrapOrOverwrite() throws IOException {
        try (LsmStore ignored = LsmStore.open(directory)) { }
        Path last = directory.resolve(Long.MAX_VALUE + ".wal");
        Files.write(last, new byte[]{7});
        try (LsmStore store = LsmStore.open(directory)) {
            assertThrows(IOException.class, store::allocateFileId);
            assertEquals(Optional.empty(), store.get(key("missing")));
        }
        assertFalse(Files.exists(last));
        Files.write(last, new byte[]{7});
        Path tooLarge = directory.resolve("9223372036854775808.sst");
        Files.write(tooLarge, new byte[]{8});
        assertThrows(IOException.class, () -> LsmStore.open(directory));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(last));
        assertArrayEquals(new byte[]{8}, Files.readAllBytes(tooLarge));
    }

    @Test
    void invalidWriteParametersDoNotLogOrInvalidateTheStore() throws IOException {
        try (LsmStore store = LsmStore.open(directory)) {
            Path path = directory.resolve("1.wal");
            byte[] before = Files.readAllBytes(path);
            assertThrows(NullPointerException.class, () -> store.put(null, value("1")));
            assertThrows(NullPointerException.class, () -> store.put(key("k"), null));
            assertThrows(NullPointerException.class, () -> store.delete(null));
            assertThrows(NullPointerException.class, () -> store.get(null));
            Value large = Value.copyOf(new byte[WalCodec.MAX_PAYLOAD_SIZE - WalCodec.MIN_PAYLOAD_SIZE]);
            assertThrows(IllegalArgumentException.class, () -> store.put(key("k"), large));
            assertEquals(0, store.activeBytes());
            assertArrayEquals(before, Files.readAllBytes(path));
            store.put(key("k"), value("1"));
            assertEquals(Optional.of(value("1")), store.get(key("k")));
        }
    }

    @Test
    void appendFailureAfterLoggingIsUncertainAndStopsTheStore() throws IOException {
        IOException primary = new IOException("failure after logging");
        LsmStore.FileOps files = new LsmStore.FileOps() {
            @Override
            void append(WalWriter writer, WalRecord record) throws IOException {
                super.append(writer, record);
                throw primary;
            }
        };
        try (LsmStore store = LsmStore.open(directory, files)) {
            assertSame(primary, assertThrows(IOException.class, () -> store.put(key("k"), value("1"))));
            assertStopped(store);
        }
        try (LsmStore store = LsmStore.open(directory)) {
            assertEquals(Optional.of(value("1")), store.get(key("k")));
        }
    }

    @Test
    void initializationFailureClosesWalAndLeavesUnambiguousIncompleteDirectory() throws IOException {
        TrackingFiles files = new TrackingFiles();
        files.failCreateManifest = true;
        assertSame(files.failure, assertThrows(IOException.class, () -> LsmStore.open(directory, files)));
        assertThrows(IllegalStateException.class,
                () -> files.writer.append(new WalRecord(key("k"), value("1"))));
        assertTrue(Files.exists(directory.resolve("1.wal")));
        assertFalse(Files.exists(directory.resolve("MANIFEST")));
        assertThrows(IOException.class, () -> LsmStore.open(directory));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void openingFailureClosesManifestAndEveryPreviouslyOpenedTable(boolean failWal) throws IOException {
        seedTwoTablesAndFrozen();
        Path orphan = directory.resolve("100.sst");
        Files.write(orphan, new byte[]{7});
        TrackingFiles files = new TrackingFiles();
        files.failOpenWal = failWal;
        files.failSecondTable = !failWal;
        assertSame(files.failure, assertThrows(IOException.class, () -> LsmStore.open(directory, files)));
        assertThrows(IllegalStateException.class, files.manifest::current);
        assertEquals(failWal ? 2 : 1, files.readers.size());
        for (SsTableReader reader : files.readers) {
            assertThrows(IllegalStateException.class, () -> reader.get(key("a")));
        }
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(orphan));
    }

    @Test
    void cleansOnlyUnreferencedRegularInternalFilesBeforeResumingFrozenFlush() throws IOException {
        seedTwoTablesAndFrozen();
        byte[] active = Files.readAllBytes(directory.resolve("6.wal"));
        byte[] frozen = Files.readAllBytes(directory.resolve("4.wal"));
        byte[] firstTable = Files.readAllBytes(directory.resolve("3.sst"));
        byte[] secondTable = Files.readAllBytes(directory.resolve("5.sst"));
        byte[] manifest = Files.readAllBytes(directory.resolve("MANIFEST"));
        // 同编号的另一种后缀不是引用；完整未发布 SSTable 也必须删除。
        for (String name : List.of("3.wal", "5.wal", "4.sst", "6.sst", "100.sst")) {
            Files.write(directory.resolve(name), new byte[]{7});
        }
        SsTableWriter.write(directory.resolve("101.sst"), List.of(sst("a", value("wrong"))));
        List<String> unknown = List.of("notes.txt", "0007.wal", "0.sst", "-1.wal", "7.SST", "8.sst.tmp");
        for (String name : unknown) {
            Files.write(directory.resolve(name), new byte[]{9});
        }
        Files.createDirectory(directory.resolve("102.wal"));
        Files.write(directory.resolve("102.wal/keep"), new byte[]{10});
        Files.createSymbolicLink(directory.resolve("103.sst"), Path.of("notes.txt"));
        Files.createSymbolicLink(directory.resolve("104.wal"), Path.of("missing"));
        PausedFiles paused = new PausedFiles();
        try (LsmStore store = LsmStore.open(directory, paused); PausedFiles release = paused) {
            paused.awaitStarted();
            for (String name : List.of("3.wal", "5.wal", "4.sst", "6.sst", "100.sst", "101.sst")) {
                assertFalse(Files.exists(directory.resolve(name)), name);
            }
            assertArrayEquals(active, Files.readAllBytes(directory.resolve("6.wal")));
            assertArrayEquals(frozen, Files.readAllBytes(directory.resolve("4.wal")));
            assertArrayEquals(firstTable, Files.readAllBytes(directory.resolve("3.sst")));
            assertArrayEquals(secondTable, Files.readAllBytes(directory.resolve("5.sst")));
            assertArrayEquals(manifest, Files.readAllBytes(directory.resolve("MANIFEST")));
            assertEquals(Optional.of(value("1")), store.get(key("a")));
        }
        for (String name : unknown) {
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(directory.resolve(name)));
        }
        assertArrayEquals(new byte[]{10}, Files.readAllBytes(directory.resolve("102.wal/keep")));
        assertEquals(Path.of("notes.txt"), Files.readSymbolicLink(directory.resolve("103.sst")));
        assertEquals(Path.of("missing"), Files.readSymbolicLink(directory.resolve("104.wal")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANIFEST", "3.sst", "4.wal", "6.wal"})
    void recoveryCorruptionNeverTriggersOrphanCleanup(String name) throws IOException {
        seedTwoTablesAndFrozen();
        Path orphan = directory.resolve("100.sst");
        Files.write(orphan, new byte[]{7});
        Files.write(directory.resolve(name), new byte[]{8});
        assertThrows(IOException.class, () -> LsmStore.open(directory));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(orphan));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedOrphanDeletionDoesNotBlockOtherCleanupOrReadsAndRetriesOnNextOpen(boolean runtime)
            throws IOException {
        try (LsmStore store = LsmStore.open(directory)) {
            store.put(key("k"), value("1"));
        }
        Path retained = directory.resolve("100.sst");
        Path deleted = directory.resolve("101.wal");
        Files.write(retained, new byte[]{7});
        Files.write(deleted, new byte[]{8});
        LsmStore.FileOps files = new LsmStore.FileOps() {
            @Override
            void deleteObsolete(Path path) throws IOException {
                if (path.equals(retained)) {
                    if (runtime) { throw new SecurityException("injected delete denial"); }
                    throw new IOException("injected delete failure");
                }
                super.deleteObsolete(path);
            }
        };
        try (LsmStore store = LsmStore.open(directory, files)) {
            assertArrayEquals(new byte[]{7}, Files.readAllBytes(retained));
            assertFalse(Files.exists(deleted));
            assertEquals(102, store.allocateFileId());
            assertEquals(Optional.of(value("1")), store.get(key("k")));
            store.put(key("k"), value("2"));
        }
        try (LsmStore store = LsmStore.open(directory)) {
            assertFalse(Files.exists(retained));
            assertEquals(Optional.of(value("2")), store.get(key("k")));
        }
    }

    @Test
    void cleanupRunsAfterWalRecoveryAndBeforeWorkerStart() throws IOException {
        try (LsmStore ignored = LsmStore.open(directory)) { }
        Path orphan = directory.resolve("100.sst");
        Files.write(orphan, new byte[]{7});
        LsmStore.FileOps files = new LsmStore.FileOps() {
            private boolean recovered;
            private boolean deleted;

            @Override
            WalWriter openWal(Path path, Consumer<WalRecord> replay) throws IOException {
                WalWriter writer = super.openWal(path, replay);
                recovered = true;
                return writer;
            }

            @Override
            void deleteObsolete(Path path) throws IOException {
                assertTrue(recovered);
                super.deleteObsolete(path);
                deleted = true;
            }

            @Override
            Thread newWorker(Runnable task) {
                return new Thread(task) {
                    @Override
                    public synchronized void start() {
                        assertTrue(deleted);
                        super.start();
                    }
                };
            }
        };
        try (LsmStore store = LsmStore.open(directory, files)) {
            assertFalse(Files.exists(orphan));
            assertEquals(Optional.empty(), store.get(key("k")));
        }
    }

    @Test
    void resourceCleanupAttemptsEveryCloseAndPreservesPrimaryAndSuppressedErrors() {
        List<Integer> events = new ArrayList<>();
        IOException primary = new IOException("primary");
        IOException cleanup = new IOException("cleanup");
        Error error = new AssertionError("error on close");
        List<AutoCloseable> owned = List.of(
                () -> { events.add(1); throw cleanup; },
                () -> { events.add(2); throw error; },
                () -> events.add(3));
        assertSame(primary, LsmStore.closeResources(owned, primary));
        assertEquals(List.of(3, 2, 1), events);
        assertArrayEquals(new Throwable[]{error, cleanup}, primary.getSuppressed());
        events.clear();
        assertSame(error, LsmStore.closeResources(owned, null));
        assertEquals(List.of(3, 2, 1), events);
        assertArrayEquals(new Throwable[]{cleanup}, error.getSuppressed());
    }

    @Test
    void repeatedCloseIsSafeAndTheClosedStoreRejectsOperations() throws IOException {
        LsmStore store = LsmStore.open(directory);
        store.put(key("k"), value("1"));
        store.close();
        store.close();
        assertStopped(store);
    }

    private void seedTwoTablesAndFrozen() throws IOException {
        SsTableWriter.write(directory.resolve("3.sst"), List.of(
                sst("a", value("1")), sst("b", value("1")), sst("c", value("1")),
                sst("d", value("1")), sst("e", value("1"))));
        SsTableWriter.write(directory.resolve("5.sst"), List.of(
                sst("b", value("2")), sst("c", Tombstone.INSTANCE),
                sst("d", value("2")), sst("e", value("2"))));
        byte[] frozen = writeLog(directory.resolve("4.wal"), new WalRecord(key("d"), Tombstone.INSTANCE),
                new WalRecord(key("e"), value("3")), new WalRecord(key("f"), value("3")));
        writeLog(directory.resolve("6.wal"), new WalRecord(key("e"), value("4")),
                new WalRecord(key("f"), Tombstone.INSTANCE));
        try (ManifestFile manifest = ManifestFile.create(directory.resolve("MANIFEST"),
                new ManifestState(1, 1, 0, 0, List.of()))) {
            manifest.append(new ManifestState(2, 2, 1, 8, List.of()));
            manifest.append(new ManifestState(3, 2, 0, 0, List.of(3L)));
            manifest.append(new ManifestState(4, 4, 2, 8, List.of(3L)));
            manifest.append(new ManifestState(5, 4, 0, 0, List.of(3L, 5L)));
            manifest.append(new ManifestState(6, 6, 4, frozen.length, List.of(3L, 5L)));
        }
    }

    private static void assertStopped(LsmStore store) {
        assertThrows(IllegalStateException.class, () -> store.get(key("k")));
        assertThrows(IllegalStateException.class, () -> store.put(key("k"), value("1")));
        assertThrows(IllegalStateException.class, () -> store.delete(key("k")));
    }

    private static byte[] writeLog(Path path, WalRecord... records) throws IOException {
        try (WalWriter writer = WalWriter.openEmpty(path)) {
            for (WalRecord record : records) {
                writer.append(record);
            }
        }
        return Files.readAllBytes(path);
    }

    private static SsTableRecord sst(String key, Entry entry) {
        return new SsTableRecord(key(key), entry);
    }

    private static ByteKey key(String text) {
        return ByteKey.copyOf(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Value value(String text) {
        return Value.copyOf(text.getBytes(StandardCharsets.UTF_8));
    }

    private static final class TrackingFiles extends LsmStore.FileOps {
        private final IOException failure = new IOException("injected open failure");
        private final List<SsTableReader> readers = new ArrayList<>();
        private ManifestFile manifest;
        private WalWriter writer;
        private boolean failCreateManifest;
        private boolean failOpenWal;
        private boolean failSecondTable;

        @Override
        WalWriter createWal(Path path) throws IOException {
            writer = super.createWal(path);
            return writer;
        }

        @Override
        ManifestFile createManifest(Path path, ManifestState initial) throws IOException {
            if (failCreateManifest) { throw failure; }
            return super.createManifest(path, initial);
        }

        @Override
        ManifestFile openManifest(Path path) throws IOException {
            manifest = super.openManifest(path);
            return manifest;
        }

        @Override
        SsTableReader openTable(Path path) throws IOException {
            if (failSecondTable && readers.size() == 1) { throw failure; }
            SsTableReader reader = super.openTable(path);
            readers.add(reader);
            return reader;
        }

        @Override
        WalWriter openWal(Path path, Consumer<WalRecord> replay) throws IOException {
            if (failOpenWal) { throw failure; }
            return super.openWal(path, replay);
        }
    }

    private static final class PausedFiles extends LsmStore.FileOps implements AutoCloseable {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        void writeTable(Path path, Iterable<SsTableRecord> records) throws IOException {
            started.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("test flush release timed out");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException(failure);
            }
            super.writeTable(path, records);
        }

        void awaitStarted() throws IOException {
            try {
                if (!started.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("test flush start timed out");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException(failure);
            }
        }

        @Override
        public void close() {
            release.countDown();
        }
    }
}
