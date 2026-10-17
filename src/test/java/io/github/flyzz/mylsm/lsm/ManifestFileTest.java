package io.github.flyzz.mylsm.lsm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestFileTest {
    @TempDir
    Path directory;

    @Test
    void createsAppendsAndRecoversWithoutRewritingHistory() throws IOException {
        Path path = directory.resolve("MANIFEST");
        byte[] first;
        try (ManifestFile file = ManifestFile.create(path, initial())) {
            assertEquals(initial(), file.current());
            first = Files.readAllBytes(path);
            assertEquals(52, first.length);
            file.append(rotated());
            assertEquals(rotated(), file.current());
            assertArrayEquals(first, Arrays.copyOf(Files.readAllBytes(path), first.length));
        }
        try (ManifestFile file = ManifestFile.open(path)) {
            assertEquals(rotated(), file.current());
            file.append(published());
        }
        byte[] all = Files.readAllBytes(path);
        assertArrayEquals(history(initial(), rotated(), published()), all);
        try (ManifestFile file = ManifestFile.open(path)) {
            assertEquals(published(), file.current());
        }
        assertArrayEquals(all, Files.readAllBytes(path));
    }

    @Test
    void creationPreservesExistingFilesAndOpenDoesNotCreateMissingOnes() throws IOException {
        Path path = directory.resolve("MANIFEST");
        byte[] original = {1, 2, 3};
        Files.write(path, original);
        assertThrows(FileAlreadyExistsException.class, () -> ManifestFile.create(path, initial()));
        assertArrayEquals(original, Files.readAllBytes(path));
        Path missing = directory.resolve("missing");
        assertThrows(NoSuchFileException.class, () -> ManifestFile.open(missing));
        assertFalse(Files.exists(missing));
        assertThrows(NoSuchFileException.class,
                () -> ManifestFile.create(directory.resolve("missing-parent/MANIFEST"), initial()));
    }

    @Test
    void invalidInitialParametersDoNotCreateOrModifyFiles() throws IOException {
        Path path = directory.resolve("MANIFEST");
        assertThrows(NullPointerException.class, () -> ManifestFile.create(path, null));
        assertThrows(IllegalArgumentException.class, () -> ManifestFile.create(path, rotated()));
        assertFalse(Files.exists(path));
        assertThrows(NullPointerException.class, () -> ManifestFile.create((Path) null, initial()));
        assertThrows(NullPointerException.class, () -> ManifestFile.open((Path) null));
        assertThrows(NullPointerException.class, () -> ManifestFile.open((ManifestIo) null));
        assertThrows(NullPointerException.class, () -> ManifestFile.create((ManifestIo) null, initial()));
        Files.write(path, new byte[]{9});
        assertThrows(IllegalArgumentException.class, () -> ManifestFile.create(path, rotated()));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(path));
    }

    @Test
    void injectedCreateOwnsResourcesEvenForInvalidInputAndNonEmptyFile() throws IOException {
        Path path = directory.resolve("MANIFEST");
        FaultIo io = new FaultIo(path, true);
        assertThrows(NullPointerException.class, () -> ManifestFile.create(io, null));
        assertEquals(1, io.closeCalls);
        assertEquals(0, Files.size(path));
        Files.write(path, new byte[]{7});
        FaultIo nonEmpty = new FaultIo(path, false);
        assertThrows(IOException.class, () -> ManifestFile.create(nonEmpty, initial()));
        assertEquals(1, nonEmpty.closeCalls);
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(path));
    }

    @Test
    void parameterErrorsDoNotInvalidateAnOpenManifest() throws IOException {
        Path path = directory.resolve("MANIFEST");
        try (ManifestFile file = ManifestFile.create(path, initial())) {
            byte[] before = Files.readAllBytes(path);
            assertThrows(NullPointerException.class, () -> file.append(null));
            assertThrows(IllegalArgumentException.class, () -> file.append(initial()));
            assertThrows(IllegalArgumentException.class, () -> file.append(published()));
            assertArrayEquals(before, Files.readAllBytes(path));
            assertEquals(initial(), file.current());
            file.append(rotated());
        }
    }

    @Test
    void handlesShortWritesAndChangesCurrentOnlyAfterSync() throws IOException {
        Path path = directory.resolve("MANIFEST");
        FaultIo io = new FaultIo(path, true);
        io.writeLimit = 3;
        io.onForce = () -> assertArrayEquals(history(initial()), Files.readAllBytes(path));
        try (ManifestFile file = ManifestFile.create(io, initial())) {
            assertEquals(1, io.forceCalls);
            io.onForce = () -> {
                assertArrayEquals(history(initial(), rotated()), Files.readAllBytes(path));
                assertEquals(initial(), file.current());
            };
            file.append(rotated());
            assertEquals(rotated(), file.current());
            assertEquals(2, io.forceCalls);
            assertTrue(io.writeCalls > 4);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void failsOnWritesWithoutProgressAndStopsTheInstance(int result) throws IOException {
        Path path = directory.resolve("MANIFEST");
        FaultIo io = new FaultIo(path, true);
        ManifestFile file = ManifestFile.create(io, initial());
        io.writeResult = result;
        assertThrows(IOException.class, () -> file.append(rotated()));
        assertStopped(file);
        assertEquals(1, io.forceCalls);
        file.close();
        assertEquals(1, io.closeCalls);
        assertArrayEquals(history(initial()), Files.readAllBytes(path));
    }

    @Test
    void partialAppendFailureIsRepairedOnReopen() throws IOException {
        Path path = directory.resolve("MANIFEST");
        FaultIo io = new FaultIo(path, true);
        ManifestFile file = ManifestFile.create(io, initial());
        io.writeLimit = 3;
        io.writeFailureAt = 55;
        IOException failure = assertThrows(IOException.class, () -> file.append(rotated()));
        assertSame(io.writeFailure, failure);
        assertEquals(55, Files.size(path));
        assertStopped(file);
        file.close();
        try (ManifestFile recovered = ManifestFile.open(path)) {
            assertEquals(initial(), recovered.current());
            assertEquals(52, Files.size(path));
            recovered.append(rotated());
        }
        assertArrayEquals(history(initial(), rotated()), Files.readAllBytes(path));
    }

    @Test
    void syncFailureCanLeaveACompleteRecoverableState() throws IOException {
        Path path = directory.resolve("MANIFEST");
        FaultIo io = new FaultIo(path, true);
        ManifestFile file = ManifestFile.create(io, initial());
        io.failForceCall = 2;
        IOException failure = assertThrows(IOException.class, () -> file.append(rotated()));
        assertSame(io.forceFailure, failure);
        assertStopped(file);
        file.close();
        try (ManifestFile recovered = ManifestFile.open(path)) {
            assertEquals(rotated(), recovered.current());
            recovered.append(published());
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 8, 20})
    void initializationWriteFailureClosesAndPreservesItsFile(long offset) throws IOException {
        Path path = directory.resolve("MANIFEST");
        FaultIo io = new FaultIo(path, true);
        io.writeFailureAt = offset;
        io.writeLimit = 4;
        IOException failure = assertThrows(IOException.class, () -> ManifestFile.create(io, initial()));
        assertSame(io.writeFailure, failure);
        assertEquals(1, io.closeCalls);
        assertEquals(offset, Files.size(path));
        assertTrue(Files.exists(path));
        assertThrows(IOException.class, () -> ManifestFile.open(path));
        assertEquals(offset, Files.size(path));
    }

    @Test
    void initializationSyncFailureMayAlsoLeaveACompleteState() throws IOException {
        Path path = directory.resolve("MANIFEST");
        FaultIo io = new FaultIo(path, true);
        io.failForceCall = 1;
        IOException failure = assertThrows(IOException.class, () -> ManifestFile.create(io, initial()));
        assertSame(io.forceFailure, failure);
        assertEquals(1, io.closeCalls);
        assertArrayEquals(history(initial()), Files.readAllBytes(path));
        try (ManifestFile file = ManifestFile.open(path)) {
            assertEquals(initial(), file.current());
        }
    }

    @Test
    void preservesInitializationErrorWhenCleanupAlsoFails() throws IOException {
        FaultIo io = new FaultIo(directory.resolve("MANIFEST"), true);
        io.writeFailureAt = 0;
        io.failClose = true;
        IOException failure = assertThrows(IOException.class, () -> ManifestFile.create(io, initial()));
        assertSame(io.writeFailure, failure);
        assertArrayEquals(new Throwable[]{io.closeFailure}, failure.getSuppressed());
        assertEquals(1, io.closeCalls);
    }

    @Test
    void repairsEveryByteCutInEachLaterStateAndAppendsAtTheCorrectBoundary() throws IOException {
        List<ManifestState> states = List.of(initial(), rotated(), published());
        for (int index = 1; index < states.size(); index++) {
            byte[] prefix = history(states.subList(0, index).toArray(ManifestState[]::new));
            byte[] frame = ManifestCodec.encode(states.get(index));
            for (int cut = 0; cut < frame.length; cut++) {
                Path path = directory.resolve("cut-" + index + "-" + cut);
                Files.write(path, concatenate(prefix, Arrays.copyOf(frame, cut)));
                FaultIo io = new FaultIo(path, false);
                try (ManifestFile file = ManifestFile.open(io)) {
                    assertEquals(states.get(index - 1), file.current());
                    assertEquals(prefix.length, Files.size(path));
                    assertEquals(cut == 0 ? 0 : 1, io.truncateCalls);
                    assertEquals(cut == 0 ? 0 : 1, io.forceCalls);
                    file.append(states.get(index));
                }
                assertArrayEquals(concatenate(prefix, frame), Files.readAllBytes(path));
            }
        }
    }

    @Test
    void refusesEveryCutBeforeTheFirstCompleteStateWithoutRepairing() throws IOException {
        byte[] all = history(initial());
        for (int cut = 0; cut < all.length; cut++) {
            Path path = directory.resolve("initial-cut-" + cut);
            byte[] bytes = Arrays.copyOf(all, cut);
            Files.write(path, bytes);
            FaultIo io = new FaultIo(path, false);
            assertThrows(IOException.class, () -> ManifestFile.open(io));
            assertEquals(1, io.closeCalls);
            assertEquals(0, io.truncateCalls);
            assertEquals(0, io.forceCalls);
            assertArrayEquals(bytes, Files.readAllBytes(path));
        }
    }

    @Test
    void doesNotRepairCompleteCorruptionAtAnyStateOrSearchPastIt() throws IOException {
        byte[] all = history(initial(), rotated(), published());
        for (int offset = 0; offset < all.length; offset++) {
            // 长度变成合法的大值时无法与真实残尾区分；这里只改头部、CRC 和帧内容。
            if ((offset >= 8 && offset < 12) || (offset >= 52 && offset < 56)
                    || (offset >= 96 && offset < 100)) {
                continue;
            }
            byte[] bytes = all.clone();
            bytes[offset] ^= 1;
            Path path = directory.resolve("corrupt-" + offset);
            Files.write(path, bytes);
            FaultIo io = new FaultIo(path, false);
            assertThrows(IOException.class, () -> ManifestFile.open(io));
            assertEquals(1, io.closeCalls);
            assertEquals(0, io.truncateCalls);
            assertArrayEquals(bytes, Files.readAllBytes(path));
        }
    }

    @Test
    void trimsALegalIncompleteLargeDeclarationWithoutReadingItsBody() throws IOException {
        Path path = directory.resolve("MANIFEST");
        byte[] incomplete = ByteBuffer.allocate(8)
                .putInt(ManifestCodec.MAX_PAYLOAD_SIZE - 4).putInt(0).array();
        Files.write(path, concatenate(history(initial()), incomplete));
        FaultIo io = new FaultIo(path, false);
        io.readFailureAt = 60; // 不应尝试读取声明中尚不存在的 payload。
        try (ManifestFile file = ManifestFile.open(io)) {
            assertEquals(initial(), file.current());
            assertEquals(1, io.truncateCalls);
            assertEquals(1, io.forceCalls);
        }
        assertArrayEquals(history(initial()), Files.readAllBytes(path));
    }

    @Test
    void rejectsSemanticCorruptionWithRecomputedCrcAndReportsOffset() throws IOException {
        byte[] frame = ManifestCodec.encode(rotated());
        ByteBuffer.wrap(frame).putLong(24, 0); // frozen 清零但长度未清。
        updateChecksum(frame);
        assertInvalidHistory("bad-fields", history(initial()), frame, "52");
        // 每个状态单独合法，但不满足首状态/历史转换。
        assertInvalidHistory("bad-initial", ManifestCodec.encodeHeader(),
                ManifestCodec.encode(rotated()), "8");
        for (ManifestState next : List.of(
                new ManifestState(3, 2, 1, 100, List.of()),
                new ManifestState(2, 2, 0, 0, List.of()),
                new ManifestState(2, 2, 1, 100, List.of(3L)))) {
            assertInvalidHistory("bad-transition-" + next.revision() + "-" + next.sstIds().size()
                    + "-" + next.frozenWalId(), history(initial()), ManifestCodec.encode(next), "52");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 35, 37, 16_777_216, 16_777_217, Integer.MAX_VALUE})
    void illegalTailLengthIsNotTreatedAsAnIncompleteAppend(int length) throws IOException {
        byte[] prefix = ByteBuffer.allocate(8).putInt(length).putInt(0).array();
        assertInvalidHistory("bad-length", history(initial()), prefix, "52");
    }

    @Test
    void handlesShortReadsWithoutRewritingACompleteManifest() throws IOException {
        Path path = directory.resolve("MANIFEST");
        byte[] bytes = history(initial(), rotated(), published());
        Files.write(path, bytes);
        FaultIo io = new FaultIo(path, false);
        io.readLimit = 3;
        try (ManifestFile file = ManifestFile.open(io)) {
            assertEquals(published(), file.current());
            assertEquals(0, io.forceCalls);
            assertEquals(0, io.truncateCalls);
            assertEquals(0, io.writeCalls);
            assertTrue(io.readCalls > 10);
        }
        assertArrayEquals(bytes, Files.readAllBytes(path));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 8, 16, 52, 60})
    void actualReadFailureOrEofIsNotInterpretedAsRepairableTail(long offset) throws IOException {
        for (boolean eof : new boolean[]{false, true}) {
            Path path = directory.resolve("read-" + offset + "-" + eof);
            byte[] bytes = history(initial(), rotated());
            Files.write(path, bytes);
            FaultIo io = new FaultIo(path, false);
            io.readLimit = 1;
            io.readFailureAt = offset;
            io.readEof = eof;
            IOException failure = assertThrows(IOException.class, () -> ManifestFile.open(io));
            if (eof) {
                assertTrue(failure instanceof EOFException);
            } else {
                assertSame(io.readFailure, failure);
            }
            assertEquals(1, io.closeCalls);
            assertEquals(0, io.truncateCalls);
            assertArrayEquals(bytes, Files.readAllBytes(path));
        }
    }

    @Test
    void zeroReadAndSizeFailureCloseResources() throws IOException {
        Path path = directory.resolve("MANIFEST");
        Files.write(path, history(initial()));
        FaultIo zero = new FaultIo(path, false);
        zero.zeroRead = true;
        assertThrows(IOException.class, () -> ManifestFile.open(zero));
        assertEquals(1, zero.closeCalls);
        FaultIo size = new FaultIo(path, false);
        size.failSize = true;
        size.failClose = true;
        IOException failure = assertThrows(IOException.class, () -> ManifestFile.open(size));
        assertSame(size.sizeFailure, failure);
        assertArrayEquals(new Throwable[]{size.closeFailure}, failure.getSuppressed());
        assertEquals(1, size.closeCalls);
    }

    @Test
    void truncateFailureDoesNotReturnAUsableInstance() throws IOException {
        Path path = directory.resolve("MANIFEST");
        byte[] bytes = concatenate(history(initial()), new byte[]{0});
        Files.write(path, bytes);
        FaultIo io = new FaultIo(path, false);
        io.failTruncate = true;
        IOException failure = assertThrows(IOException.class, () -> ManifestFile.open(io));
        assertSame(io.truncateFailure, failure);
        assertEquals(1, io.closeCalls);
        assertEquals(0, io.forceCalls);
        assertArrayEquals(bytes, Files.readAllBytes(path));
    }

    @Test
    void repairSyncFailureKeepsItsPrimaryErrorAndDoesNotReturnAnInstance() throws IOException {
        Path path = directory.resolve("MANIFEST");
        Files.write(path, concatenate(history(initial()), new byte[]{0}));
        FaultIo io = new FaultIo(path, false);
        io.failForceCall = 1;
        io.failClose = true;
        IOException failure = assertThrows(IOException.class, () -> ManifestFile.open(io));
        assertSame(io.forceFailure, failure);
        assertEquals(1, io.truncateCalls);
        assertEquals(1, io.closeCalls);
        assertArrayEquals(new Throwable[]{io.closeFailure}, failure.getSuppressed());
        assertArrayEquals(history(initial()), Files.readAllBytes(path));
    }

    @Test
    void stateIsClosedEvenWhenCloseFailsAndRepeatedCloseDoesNotRetry() throws IOException {
        FaultIo io = new FaultIo(directory.resolve("MANIFEST"), true);
        ManifestFile file = ManifestFile.create(io, initial());
        io.failClose = true;
        assertSame(io.closeFailure, assertThrows(IOException.class, file::close));
        assertStopped(file);
        file.close();
        assertEquals(1, io.closeCalls);
    }

    @Test
    void normalCloseAlsoRejectsFurtherOperations() throws IOException {
        FaultIo io = new FaultIo(directory.resolve("MANIFEST"), true);
        ManifestFile file = ManifestFile.create(io, initial());
        file.close();
        file.close();
        assertStopped(file);
        assertEquals(1, io.closeCalls);
    }

    @Test
    void appendsCompactionAfterRotationAndRecoversFrozenBeforeItsLaterPublication() throws IOException {
        Path path = directory.resolve("MANIFEST");
        List<ManifestState> prefix = compactionPrefix();
        ManifestState compacted = compacted(prefix.getLast());
        ManifestState laterFlush = new ManifestState(8, 7, 0, 0, List.of(6L, 8L));
        try (ManifestFile file = ManifestFile.create(path, prefix.getFirst())) {
            for (int i = 1; i < prefix.size(); i++) {
                file.append(prefix.get(i));
            }
            byte[] before = Files.readAllBytes(path);
            file.append(compacted);
            assertEquals(compacted, file.current());
            assertArrayEquals(before, Arrays.copyOf(Files.readAllBytes(path), before.length));
        }
        try (ManifestFile reopened = ManifestFile.open(path)) {
            assertEquals(compacted, reopened.current());
            assertEquals(7, reopened.current().activeWalId());
            assertEquals(4, reopened.current().frozenWalId());
            assertEquals(300, reopened.current().frozenWalLength());
            reopened.append(laterFlush);
        }
        try (ManifestFile reopened = ManifestFile.open(path)) {
            assertEquals(laterFlush, reopened.current());
        }
        assertArrayEquals(concatenate(history(prefix.toArray(ManifestState[]::new)),
                concatenate(ManifestCodec.encode(compacted), ManifestCodec.encode(laterFlush))),
                Files.readAllBytes(path));
    }

    @Test
    void installsCompactedCurrentOnlyAfterTheNewFrameIsSynced() throws IOException {
        Path path = directory.resolve("MANIFEST");
        List<ManifestState> prefix = compactionPrefix();
        ManifestState before = prefix.getLast();
        ManifestState compacted = compacted(before);
        byte[] original = history(prefix.toArray(ManifestState[]::new));
        Files.write(path, original);
        FaultIo io = new FaultIo(path, false);
        io.writeLimit = 3;
        try (ManifestFile file = ManifestFile.open(io)) {
            io.onForce = () -> {
                assertEquals(before, file.current());
                assertArrayEquals(concatenate(original, ManifestCodec.encode(compacted)), Files.readAllBytes(path));
            };
            file.append(compacted);
            assertEquals(compacted, file.current());
            assertEquals(1, io.forceCalls);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repairsEveryCutInACompactionFrameWithoutLosingTheOldWalState(boolean frozen) throws IOException {
        List<ManifestState> history = compactionPrefix();
        List<ManifestState> prefix = frozen ? history : history.subList(0, history.size() - 1);
        ManifestState before = prefix.getLast();
        ManifestState compacted = compacted(before);
        ManifestState following = frozen
                ? new ManifestState(8, 7, 0, 0, List.of(6L, 8L))
                : new ManifestState(7, 7, 4, 300, List.of(6L));
        byte[] original = history(prefix.toArray(ManifestState[]::new));
        byte[] frame = ManifestCodec.encode(compacted);
        for (int cut = 0; cut < frame.length; cut++) {
            Path path = directory.resolve("compaction-cut-" + frozen + "-" + cut);
            Files.write(path, concatenate(original, Arrays.copyOf(frame, cut)));
            FaultIo io = new FaultIo(path, false);
            try (ManifestFile recovered = ManifestFile.open(io)) {
                assertEquals(before, recovered.current());
                assertEquals(original.length, Files.size(path));
                assertEquals(cut == 0 ? 0 : 1, io.truncateCalls);
                assertEquals(cut == 0 ? 0 : 1, io.forceCalls);
                recovered.append(compacted);
                recovered.append(following);
            }
            assertArrayEquals(concatenate(original, concatenate(frame, ManifestCodec.encode(following))),
                    Files.readAllBytes(path));
        }
    }

    @Test
    void failedCompactionSyncStillAllowsRecoveryOfTheCompleteNewFileSet() throws IOException {
        Path path = directory.resolve("MANIFEST");
        List<ManifestState> prefix = compactionPrefix();
        ManifestState compacted = compacted(prefix.getLast());
        byte[] original = history(prefix.toArray(ManifestState[]::new));
        Files.write(path, original);
        FaultIo io = new FaultIo(path, false);
        io.failForceCall = 1;
        try (ManifestFile file = ManifestFile.open(io)) {
            assertSame(io.forceFailure, assertThrows(IOException.class, () -> file.append(compacted)));
            assertStopped(file);
        }
        // 报错不代表回滚：完整帧仍可能被恢复采用，不能先回收新旧任一恢复来源。
        assertArrayEquals(concatenate(original, ManifestCodec.encode(compacted)), Files.readAllBytes(path));
        try (ManifestFile recovered = ManifestFile.open(path)) {
            assertEquals(compacted, recovered.current());
            recovered.append(new ManifestState(8, 7, 0, 0, List.of(6L, 8L)));
        }
    }

    @Test
    void invalidCompactionIsRejectedBeforeIoAndDoesNotPoisonTheManifest() throws IOException {
        Path path = directory.resolve("MANIFEST");
        List<ManifestState> prefix = compactionPrefix();
        byte[] original = history(prefix.toArray(ManifestState[]::new));
        Files.write(path, original);
        FaultIo io = new FaultIo(path, false);
        try (ManifestFile file = ManifestFile.open(io)) {
            for (ManifestState invalid : invalidCompactions()) {
                assertThrows(IllegalArgumentException.class, () -> file.append(invalid));
                assertEquals(prefix.getLast(), file.current());
                assertEquals(0, io.writeCalls);
                assertEquals(0, io.forceCalls);
                assertArrayEquals(original, Files.readAllBytes(path));
            }
            file.append(compacted(prefix.getLast()));
        }
    }

    @Test
    void recoveryRejectsCrcValidButIllegalCompactionInsteadOfFallingBack() throws IOException {
        byte[] original = history(compactionPrefix().toArray(ManifestState[]::new));
        int index = 0;
        for (ManifestState invalid : invalidCompactions()) {
            byte[] frame = ManifestCodec.encode(invalid);
            assertEquals(invalid, ManifestCodec.decode(frame)); // 单个状态和 CRC 合法，历史语义不合法。
            assertInvalidHistory("bad-compaction-" + index++, original, frame, Integer.toString(original.length));
        }
    }

    private static List<ManifestState> compactionPrefix() {
        return List.of(initial(), rotated(), published(),
                new ManifestState(4, 4, 2, 200, List.of(3L)),
                new ManifestState(5, 4, 0, 0, List.of(3L, 5L)),
                new ManifestState(6, 7, 4, 300, List.of(3L, 5L)));
    }

    private static ManifestState compacted(ManifestState before) {
        return new ManifestState(before.revision() + 1, before.activeWalId(), before.frozenWalId(),
                before.frozenWalLength(), List.of(6L));
    }

    private static List<ManifestState> invalidCompactions() {
        return List.of(
                new ManifestState(7, 4, 0, 0, List.of(6L)), // 使用压缩开始前的过时 WAL 快照。
                new ManifestState(7, 8, 4, 300, List.of(6L)), // 同时修改 active。
                new ManifestState(7, 7, 4, 301, List.of(6L)), // 修改封存长度。
                new ManifestState(7, 7, 0, 0, List.of(6L)), // 丢弃仍需的 frozen。
                new ManifestState(7, 7, 4, 300, List.of(3L)), // 复用输入编号。
                new ManifestState(7, 7, 4, 300, List.of()),
                new ManifestState(7, 7, 4, 300, List.of(3L, 5L)), // 不接受无操作状态提交。
                new ManifestState(7, 7, 4, 300, List.of(3L, 6L))); // 部分替换。
    }

    private void assertInvalidHistory(String name, byte[] prefix, byte[] frame, String offset)
            throws IOException {
        Path path = directory.resolve(name);
        byte[] bytes = concatenate(prefix, frame);
        Files.write(path, bytes);
        FaultIo io = new FaultIo(path, false);
        IOException failure = assertThrows(IOException.class, () -> ManifestFile.open(io));
        assertTrue(failure.getMessage().contains("offset " + offset));
        assertNotNull(failure.getCause());
        assertEquals(0, io.truncateCalls);
        assertEquals(1, io.closeCalls);
        assertArrayEquals(bytes, Files.readAllBytes(path));
    }

    private static void assertStopped(ManifestFile file) {
        assertThrows(IllegalStateException.class, file::current);
        assertThrows(IllegalStateException.class, () -> file.append(rotated()));
    }

    private static ManifestState initial() {
        return new ManifestState(1, 1, 0, 0, List.of());
    }

    private static ManifestState rotated() {
        return new ManifestState(2, 2, 1, 12000, List.of());
    }

    private static ManifestState published() {
        return new ManifestState(3, 2, 0, 0, List.of(3L));
    }

    private static byte[] history(ManifestState... states) {
        byte[] bytes = ManifestCodec.encodeHeader();
        for (ManifestState state : states) {
            bytes = concatenate(bytes, ManifestCodec.encode(state));
        }
        return bytes;
    }

    private static byte[] concatenate(byte[] first, byte[] second) {
        return ByteBuffer.allocate(first.length + second.length).put(first).put(second).array();
    }

    private static void updateChecksum(byte[] frame) {
        CRC32C crc = new CRC32C();
        crc.update(frame, 0, 4);
        crc.update(frame, 8, frame.length - 8);
        ByteBuffer.wrap(frame).putInt(4, (int) crc.getValue());
    }

    private static final class FaultIo implements ManifestIo {
        private final FileChannel channel;
        private final IOException readFailure = new IOException("injected read failure");
        private final IOException writeFailure = new IOException("injected write failure");
        private final IOException forceFailure = new IOException("injected force failure");
        private final IOException closeFailure = new IOException("injected close failure");
        private final IOException truncateFailure = new IOException("injected truncate failure");
        private final IOException sizeFailure = new IOException("injected size failure");
        private int readLimit = Integer.MAX_VALUE;
        private int writeLimit = Integer.MAX_VALUE;
        private long readFailureAt = -1;
        private long writeFailureAt = -1;
        private Integer writeResult;
        private boolean zeroRead;
        private boolean readEof;
        private boolean failSize;
        private boolean failTruncate;
        private boolean failClose;
        private int failForceCall = -1;
        private int readCalls;
        private int writeCalls;
        private int forceCalls;
        private int closeCalls;
        private int truncateCalls;
        private IoAction onForce = () -> { };

        FaultIo(Path path, boolean create) throws IOException {
            channel = create ? FileChannel.open(path, CREATE_NEW, READ, WRITE)
                    : FileChannel.open(path, READ, WRITE);
        }

        @Override
        public long size() throws IOException {
            if (failSize) {
                throw sizeFailure;
            }
            return channel.size();
        }

        @Override
        public int read(ByteBuffer target, long offset) throws IOException {
            readCalls++;
            if (zeroRead) {
                return 0;
            }
            if (readFailureAt >= 0 && offset >= readFailureAt) {
                if (readEof) {
                    return -1;
                }
                throw readFailure;
            }
            int oldLimit = target.limit();
            try {
                target.limit(target.position() + Math.min(target.remaining(), readLimit));
                return channel.read(target, offset);
            } finally {
                target.limit(oldLimit);
            }
        }

        @Override
        public int write(ByteBuffer source, long offset) throws IOException {
            writeCalls++;
            if (writeResult != null) {
                return writeResult;
            }
            if (writeFailureAt >= 0 && offset >= writeFailureAt) {
                throw writeFailure;
            }
            int oldLimit = source.limit();
            try {
                source.limit(source.position() + Math.min(source.remaining(), writeLimit));
                return channel.write(source, offset);
            } finally {
                source.limit(oldLimit);
            }
        }

        @Override
        public void truncate(long size) throws IOException {
            truncateCalls++;
            if (failTruncate) {
                throw truncateFailure;
            }
            channel.truncate(size);
        }

        @Override
        public void force() throws IOException {
            forceCalls++;
            onForce.run();
            if (forceCalls == failForceCall) {
                throw forceFailure;
            }
            channel.force(true);
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

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
