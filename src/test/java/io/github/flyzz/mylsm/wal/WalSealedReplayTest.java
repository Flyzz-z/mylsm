package io.github.flyzz.mylsm.wal;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.nio.file.StandardOpenOption.READ;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WalSealedReplayTest {
    private static final WalRecord PUT = new WalRecord(ByteKey.copyOf(new byte[]{0, (byte) 0xff}),
            Value.copyOf(new byte[]{0, (byte) 0x80}));
    private static final WalRecord DELETE = new WalRecord(PUT.key(), Tombstone.INSTANCE);

    @TempDir
    Path directory;

    @Test
    void sealedReplayUsesOnlyReadsHandlesShortReadsAndCloses() throws IOException {
        Path path = directory.resolve("sealed.wal");
        writeLog(path, PUT, DELETE, PUT);
        byte[] original = Files.readAllBytes(path);
        ReadOnlyIo io = new ReadOnlyIo(path);
        io.maxRead = 2;
        List<WalRecord> records = new ArrayList<>();
        WalWriter.replaySealed(io, original.length, records::add);
        assertEquals(List.of(PUT, DELETE, PUT), records);
        assertEquals(1, io.closeCalls);
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test
    void sealedLengthDetectsRecordBoundaryTruncationAndExtraBytesBeforeReplay() throws IOException {
        Path path = directory.resolve("sealed.wal");
        writeLog(path, PUT, DELETE);
        byte[] original = Files.readAllBytes(path);
        for (int length : new int[]{0, 8, original.length - WalCodec.encode(DELETE).length,
                original.length + 1}) {
            Files.write(path, Arrays.copyOf(original, length));
            List<WalRecord> records = new ArrayList<>();
            assertThrows(IOException.class, () -> WalWriter.replaySealed(path, original.length, records::add));
            assertEquals(List.of(), records);
            assertEquals(length, Files.size(path));
        }
    }

    @Test
    void matchingSizeStillRejectsEveryPartialRecordWithoutRepair() throws IOException {
        Path path = directory.resolve("sealed.wal");
        writeLog(path, PUT, DELETE);
        byte[] original = Files.readAllBytes(path);
        int boundary = original.length - WalCodec.encode(DELETE).length;
        for (int length = boundary + 1; length < original.length; length++) {
            byte[] bytes = Arrays.copyOf(original, length);
            Files.write(path, bytes);
            assertThrows(IOException.class, () -> WalWriter.replaySealed(path, bytes.length, record -> { }));
            assertArrayEquals(bytes, Files.readAllBytes(path));
        }
    }

    @ParameterizedTest
    @MethodSource("io.github.flyzz.mylsm.wal.WalRecoveryTest#corruptRecords")
    void rejectsCompleteCorruptionWithNoFileModification(byte[] corrupt) throws IOException {
        Path path = directory.resolve("sealed.wal");
        writeLog(path, PUT);
        byte[] original = Files.readAllBytes(path);
        byte[] bytes = ByteBuffer.allocate(original.length + corrupt.length).put(original).put(corrupt).array();
        Files.write(path, bytes);
        IOException failure = assertThrows(IOException.class,
                () -> WalWriter.replaySealed(path, bytes.length, record -> { }));
        assertEquals(true, failure.getMessage().contains("offset " + original.length));
        assertArrayEquals(bytes, Files.readAllBytes(path));
    }

    @Test
    void replayFailurePreservesPrimaryErrorAndClosesTheInput() throws IOException {
        Path path = directory.resolve("sealed.wal");
        writeLog(path, PUT);
        ReadOnlyIo io = new ReadOnlyIo(path);
        io.failClose = true;
        RuntimeException primary = new IllegalStateException("callback failed");
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> WalWriter.replaySealed(io, Files.size(path), record -> { throw primary; }));
        assertSame(primary, failure);
        assertArrayEquals(new Throwable[]{io.closeFailure}, failure.getSuppressed());
        assertEquals(1, io.closeCalls);
    }

    @Test
    void sealedReadFailureClosesWithoutCallingAnyWriteOperation() throws IOException {
        Path path = directory.resolve("sealed.wal");
        writeLog(path, PUT);
        ReadOnlyIo io = new ReadOnlyIo(path);
        io.failRead = true;
        assertThrows(IOException.class, () -> WalWriter.replaySealed(io, Files.size(path), record -> { }));
        assertEquals(1, io.closeCalls);
    }

    @Test
    void existingRecoveryNeverCreatesOrInitializesAReferencedLog() throws IOException {
        Path missing = directory.resolve("missing.wal");
        assertThrows(NoSuchFileException.class,
                () -> WalWriter.openExistingRecovering(missing, record -> { }));
        assertFalse(Files.exists(missing));
        Path path = directory.resolve("active.wal");
        for (int length = 0; length < 8; length++) {
            byte[] bytes = Arrays.copyOf(new byte[]{0x4d, 0x59, 0x57, 0x4c, 0, 0, 0, 1}, length);
            Files.write(path, bytes);
            assertThrows(IOException.class, () -> WalWriter.openExistingRecovering(path, record -> { }));
            assertArrayEquals(bytes, Files.readAllBytes(path));
        }
    }

    @Test
    void existingRecoveryRetainsActiveTailRepairAndAppendBehavior() throws IOException {
        Path path = directory.resolve("active.wal");
        writeLog(path, PUT);
        byte[] prefix = Files.readAllBytes(path);
        Files.write(path, ByteBuffer.allocate(prefix.length + 1).put(prefix).put((byte) 0).array());
        List<WalRecord> records = new ArrayList<>();
        try (WalWriter writer = WalWriter.openExistingRecovering(path, records::add)) {
            assertEquals(List.of(PUT), records);
            assertArrayEquals(prefix, Files.readAllBytes(path));
            writer.append(DELETE);
        }
        records.clear();
        try (WalWriter ignored = WalWriter.openExistingRecovering(path, records::add)) {
            assertEquals(List.of(PUT, DELETE), records);
        }
    }

    @Test
    void validatesSealedParametersAndAcceptsAHeaderOnlyLog() throws IOException {
        Path path = directory.resolve("sealed.wal");
        writeLog(path);
        WalWriter.replaySealed(path, 8, record -> { throw new AssertionError("not empty"); });
        assertThrows(NullPointerException.class, () -> WalWriter.replaySealed((Path) null, 8, record -> { }));
        assertThrows(NullPointerException.class, () -> WalWriter.replaySealed(path, 8, null));
        assertThrows(IllegalArgumentException.class, () -> WalWriter.replaySealed(path, 7, record -> { }));
        ReadOnlyIo io = new ReadOnlyIo(path);
        assertThrows(IllegalArgumentException.class, () -> WalWriter.replaySealed(io, 7, record -> { }));
        assertEquals(1, io.closeCalls);
    }

    private static void writeLog(Path path, WalRecord... records) throws IOException {
        try (WalWriter writer = WalWriter.openEmpty(path)) {
            for (WalRecord record : records) {
                writer.append(record);
            }
        }
    }

    private static final class ReadOnlyIo implements WalIo {
        private final FileChannel channel;
        private final IOException closeFailure = new IOException("close failed");
        private int maxRead = Integer.MAX_VALUE;
        private int closeCalls;
        private boolean failRead;
        private boolean failClose;

        ReadOnlyIo(Path path) throws IOException {
            channel = FileChannel.open(path, READ);
        }

        @Override
        public long size() throws IOException { return channel.size(); }

        @Override
        public int read(ByteBuffer target, long offset) throws IOException {
            if (failRead) { throw new IOException("read failed"); }
            int oldLimit = target.limit();
            try {
                target.limit(target.position() + Math.min(maxRead, target.remaining()));
                return channel.read(target, offset);
            } finally {
                target.limit(oldLimit);
            }
        }

        @Override
        public int write(ByteBuffer source, long offset) { throw new AssertionError("write in sealed replay"); }
        @Override
        public void truncate(long size) { throw new AssertionError("truncate in sealed replay"); }
        @Override
        public void force() { throw new AssertionError("force in sealed replay"); }

        @Override
        public void close() throws IOException {
            closeCalls++;
            channel.close();
            if (failClose) { throw closeFailure; }
        }
    }
}
