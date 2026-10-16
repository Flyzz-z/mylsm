package io.github.flyzz.mylsm.lsm;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Objects;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * 单实例独占、串行访问的 Manifest 追加与恢复器，不提供线程锁。
 * 只处理元信息文件；不创建、验证或删除其引用的 WAL/SSTable。
 */
final class ManifestFile implements AutoCloseable {
    private enum State { OPEN, FAILED, CLOSED }

    private final ManifestIo io;
    private ManifestState current;
    private long appendOffset;
    private State state = State.OPEN;

    private ManifestFile(ManifestIo io, ManifestState current, long appendOffset) {
        this.io = io;
        this.current = current;
        this.appendOffset = appendOffset;
    }

    /**
     * 仅新建路径；先验证初始状态，再创建并写出 Header/首状态，force 成功后返回。
     * 初始化失败关闭资源但保留文件；不覆盖已有文件，不保证目录项断电持久性。
     */
    static ManifestFile create(Path path, ManifestState initial) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(initial, "initial").requireInitial();
        return create(new ChannelIo(FileChannel.open(path, CREATE_NEW, READ, WRITE)), initial);
    }

    /** 接管 io，包括参数或初始化失败时的关闭责任；io 必须为新建的空文件。 */
    static ManifestFile create(ManifestIo io, ManifestState initial) throws IOException {
        Objects.requireNonNull(io, "io");
        try {
            Objects.requireNonNull(initial, "initial").requireInitial();
            if (io.size() != 0) {
                throw new IOException("New Manifest is not empty");
            }
            byte[] header = ManifestCodec.encodeHeader();
            byte[] frame = ManifestCodec.encode(initial);
            writeFully(io, ByteBuffer.wrap(header), 0);
            writeFully(io, ByteBuffer.wrap(frame), header.length);
            io.force();
            return new ManifestFile(io, initial, (long) header.length + frame.length);
        } catch (IOException | RuntimeException | Error failure) {
            closeOnFailure(io, failure);
            throw failure;
        }
    }

    /** 仅打开既有文件；无首个完整有效状态时失败，不自动初始化。 */
    static ManifestFile open(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return open(new ChannelIo(FileChannel.open(path, READ, WRITE)));
    }

    /** 接管 io；打开失败不返回部分实例，并保留主错误与关闭错误。 */
    static ManifestFile open(ManifestIo io) throws IOException {
        Objects.requireNonNull(io, "io");
        try {
            Recovered recovered = recover(io);
            return new ManifestFile(io, recovered.current(), recovered.appendOffset());
        } catch (IOException | RuntimeException | Error failure) {
            closeOnFailure(io, failure);
            throw failure;
        }
    }

    /** 返回最近提交成功或恢复选定的状态；失败/关闭后拒绝读取缓存状态。 */
    ManifestState current() {
        requireOpen();
        return current;
    }

    /**
     * 参数与转换验证发生在写入前，参数错误不使实例失效。
     * 开始 I/O 后失败则只允许关闭；恢复可能采用已完整写出但未报告成功的状态。
     */
    void append(ManifestState next) throws IOException {
        requireOpen();
        current.requireSuccessor(next);
        byte[] frame = ManifestCodec.encode(next);
        try {
            if (frame.length > Long.MAX_VALUE - appendOffset) {
                throw new IOException("Manifest append offset would overflow");
            }
            writeFully(io, ByteBuffer.wrap(frame), appendOffset);
            io.force();
            appendOffset += frame.length;
            current = next;
        } catch (IOException | RuntimeException | Error failure) {
            state = State.FAILED;
            throw failure;
        }
    }

    /** 重复关闭安全；关闭抛错后也不允许重新使用或再次释放底层资源。 */
    @Override
    public void close() throws IOException {
        if (state != State.CLOSED) {
            state = State.CLOSED;
            io.close();
        }
    }

    private void requireOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException("Manifest file is " + state);
        }
    }

    private static Recovered recover(ManifestIo io) throws IOException {
        long fileSize = io.size();
        if (fileSize < ManifestCodec.HEADER_SIZE) {
            throw new EOFException("Incomplete Manifest header");
        }
        byte[] header = new byte[ManifestCodec.HEADER_SIZE];
        readFully(io, ByteBuffer.wrap(header), 0);
        ManifestCodec.decodeHeader(header);

        long validEnd = header.length;
        ManifestState latest = null;
        ByteBuffer prefix = ByteBuffer.allocate(ManifestCodec.PREFIX_SIZE).order(ByteOrder.BIG_ENDIAN);
        while (validEnd < fileSize) {
            long remaining = fileSize - validEnd;
            if (remaining < ManifestCodec.PREFIX_SIZE) {
                break;
            }
            prefix.clear();
            readFully(io, prefix, validEnd);
            int frameLength;
            try {
                frameLength = ManifestCodec.checkedFrameLength(prefix.getInt(0));
            } catch (IOException failure) {
                throw invalidRecord(validEnd, failure);
            }
            if (frameLength > remaining) {
                break; // 长度合法但载荷残缺。尚未分配声明的整个帧。
            }
            byte[] frame = new byte[frameLength];
            System.arraycopy(prefix.array(), 0, frame, 0, ManifestCodec.PREFIX_SIZE);
            readFully(io, ByteBuffer.wrap(frame, ManifestCodec.PREFIX_SIZE,
                    frameLength - ManifestCodec.PREFIX_SIZE), validEnd + ManifestCodec.PREFIX_SIZE);
            try {
                ManifestState next = ManifestCodec.decode(frame);
                if (latest == null) {
                    next.requireInitial();
                } else {
                    latest.requireSuccessor(next);
                }
                latest = next;
            } catch (IOException | IllegalArgumentException failure) {
                throw invalidRecord(validEnd, failure);
            }
            validEnd += frameLength; // 已验证 frameLength <= fileSize - validEnd，不会溢出。
        }
        if (latest == null) {
            throw new IOException("Manifest has no complete initial state");
        }
        if (validEnd < fileSize) {
            io.truncate(validEnd);
            io.force();
        }
        return new Recovered(latest, validEnd);
    }

    private static IOException invalidRecord(long offset, Exception cause) {
        return new IOException("Invalid Manifest record at offset " + offset + ": " + cause.getMessage(), cause);
    }

    private static void readFully(ManifestIo io, ByteBuffer target, long offset) throws IOException {
        while (target.hasRemaining()) {
            int read = io.read(target, offset);
            if (read < 0) {
                throw new EOFException("Unexpected EOF in Manifest at offset " + offset);
            }
            if (read == 0) {
                throw new IOException("Manifest read made no progress at offset " + offset);
            }
            offset += read;
        }
    }

    private static void writeFully(ManifestIo io, ByteBuffer source, long offset) throws IOException {
        while (source.hasRemaining()) {
            int written = io.write(source, offset);
            if (written <= 0) {
                throw new IOException("Manifest write made no progress at offset " + offset);
            }
            offset += written;
        }
    }

    private static void closeOnFailure(ManifestIo io, Throwable failure) {
        try {
            io.close();
        } catch (IOException | RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != failure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private record Recovered(ManifestState current, long appendOffset) {
    }

    private record ChannelIo(FileChannel channel) implements ManifestIo {
        @Override
        public long size() throws IOException {
            return channel.size();
        }

        @Override
        public int read(ByteBuffer target, long offset) throws IOException {
            return channel.read(target, offset);
        }

        @Override
        public int write(ByteBuffer source, long offset) throws IOException {
            return channel.write(source, offset);
        }

        @Override
        public void truncate(long size) throws IOException {
            channel.truncate(size);
        }

        @Override
        public void force() throws IOException {
            channel.force(true);
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
